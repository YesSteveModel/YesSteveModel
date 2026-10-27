package com.elfmcys.ysm.mock.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class JsonlEvidenceSink {
    private final Path path;
    private final String role;

    JsonlEvidenceSink(Path path, String role) {
        this.path = path.toAbsolutePath().normalize();
        this.role = EvidenceRun.requireSegment(role, "role");
    }

    public synchronized void append(Observation observation) throws IOException {
        Objects.requireNonNull(observation, "observation");
        if (!role.equals(observation.role())) {
            throw new IllegalArgumentException("Observation role does not match sink role");
        }
        Files.createDirectories(path.getParent());
        var line = EvidenceJson.canonicalBytes(observation);
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.APPEND)) {
            output.write(line);
            output.write('\n');
        }
    }

    Path path() {
        return path;
    }

    public record Observation(String scenarioId, String actionId, String role, String connectionId, String operationId,
        String eventKind, Map<String, String> payload) {
        public Observation {
            scenarioId = EvidenceRun.requireSegment(scenarioId, "scenarioId");
            actionId = requireText(actionId, "actionId");
            role = EvidenceRun.requireSegment(role, "role");
            connectionId = optionalText(connectionId, "connectionId");
            operationId = optionalText(operationId, "operationId");
            eventKind = requireText(eventKind, "eventKind");
            payload = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(payload, "payload")));
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }

        private static String optionalText(String value, String name) {
            if (value != null && value.isBlank()) {
                throw new IllegalArgumentException(name + " must be null or non-blank");
            }
            return value;
        }
    }
}
