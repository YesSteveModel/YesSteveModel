package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.jar.JarFile;
import java.util.*;

/** Collects both sources and publishes once, after every class has reached a final outcome. */
public final class MolangExtensions {
    private MolangExtensions() {}

    public record DeclarationRef(String className, String namespace, String claimedModId) {
        public DeclarationRef {
            Objects.requireNonNull(className);
            Objects.requireNonNull(namespace);
            claimedModId = claimedModId == null ? "" : claimedModId;
        }
    }

    public record Source(String id, Set<String> modIds, List<DeclarationRef> declarations, ClassLoader loader,
        Path file) {
        public Source(String id, Set<String> modIds, List<DeclarationRef> declarations, ClassLoader loader) {
            this(id, modIds, declarations, loader, null);
        }

        public Source {
            Objects.requireNonNull(id);
            Objects.requireNonNull(loader);
            modIds = Set.copyOf(modIds);
            declarations = List.copyOf(declarations);
            if (file != null)
                file = file.toAbsolutePath().normalize();
        }

        byte[] read(String resource, URL origin) throws IOException {
            byte[] visible;
            // The admission read owns its stream, not a process-wide cached JAR handle.
            var connection = origin.openConnection();
            connection.setUseCaches(false);
            try (var input = connection.getInputStream()) {
                visible = input.readNBytes(1_048_577);
            }
            if (visible.length > 1_048_576)
                throw new IOException("source resource exceeds admission limit");
            if (file == null)
                return visible;
            byte[] owned;
            if (Files.isDirectory(file)) {
                Path target = file.resolve(resource).normalize();
                if (!target.startsWith(file))
                    throw new IOException("resource escapes source");
                try (var input = Files.newInputStream(target)) {
                    owned = input.readNBytes(1_048_577);
                }
            } else
                try (var jar = new JarFile(file.toFile())) {
                    var entry = jar.getJarEntry(resource);
                    if (entry == null)
                        throw new IOException("resource is not in discovered source file");
                    try (var input = jar.getInputStream(entry)) {
                        owned = input.readNBytes(1_048_577);
                    }
                }
            if (!Arrays.equals(visible, owned))
                throw new IOException("classloader resource differs from discovered source file");
            return owned;
        }
    }

    public record Admission(ExpressionHost host, List<String> rejectedMods, List<String> diagnostics) {
        public Admission {
            rejectedMods = List.copyOf(rejectedMods);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public interface Qualification {
        boolean dependency(String modId, String version);

        boolean checker(Source source, DeclarationSchema schema);
    }

    public record Submission(String resource, URL origin, boolean accepted, String detail) {
    }

    public static final class Collector {
        private enum State {
            COLLECTING, RESOLVING, FROZEN
        }

        private State state = State.COLLECTING;
        private final List<Source> sources = new ArrayList<>();
        private final Map<String, List<Submission>> submissions = new TreeMap<>();
        private Source builtinSource;

        public synchronized void collectBuiltins() {
            requireCollecting();
            if (builtinSource != null)
                throw new IllegalStateException("Builtin declarations already collected");
            builtinSource = BuiltinDeclarations.source();
            sources.add(builtinSource);
        }

        public synchronized void collect(Source source) {
            requireCollecting();
            sources.add(Objects.requireNonNull(source));
        }

        public synchronized void submit(Submission submission) {
            requireCollecting();
            submissions.computeIfAbsent(submission.resource(), ignored -> new ArrayList<>()).add(submission);
        }

        private void requireCollecting() {
            if (state != State.COLLECTING)
                throw new IllegalStateException("Molang declarations are sealed");
        }

        public synchronized Admission freeze(HostAvailability availability, Qualification qualification) {
            return freeze(availability, qualification, ExpressionPolicy.COMPILATION);
        }

        private synchronized Admission freeze(HostAvailability availability, Qualification qualification,
            dev.semaweave.CompilationPolicy policy) {
            Objects.requireNonNull(policy, "policy");
            requireCollecting();
            state = State.RESOLVING;
            var diagnostics = new ArrayList<String>();
            var candidates = new ArrayList<Candidate>();
            var rejected = new TreeSet<String>();
            var ordered = sources.stream().sorted(Comparator.comparing(Source::id)).toList();
            for (var source : ordered)
                for (var ref : source.declarations()) {
                    String resource = DeclarationSchema.RESOURCE_ROOT + ref.className() + ".bin";
                    String verifiedModId = null;
                    String stage = "METADATA";
                    try {
                        URL url = source.loader().getResource(resource);
                        if (url == null)
                            throw new IOException("missing safe declaration resource");
                        DeclarationSchema schema;
                        schema = DeclarationSchema.decode(new ByteArrayInputStream(source.read(resource, url)));
                        if (!schema.owner().equals(ref.className()) || !schema.namespace().equals(ref.namespace())
                            || !schema.modId().equals(ref.claimedModId()))
                            throw new IllegalArgumentException("discovery/declaration facts mismatch");
                        if (schema.builtin() != (source == builtinSource))
                            throw new IllegalArgumentException("declaration source trust mismatch");
                        stage = "OWNERSHIP";
                        verifiedModId = source == builtinSource ? "ysm" : resolveModId(source, schema.modId());
                        stage = "QUALIFICATION";
                        var manual = submissions.getOrDefault(resource, List.of());
                        if (schema.manual()) {
                            if (manual.size() != 1)
                                throw new IllegalArgumentException(manual.isEmpty() ? "manual declaration not submitted"
                                    : "duplicate manual submission");
                            var receipt = manual.get(0);
                            if (!receipt.origin().equals(url) || !receipt.accepted())
                                throw new IllegalArgumentException(
                                    "manual precheck rejected or source mismatch: " + receipt.detail());
                        } else if (!manual.isEmpty())
                            throw new IllegalArgumentException("auto/manual double submission");
                        for (var dependency : schema.dependencies())
                            if (!qualification.dependency(dependency.modId(), dependency.version()))
                                throw new IllegalArgumentException("dependency rejected: " + dependency.modId());
                        if (!qualification.checker(source, schema))
                            throw new IllegalArgumentException("compatibility checker rejected");
                        candidates.add(new Candidate(source, schema, verifiedModId, resource, url));
                    } catch (IOException | RuntimeException | LinkageError failure) {
                        diagnostics.add(ref.className() + " [" + source.id()
                            + (verifiedModId == null ? "" : "; mod=" + verifiedModId) + "]: " + stage + ": " + failure);
                        if (verifiedModId != null && source != builtinSource)
                            rejected.add(verifiedModId);
                    }
                }
            var conflicts = new HashSet<Candidate>();
            var identities = new HashMap<String, List<Candidate>>();
            var symbols = new HashMap<String, List<Candidate>>();
            var namespaces = new HashMap<String, List<Candidate>>();
            for (var c : candidates) {
                identities.computeIfAbsent(c.schema.owner(), ignored -> new ArrayList<>()).add(c);
                for (var symbol : c.schema.members().stream().map(DeclarationSchema.Member::symbol).distinct().toList())
                    symbols.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(c);
                if (!c.schema.builtin())
                    for (var namespace : c.schema.members().stream().map(DeclarationSchema.Member::namespace).distinct()
                        .toList())
                        namespaces.computeIfAbsent(namespace, ignored -> new ArrayList<>()).add(c);
            }
            identities.values().stream().filter(v -> v.size() > 1).forEach(conflicts::addAll);
            symbols.values().stream().filter(v -> v.size() > 1).forEach(conflicts::addAll);
            namespaces.values().stream().filter(v -> v.stream().map(Candidate::verifiedModId).distinct().count() > 1)
                .forEach(conflicts::addAll);
            var published = new ArrayList<LinkedDeclaration>();
            var publishedBuiltins = new ArrayList<LinkedDeclaration>();
            for (var c : candidates.stream().sorted(Comparator.comparing(v -> v.schema.owner())).toList()) {
                String stage = "CONFLICT";
                try {
                    if (conflicts.contains(c))
                        throw new IllegalArgumentException("duplicate declaration or symbol conflict");
                    stage = "RESOLUTION";
                    for (String className : List.of(c.schema.owner(), c.schema.helper())) {
                        String path = className.replace('.', '/') + ".class";
                        URL origin = c.source.loader().getResource(path);
                        if (origin == null)
                            throw new IOException("missing implementation/helper class resource");
                        c.source.read(path, origin);
                    }
                    stage = "BINDING";
                    var declaration = LinkedDeclaration.link(c.schema, c.source.loader(), c.origin, c.verifiedModId);
                    c.source.read(c.schema.owner().replace('.', '/') + ".class",
                        declaration.owner().getResource("/" + c.schema.owner().replace('.', '/') + ".class"));
                    c.source.read(c.schema.helper().replace('.', '/') + ".class",
                        declaration.helper().getResource("/" + c.schema.helper().replace('.', '/') + ".class"));
                    stage = "INITIALIZATION";
                    Class.forName(c.schema.owner(), true, c.source.loader());
                    published.add(declaration);
                    if (c.source == builtinSource)
                        publishedBuiltins.add(declaration);
                } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError failure) {
                    diagnostics.add(c.schema.owner() + " [mod=" + c.verifiedModId + "]: " + stage + ": " + failure);
                    if (!c.schema.builtin())
                        rejected.add(c.verifiedModId);
                }
            }
            for (var resource : submissions.keySet())
                if (candidates.stream().noneMatch(c -> c.resource.equals(resource)))
                    diagnostics.add(resource + ": QUALIFICATION: submission has no discovered owning source");
            try {
                ExpressionHost host = ExpressionHost.createFrozen(availability, policy,
                    List.copyOf(published), List.copyOf(publishedBuiltins));
                state = State.FROZEN;
                return new Admission(host, List.copyOf(rejected), diagnostics);
            } finally {
                state = State.FROZEN;
            }
        }
    }

    private record Candidate(Source source, DeclarationSchema schema, String verifiedModId, String resource,
        URL origin) {
    }

    private static String resolveModId(Source source, String explicit) {
        var valid = source.modIds().stream().filter(id -> id.matches("[a-z][a-z0-9_]*")).toList();
        if (source.file() == null || valid.isEmpty())
            throw new IllegalArgumentException("public declaration has no valid owning source");
        if (!explicit.isEmpty()) {
            if (!valid.contains(explicit))
                throw new IllegalArgumentException("explicit mod id does not belong to the source file");
            return explicit;
        }
        if (valid.size() != 1)
            throw new IllegalArgumentException("source mod ownership is ambiguous; specify modId");
        return valid.get(0);
    }

    public static Admission admit(HostAvailability availability, List<Source> sources) {
        return admit(availability, sources, ExpressionPolicy.COMPILATION);
    }

    static Admission admit(HostAvailability availability, List<Source> sources, dev.semaweave.CompilationPolicy policy) {
        Collector collector = new Collector();
        collector.collectBuiltins();
        sources.forEach(collector::collect);
        return collector.freeze(availability, new Qualification() {
            public boolean dependency(String id, String version) {
                String actual = availability.versions().get(id);
                return actual != null
                    && (version.isEmpty() || actual.equals("unknown") || versionMatches(actual, version));
            }

            public boolean checker(Source source, DeclarationSchema schema) {
                if (schema.builtin())
                    return schema.dependencies().stream().allMatch(d -> Arrays.stream(OptionalCapability.values())
                        .filter(c -> c.modId().equals(d.modId())).allMatch(availability::supports));
                if (schema.manual())
                    return true;
                try {
                    var result =
                        com.elfmcys.ysm.api.internal.CompatibilityQualification.check(schema.owner(), source.loader());
                    if (!result.compatible())
                        throw new IllegalArgumentException(
                            "compatibility checker rejected: " + result.status() + " " + result.issues());
                    return true;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalArgumentException("generated checker failed", failure);
                }
            }
        }, policy);
    }

    static boolean versionMatches(String actual, String range) {
        try {
            return org.apache.maven.artifact.versioning.VersionRange.createFromVersionSpec(range)
                .containsVersion(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(actual));
        } catch (org.apache.maven.artifact.versioning.InvalidVersionSpecificationException failure) {
            throw new IllegalArgumentException("invalid loader version range", failure);
        }
    }
}
