package com.elfmcys.ysm.verification;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.internal.expression.semaweave.*;
import com.elfmcys.ysm.internal.expression.semaweave.backend.janino.JaninoJavaCompilerBackend;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.*;
import com.elfmcys.ysm.internal.expression.semaweave.program.*;
import com.google.gson.GsonBuilder;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Optional classes are absent from the application classpath, including in the child JVM.
 */
public final class PackagedDeclarationMatrix {
    private static final List<String> SCENARIOS = List.of("all", "no-a", "no-b", "neither", "version", "method", "init",
            "binding", "no-declaration", "all-restored", "reason-changed", "manual", "manual-failed", "manual-duplicate",
            "auto-double", "conflict", "reversed");

    public static void main(String[] args) throws Throwable {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        if (args.length == 7) {
            child(output, Arrays.copyOfRange(args, 1, 6), args[6]);
            return;
        }
        output = Files.createTempDirectory(output, "run-");
        args[0] = output.toString();
        System.out.println("DECLARATION_MATRIX_OUTPUT " + output);
        for (String scenario : Boolean.getBoolean("matrix.composition")
                ? List.of("compose-cold", "compose-warm", "compose-conflict") : SCENARIOS) {
            Path log = output.resolve(scenario + ".log");
            var command =
                    new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                            "-Xlog:class+load=info:file="
                                    + output.resolve(scenario + "-classes.log").toString().replace('\\', '/'),
                            "-cp", System.getProperty("java.class.path"), PackagedDeclarationMatrix.class.getName()));
            command.addAll(List.of(args));
            command.add(scenario);
            int exit =
                    new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start().waitFor();
            require(exit == 0, scenario + " failed; see " + log);
            if (List.of("no-a", "no-b", "neither", "version", "method", "binding", "reason-changed", "conflict")
                    .contains(scenario))
                require(!Files.readString(output.resolve(scenario + "-classes.log")).contains(" independent.Combined "),
                        "JVM loaded rejected Combined");
            System.out.println("DECLARATION_MATRIX_OK " + scenario);
        }
    }

    private static void child(Path output, String[] jars, String scenario) throws Throwable {
        boolean composed = scenario.startsWith("compose-");
        boolean a = !List.of("no-a", "neither").contains(scenario);
        boolean b = !List.of("no-b", "neither").contains(scenario);
        boolean declaration = !scenario.equals("no-declaration");
        var urls = new ArrayList<URL>();
        if (declaration)
            urls.add(Path.of(jars[scenario.equals("binding") ? 4 : 0]).toUri().toURL());
        if (scenario.equals("compose-conflict"))
            urls.add(Path.of(jars[4]).toUri().toURL());
        if (a)
            urls.add(Path.of(jars[1]).toUri().toURL());
        if (b)
            urls.add(Path.of(jars[scenario.equals("method") ? 3 : 2]).toUri().toURL());
        for (String path : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            urls.add(Path.of(path).toUri().toURL());
        System.setProperty("matrix.failInitialization", Boolean.toString(scenario.equals("init")));
        try (var loader = new TraceLoader(urls.toArray(URL[]::new))) {
            try {
                loader.loadClass(PackagedDeclarationMatrix.class.getName())
                        .getMethod("runChild", String.class, String[].class, String.class, ClassLoader.class)
                        .invoke(null, output.toString(), jars, scenario, loader);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    public static void runChild(String outputDirectory, String[] jars, String scenario, ClassLoader loader)
            throws Throwable {
        Path output = Path.of(outputDirectory);
        boolean composed = scenario.startsWith("compose-");
        boolean a = !List.of("no-a", "neither").contains(scenario);
        boolean b = !List.of("no-b", "neither").contains(scenario);
        boolean declaration = !scenario.equals("no-declaration");
        boolean published = !scenario.equals("compose-conflict") && declaration && a && b
                && !List.of("version", "method", "init", "binding", "reason-changed", "conflict").contains(scenario);
        Path declarationFile = Path.of(jars[scenario.equals("binding") ? 4 : 0]);
        var versions = new TreeMap<String, String>();
        if (a)
            versions.put("a", "1.0");
        if (b)
            versions.put("b", scenario.equals("version") || scenario.equals("reason-changed") ? "3.0" : "1.0");
        var collector = new MolangExtensions.Collector();
        var names = new ArrayList<>(composed ? List.of("Query", "Actions") : List.of("Combined", "AOnly", "Probe", "Manual"));
        if (scenario.equals("reversed"))
            Collections.reverse(names);
        if (declaration) {
            var refs = names.stream()
                    .map(n -> new MolangExtensions.DeclarationRef("independent." + n, "matrix_mod", "")).toList();
            collector
                    .collect(new MolangExtensions.Source("matrix", Set.of("matrix_mod"), refs, loader, declarationFile));
            if (scenario.equals("conflict"))
                collector.collect(new MolangExtensions.Source("duplicate", Set.of("matrix_mod"),
                        List.of(refs.stream().filter(r -> r.className().endsWith("Combined")).findFirst().orElseThrow()),
                        loader, Path.of(jars[0])));
            if (scenario.startsWith("manual")) {
                submit(collector, loader, "Manual", !scenario.equals("manual-failed"));
                if (scenario.equals("manual-duplicate"))
                    submit(collector, loader, "Manual", true);
            }
            if (scenario.equals("auto-double"))
                submit(collector, loader, "AOnly", true);
        }
        if (scenario.equals("compose-conflict"))
            collector.collect(new MolangExtensions.Source("other", Set.of("other_mod"),
                    List.of(new MolangExtensions.DeclarationRef("independent.Other", "matrix_mod", "")), loader, Path.of(jars[4])));
        var availability = new HostAvailability(Set.of(), versions);
        var admission = collector.freeze(availability, new MolangExtensions.Qualification() {
            public boolean dependency(String id, String range) {
                String version = versions.get(id);
                if (version == null)
                    return false;
                try {
                    return range.isEmpty()
                            || org.apache.maven.artifact.versioning.VersionRange.createFromVersionSpec(range)
                            .containsVersion(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(version));
                } catch (Exception failure) {
                    throw new IllegalArgumentException(failure);
                }
            }

            public boolean checker(MolangExtensions.Source source, DeclarationSchema schema) {
                try {
                    if (schema.manual())
                        return true;
                    if (schema.owner().endsWith("Combined")) {
                        // Independent precheck probes only dependency API, never the implementation.
                        Class.forName("optional.b.Api", false, loader).getMethod("value");
                    }
                    return com.elfmcys.ysm.api.internal.CompatibilityQualification.check(schema.owner(), loader)
                            .compatible();
                } catch (ReflectiveOperationException failure) {
                    return false;
                }
            }
        });
        try {
            collector.collect(new MolangExtensions.Source("late", Set.of(), List.of(), loader));
            throw new AssertionError("late collector mutation accepted");
        } catch (IllegalStateException expected) {
        }
        try {
            submit(collector, loader, "Manual", true);
            throw new AssertionError("late submission accepted");
        } catch (IllegalStateException expected) {
        }
        var host = admission.host();
        var symbols = host.registry().functions().keySet().stream().map(SymbolKey::canonical).sorted().toList();
        require(symbols.contains("matrix_mod:action") == published, "publication " + admission.diagnostics());
        boolean aOnly = !composed && declaration && a && !scenario.equals("auto-double");
        require(symbols.contains("matrix_mod:a_value") == aOnly, "independent A declaration");
        require(symbols.contains("matrix_mod:manual") == scenario.equals("manual"), "manual publication");
        if (scenario.equals("compose-conflict")) {
            require(symbols.isEmpty(), "conflicting namespaces retained symbols " + symbols);
            require(requests().stream().noneMatch(n -> List.of("Query", "Actions", "Other").stream().anyMatch(c -> n.equals("independent." + c) || n.startsWith("independent." + c + "$") || n.startsWith("independent." + c + "MolangBindings"))), "conflict loaded implementation " + requests());
            require(Integer.getInteger("composition.init", 0) == 0, "conflict initialized implementation");
            Files.writeString(output.resolve(scenario + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("symbols", symbols, "diagnostics", admission.diagnostics(), "loadRequests", requests(), "initializations", 0)));
            return;
        }
        boolean preRejected = !composed && declaration && !published && !scenario.equals("init");
        if (preRejected) {
            require(
                    requests().stream()
                            .noneMatch(n -> n.equals("independent.Combined") || n.startsWith("independent.Combined$")
                                    || n.startsWith("independent.CombinedMolangBindings")),
                    "precheck loaded implementation " + requests());
            require(Integer.getInteger("combined.init", 0) == 0, "precheck initialized implementation");
        }
        if (scenario.equals("init"))
            require(admission.diagnostics().stream().anyMatch(s -> s.contains("INITIALIZATION")),
                    "initialization provenance");
        if (scenario.equals("binding"))
            require(
                    admission.diagnostics().stream().anyMatch(
                            s -> s.contains("RESOLUTION") && s.contains("missing implementation/helper class resource")),
                    "missing helper resolution provenance " + admission.diagnostics());
        var codec = PortableProgramCodecs.v1();
        var builder = CompilationUnit.builder(host.compilationPolicy(), codec, ExpressionIdentityHash.SHA_256);
        var action = call("action", call("probe"));
        var plus = new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD, ProgramNode.F32.of(3), action);
        var expressions = composed ? List.of(read("next"), call("defaulted"), new ProgramNode.Coalesce(read("missing"), ProgramNode.F32.of(7)), call("action", read("next")), new ProgramNode.Coalesce(call("action", read("next")), ProgramNode.F32.of(7)), read("next"), read("action"), new ProgramNode.Coalesce(read("missing"), ProgramNode.F32.of(7))) : List.of(plus, plus,
                new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD, ProgramNode.F32.of(3), call("action")),
                new ProgramNode.Coalesce(action, ProgramNode.F32.of(7)), call("a_value"),
                new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "matrix_mod:read"),
                        ProgramNode.AccessForm.READ, List.of()));
        var keys = new ArrayList<ProgramKey>();
        for (int i = 0; i < expressions.size(); i++) {
            var key = new ProgramKey(ReadOnlyBytes.copyOf(new byte[]{(byte) (i + 1)}));
            keys.add(key);
            builder
                    .addProgram(new ProgramSource(key, codec.encode(PortablePrograms.ofRoots(List.of(expressions.get(i)))),
                            new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(),
                                    List.of()),
                            Optional.empty(), new DiagnosticLocation("matrix/" + i),
                            (composed ? i == 4 : i == 1) ? EntryPermissions.CONTEXT : EntryPermissions.ACTION));
        }
        var unit = builder.build();
        var backend = new JaninoJavaCompilerBackend();
        var expressionBinding = host.expressionBinding();
        var plan = expressionBinding.bindingPlan();
        var expected = ExpectedArtifactIdentity.create(unit, host.hostAbi(), host.registry(), CompilerOptions.DEFAULT,
                plan, backend.descriptor(), ExpressionIdentityHash.SHA_256);
        String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(expected.identity().toString().getBytes(StandardCharsets.UTF_8)));
        Path artifactPath = output.resolve(identity + ".swa");
        boolean warm = Files.exists(artifactPath);
        long start = System.nanoTime();
        CompiledArtifact artifact;
        var compileStages = new EnumMap<EngineMetrics.Stage, Integer>(EngineMetrics.Stage.class);
        EngineMetrics metrics = new EngineMetrics() {
            public void recordDuration(EngineMetrics.Stage stage, long nanos) {
                compileStages.merge(stage, 1, Integer::sum);
            }
        };
        if (composed)
            require(warm == scenario.equals("compose-warm"), "composition cache source");
        if (warm)
            artifact = ArtifactCodecs.v1().decodeAndVerify(ReadOnlyBytes.copyOf(Files.readAllBytes(artifactPath)),
                    expected, ExpressionIdentityHash.SHA_256);
        else {
            artifact = new DefaultExpressionAotCompiler<MolangContext>(backend, metrics).compile(TaskContext.NONE, unit,
                    host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, plan, ExpressionIdentityHash.SHA_256);
            Files.write(artifactPath,
                    ArtifactCodecs.v1().encode(artifact, ExpressionIdentityHash.SHA_256).toByteArray());
        }
        long buildNanos = System.nanoTime() - start;
        if (List.of("all-restored", "reversed", "reason-changed").contains(scenario))
            require(warm, "restored identity must reuse disk artifact");
        for (var bytes : artifact.classes().values())
            if (!published)
                require(!new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1).contains("independent/Combined"),
                        "rejected Java linkage retained");
        var values = new ArrayList<Float>();
        try (var resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected, host.registry(),
                host.registry().visibilityRoot(), ExpressionIdentityHash.SHA_256)) {
            var subject = a ? (net.minecraft.world.entity.Entity) loader.loadClass("optional.a.Subject").getConstructor()
                    .newInstance() : new net.minecraft.world.entity.Entity();
            var holder = new DeclarationComponentScope(new com.elfmcys.ysm.client.entity.AnimatableEntity<>(subject),
                    host.declarations());
            var registryLinkage =
                    RegistryRuntimeLinkage.<MolangContext>create(holder.providers(host, plan), RuntimeSymbolTable.empty());
            RuntimeLinkage<MolangContext> linkage = new RuntimeLinkage<>() {
                public ContextType<MolangContext> contextType() {
                    return host.contextType();
                }

                public RegistryRuntimeLinkage<MolangContext> registry() {
                    return registryLinkage;
                }

                public BakedConstantTable constants() {
                    return artifact.constants();
                }

                public RuntimeDiagnostics diagnostics() {
                    return RuntimeDiagnostics.none();
                }

                public ResourceLease<MolangContext> retainResource() {
                    return resource.retain();
                }
            };
            var roaming = resource.newRoamingState(RoamingRole.PRODUCER);
            var state = MolangContext.logical(new EntityView() {
                public net.minecraft.world.entity.Entity entity() {
                    return subject;
                }
            }, new RenderView() {
            }, MolangContext.LogicalPhase.ACTION, (command, scope) -> {
            }, roaming, new InvocationProvenance("matrix", scenario, 0, 1, 1), () -> false, () -> true, 16);
            state.configureLimits(host.executionLimits());
            try (var instance = resource.newInstance(linkage, roaming)) {
                for (var key : keys) {
                    var id = resource.bindProgram(key, ProgramShape.F32);
                    state.bindExpression("matrix", id.sourceId());
                    try {
                        values.add(instance.executeFloat(id, state,
                                instance.bindSubject(MolangContext.subjectOrNull(state)), state));
                    } finally {
                        state.releaseExpression();
                    }
                }
                if (composed) {
                    require(values.equals(List.of(1f, 2f, 7f, 103f, 7f, 4f, 100f, 7f)), "composition values " + values);
                    require(System.getProperty("composition.sequence", "").equals("1,2,3,4,"), "component sequence");
                    require(System.getProperty("composition.actions", "").equals("3.0,0.0,"), "action trace");
                    require(Integer.getInteger("composition.views", 0) == 1, "scope views differ");
                    require(state.firstFailure() == null && !state.isActive(), "expression reset");
                } else {
                    require(values.equals(
                                    List.of(published ? 12f : 3f, 3f, 3f, published ? 9f : 7f, aOnly ? 4f : 0f, published ? 9f : 0f)),
                            "values " + values);
                    require(Integer.getInteger("combined.action", 0) == (published ? 2 : 0), "provider count");
                    require(Integer.getInteger("probe.calls", 0) == (published ? 2 : 0), "argument count");
                    System.setProperty("matrix.throwProbe", "true");
                    var id = resource.bindProgram(keys.get(1), ProgramShape.F32);
                    state.bindExpression("matrix-skipped", id.sourceId());
                    try {
                        require(instance.executeFloat(id, state, instance.bindSubject(MolangContext.subjectOrNull(state)),
                                state) == 3f, "throwing skipped argument");
                    } finally {
                        state.releaseExpression();
                    }
                }
            } finally {
                state.closeRound();
                holder.invalidate();
            }
        }
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("scenario", scenario);
        receipt.put("classpath", System.getProperty("java.class.path"));
        receipt.put("urls", Arrays.toString(((URLClassLoader) loader).getURLs()));
        receipt.put("loadRequests", requests());
        receipt.put("diagnostics", admission.diagnostics());
        receipt.put("symbols", symbols);
        receipt.put("identity", identity);
        receipt.put("source", warm ? "DISK" : "COLD");
        receipt.put("artifactBytes", Files.size(artifactPath));
        receipt.put("buildNanos", buildNanos);
        require(artifact.report().replacements().isEmpty(), "unexpected whole-program replacement");
        require(Integer.getInteger(composed ? "composition.construct" : "combined.construct", 0) == (published ? 1 : 0), "constructor count");
        receipt.put("compileStages", compileStages);
        receipt.put("backendCompiles", compileStages.getOrDefault(EngineMetrics.Stage.BACKEND_COMPILATION, 0));
        if (composed)
            require(compileStages.getOrDefault(EngineMetrics.Stage.BACKEND_COMPILATION, 0) == (warm ? 0 : 1), "backend compile count");
        if (composed) {
            receipt.put("sequence", System.getProperty("composition.sequence"));
            receipt.put("actionTrace", System.getProperty("composition.actions"));
            receipt.put("viewCount", Integer.getInteger("composition.views", 0));
            receipt.put("oracle", List.of(1f, 2f, 7f, 103f, 7f, 4f, 100f, 7f));
        }
        receipt.put("values", values);
        receipt.put("localRecoveries", artifact.report().localRecoveries().stream().map(Object::toString).toList());
        receipt.put("layout", artifact.catalog().descriptors().stream().map(Object::toString).toList());
        receipt.put("bindings", host.manifest().stream().map(Object::toString).toList());
        receipt.put("init", Integer.getInteger(composed ? "composition.init" : "combined.init", 0));
        receipt.put("construct", Integer.getInteger(composed ? "composition.construct" : "combined.construct", 0));
        receipt.put("provider", Integer.getInteger("combined.action", 0));
        receipt.put("probe", Integer.getInteger("probe.calls", 0));
        Files.writeString(output.resolve(scenario + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(receipt));
    }

    private static void submit(MolangExtensions.Collector collector, ClassLoader loader, String name,
                               boolean accepted) {
        String resource = DeclarationSchema.RESOURCE_ROOT + "independent." + name + ".bin";
        collector.submit(
                new MolangExtensions.Submission(resource, loader.getResource(resource), accepted, "matrix precheck"));
    }

    private static ProgramNode read(String name) {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "matrix_mod:" + name), ProgramNode.AccessForm.READ, List.of());
    }

    private static ProgramNode call(String name, ProgramNode... arguments) {
        return new ProgramNode.Symbol(
                new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "matrix_mod:" + name),
                ProgramNode.AccessForm.INVOKE, List.of(arguments));
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private static List<String> requests() {
        return System.getProperty("matrix.requests", "").lines().toList();
    }

    private static final class TraceLoader extends URLClassLoader {
        TraceLoader(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("independent.") || name.startsWith("optional."))
                System.setProperty("matrix.requests", System.getProperty("matrix.requests", "") + name + "\n");
            return super.loadClass(name, resolve);
        }
    }
}
