package com.elfmcys.ysm.mock.classpath;

import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Append-only endpoint observations stored outside the empty process working directory. */
public final class EndpointEvidence {
    private final String scenarioId;
    private final String role;
    private final Path events;

    public EndpointEvidence(String scenarioId, String role, Path events) {
        this.scenarioId = Objects.requireNonNull(scenarioId, "scenarioId");
        this.role = Objects.requireNonNull(role, "role");
        this.events = events.toAbsolutePath().normalize();
    }

    public synchronized void append(String actionId, String operationId, String eventKind, Map<String, String> payload)
        throws IOException {
        append("classpath-smoke-connection", actionId, operationId, eventKind, payload);
    }

    public synchronized void append(String connectionId, String actionId, String operationId, String eventKind,
        Map<String, String> payload) throws IOException {
        var observation = new JsonlEvidenceSink.Observation(scenarioId, actionId, role, connectionId, operationId,
            eventKind, new TreeMap<>(payload));
        Files.createDirectories(events.getParent());
        Files.write(events, EvidenceJson.canonicalBytes(observation), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        Files.write(events, new byte[] {'\n'}, StandardOpenOption.APPEND);
    }

    public static void writeNew(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        Files.write(path, EvidenceJson.canonicalBytes(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public static boolean workingDirectoryEmpty() throws IOException {
        try (var entries = Files.list(Path.of("").toAbsolutePath().normalize())) {
            return entries.findAny().isEmpty();
        }
    }
}
