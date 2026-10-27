package com.elfmcys.ysm.mock.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class EvidenceRun {
    private final Path root;
    private final Metadata metadata;

    private EvidenceRun(Path root, Metadata metadata) {
        this.root = root;
        this.metadata = metadata;
    }

    public static EvidenceRun open(Path root, Metadata metadata) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(metadata, "metadata");
        var absolute = root.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        EvidenceJson.writeJsonIdentical(absolute.resolve("run.json"), metadata);
        return new EvidenceRun(absolute, metadata);
    }

    public static boolean isConfigured() {
        var configured = System.getProperty("ysm.mock.evidenceRoot");
        return configured != null && !configured.isBlank();
    }

    public static EvidenceRun openConfigured() throws IOException {
        var root = configuredRoot();
        return open(root,
            new Metadata(new Revisions(3, 3, 2, Integer.parseInt(requiredProperty("ysm.mock.implementationRevision"))),
                Map.of("java", requiredProperty("ysm.mock.javaRevision")),
                Map.of("java", System.getProperty("java.version"), "task", "modelManagementMockDomain"),
                Map.of("arch", System.getProperty("os.arch"), "os", System.getProperty("os.name")),
                List.of(requiredProperty("ysm.mock.command")), requiredProperty("ysm.mock.startedAt"),
                root.toString()));
    }

    private static Path configuredRoot() {
        var configured = System.getProperty("ysm.mock.evidenceRoot");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing system property: ysm.mock.evidenceRoot");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static String requiredProperty(String name) {
        var value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property: " + name);
        }
        return value;
    }

    public ScenarioIdentity identity(String scenarioId, List<String> finalAcceptance, List<String> contractObligations,
        Radius radius, boolean acceptanceEligible, byte[] canonicalInput) {
        return new ScenarioIdentity(scenarioId, finalAcceptance, contractObligations, metadata.revisions(),
            metadata.authorityRevisions(), metadata.configuration(), metadata.platform(),
            EvidenceJson.sha256(canonicalInput), radius, acceptanceEligible);
    }

    public ScenarioEvidence scenario(ScenarioIdentity identity, byte[] canonicalInput) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(canonicalInput, "canonicalInput");
        if (!metadata.revisions().equals(identity.revisions())
            || !metadata.authorityRevisions().equals(identity.authorityRevisions())
            || !metadata.configuration().equals(identity.configuration())
            || !metadata.platform().equals(identity.platform())) {
            throw new IllegalArgumentException("Scenario identity does not match run metadata");
        }
        if (!EvidenceJson.sha256(canonicalInput).equals(identity.inputSha256())) {
            throw new IllegalArgumentException("Scenario input does not match its fixed hash");
        }
        return ScenarioEvidence.create(this, root, identity, canonicalInput);
    }

    synchronized void append(IndexEntry entry) throws IOException {
        var line = EvidenceJson.canonicalBytes(entry);
        var index = root.resolve("index.jsonl");
        try (var output = Files.newOutputStream(index, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.APPEND)) {
            output.write(line);
            output.write('\n');
        }
    }

    static String requireSegment(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(name + " must be a safe path segment");
        }
        return value;
    }

    private static List<String> sortedStrings(List<String> values, String name, boolean allowEmpty) {
        Objects.requireNonNull(values, name);
        var result = new ArrayList<String>(values.size());
        for (var value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank values");
            }
            result.add(value);
        }
        if (!allowEmpty && result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        Collections.sort(result);
        if (result.size() != result.stream().distinct().count()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return List.copyOf(result);
    }

    private static Map<String, String> sortedMap(Map<String, String> values, String name) {
        Objects.requireNonNull(values, name);
        var result = new TreeMap<String, String>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank keys and values");
            }
            result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    public enum Radius {
        UNIT, DETERMINISTIC_COMPOSITION, CORE_WORKLOAD, CLASSPATH_SYSTEM, FORGE_HOST
    }

    public record Revisions(int taskContext, int decision, int contract, int implementation) {
        public Revisions {
            if (taskContext < 1 || decision < 1 || contract < 1 || implementation < 1) {
                throw new IllegalArgumentException("All revisions must be positive");
            }
        }
    }

    public record Metadata(Revisions revisions, Map<String, String> authorityRevisions,
        Map<String, String> configuration, Map<String, String> platform, List<String> command, String startedAt,
        String outputDirectory) {
        public Metadata {
            Objects.requireNonNull(revisions, "revisions");
            authorityRevisions = sortedMap(authorityRevisions, "authorityRevisions");
            configuration = sortedMap(configuration, "configuration");
            platform = sortedMap(platform, "platform");
            command = immutableStrings(command, "command", false);
            if (startedAt == null || startedAt.isBlank() || outputDirectory == null || outputDirectory.isBlank()) {
                throw new IllegalArgumentException("Run-local metadata must not be blank");
            }
        }
    }

    private static List<String> immutableStrings(List<String> values, String name, boolean allowEmpty) {
        Objects.requireNonNull(values, name);
        if (!allowEmpty && values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        for (var value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank values");
            }
        }
        return List.copyOf(values);
    }

    public record ScenarioIdentity(String scenarioId, List<String> finalAcceptance, List<String> contractObligations,
        Revisions revisions, Map<String, String> authorityRevisions, Map<String, String> configuration,
        Map<String, String> platform, String inputSha256, Radius radius, boolean acceptanceEligible) {
        public ScenarioIdentity {
            scenarioId = requireSegment(scenarioId, "scenarioId");
            finalAcceptance = sortedStrings(finalAcceptance, "finalAcceptance", true);
            contractObligations = sortedStrings(contractObligations, "contractObligations", false);
            Objects.requireNonNull(revisions, "revisions");
            authorityRevisions = sortedMap(authorityRevisions, "authorityRevisions");
            configuration = sortedMap(configuration, "configuration");
            platform = sortedMap(platform, "platform");
            if (inputSha256 == null || !inputSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("inputSha256 must be lowercase SHA-256 hex");
            }
            Objects.requireNonNull(radius, "radius");
            if (acceptanceEligible && finalAcceptance.isEmpty()) {
                throw new IllegalArgumentException("Acceptance-eligible scenarios must bind final acceptance IDs");
            }
        }
    }

    record IndexEntry(String scenarioId, boolean acceptanceEligible, List<String> finalAcceptance,
        List<String> contractObligations, ScenarioEvidence.Outcome verdict, String inputSha256, String verdictArtifact,
        String inventoryArtifact) {
    }
}
