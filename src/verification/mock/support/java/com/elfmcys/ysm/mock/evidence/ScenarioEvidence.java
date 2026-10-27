package com.elfmcys.ysm.mock.evidence;

import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class ScenarioEvidence {
    private final EvidenceRun run;
    private final Path directory;
    private final EvidenceRun.ScenarioIdentity identity;
    private final ArtifactInventory inventory;
    private final Map<String, JsonlEvidenceSink> sinks = new HashMap<>();
    private final List<String> failureArtifacts = new ArrayList<>();
    private boolean complete;

    private ScenarioEvidence(EvidenceRun run, Path directory, EvidenceRun.ScenarioIdentity identity,
        ArtifactInventory inventory) {
        this.run = run;
        this.directory = directory;
        this.identity = identity;
        this.inventory = inventory;
    }

    static ScenarioEvidence create(EvidenceRun run, Path root, EvidenceRun.ScenarioIdentity identity,
        byte[] canonicalInput) throws IOException {
        var directory = root.resolve(identity.scenarioId());
        var inventory = new ArtifactInventory(directory);
        var input = directory.resolve("input.json");
        EvidenceJson.writeNew(input, canonicalInput);
        inventory.register("input", input);
        return new ScenarioEvidence(run, directory, identity, inventory);
    }

    public synchronized void append(JsonlEvidenceSink.Observation observation) throws IOException {
        requireOpen();
        Objects.requireNonNull(observation, "observation");
        if (!identity.scenarioId().equals(observation.scenarioId())) {
            throw new IllegalArgumentException("Observation belongs to a different scenario");
        }
        var sink = sinks.computeIfAbsent(observation.role(),
            role -> new JsonlEvidenceSink(directory.resolve("events." + role + ".jsonl"), role));
        sink.append(observation);
    }

    public synchronized String retainFailureArtifact(String relativeName, byte[] bytes) throws IOException {
        requireOpen();
        Objects.requireNonNull(bytes, "bytes");
        var relative = Path.of(relativeName);
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.normalize().startsWith("..")) {
            throw new IllegalArgumentException("Failure artifact path must stay relative");
        }
        var artifact = directory.resolve("failure").resolve(relative).normalize();
        if (!artifact.startsWith(directory.resolve("failure"))) {
            throw new IllegalArgumentException("Failure artifact escaped its directory");
        }
        EvidenceJson.writeNew(artifact, bytes);
        var normalized = directory.relativize(artifact).toString().replace('\\', '/');
        inventory.register("failure:" + normalized, artifact);
        failureArtifacts.add(normalized);
        return normalized;
    }

    public synchronized String retainArtifact(String relativeName, byte[] bytes) throws IOException {
        requireOpen();
        Objects.requireNonNull(bytes, "bytes");
        var relative = Path.of(relativeName);
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.normalize().startsWith("..")) {
            throw new IllegalArgumentException("Artifact path must stay relative");
        }
        var artifactRoot = directory.resolve("artifacts");
        var artifact = artifactRoot.resolve(relative).normalize();
        if (!artifact.startsWith(artifactRoot)) {
            throw new IllegalArgumentException("Artifact escaped its directory");
        }
        EvidenceJson.writeNew(artifact, bytes);
        var normalized = directory.relativize(artifact).toString().replace('\\', '/');
        inventory.register("artifact:" + normalized, artifact);
        return normalized;
    }

    public synchronized void complete(Verdict verdict) throws IOException {
        requireOpen();
        Objects.requireNonNull(verdict, "verdict");
        if (verdict.outcome() == Outcome.FAIL && failureArtifacts.isEmpty()) {
            throw new IllegalStateException("Fail verdict requires a retained failure artifact");
        }
        for (var sink : sinks.values()) {
            inventory.register("events:" + sink.path().getFileName(), sink.path());
        }

        var verdictPath = directory.resolve("verdict.json");
        var result = new Result(identity, verdict.outcome(), verdict.oracleObservations(), verdict.mismatches(),
            verdict.diagnosticCounts(), List.copyOf(failureArtifacts), verdict.evidenceLimit());
        EvidenceJson.writeJsonNew(verdictPath, result);
        inventory.register("verdict", verdictPath);
        var inventoryPath = directory.resolve("artifacts.json");
        inventory.write(inventoryPath);
        run.append(new EvidenceRun.IndexEntry(identity.scenarioId(), identity.acceptanceEligible(),
            identity.finalAcceptance(), identity.contractObligations(), verdict.outcome(), identity.inputSha256(),
            relativeToRun(verdictPath), relativeToRun(inventoryPath)));
        complete = true;
    }

    private String relativeToRun(Path path) {
        return directory.getParent().relativize(path).toString().replace('\\', '/');
    }

    private void requireOpen() {
        if (complete) {
            throw new IllegalStateException("Scenario evidence is already complete");
        }
    }

    public enum Outcome {
        @SerializedName("Pass") PASS, @SerializedName("Fail") FAIL, @SerializedName("Not run") NOT_RUN,
        @SerializedName("Unreviewable") UNREVIEWABLE
    }

    public record Verdict(Outcome outcome, List<String> oracleObservations, List<String> mismatches,
        Map<String, Long> diagnosticCounts, String evidenceLimit) {
        public Verdict {
            Objects.requireNonNull(outcome, "outcome");
            oracleObservations = immutableStrings(oracleObservations, "oracleObservations");
            mismatches = immutableStrings(mismatches, "mismatches");
            Objects.requireNonNull(diagnosticCounts, "diagnosticCounts");
            var counts = new TreeMap<String, Long>();
            diagnosticCounts.forEach((name, count) -> {
                if (name == null || name.isBlank() || count == null || count < 0) {
                    throw new IllegalArgumentException("diagnosticCounts must contain non-negative named counts");
                }
                counts.put(name, count);
            });
            diagnosticCounts = Collections.unmodifiableMap(counts);
            if (evidenceLimit == null || evidenceLimit.isBlank()) {
                throw new IllegalArgumentException("evidenceLimit must not be blank");
            }
        }

        private static List<String> immutableStrings(List<String> values, String name) {
            Objects.requireNonNull(values, name);
            for (var value : values) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(name + " must contain non-blank values");
                }
            }
            return List.copyOf(values);
        }
    }

    private record Result(EvidenceRun.ScenarioIdentity identity, Outcome verdict, List<String> oracleObservations,
        List<String> mismatches, Map<String, Long> diagnosticCounts, List<String> failureArtifacts,
        String evidenceLimit) {
    }
}
