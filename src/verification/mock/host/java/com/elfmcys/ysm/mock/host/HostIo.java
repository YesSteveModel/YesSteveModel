package com.elfmcys.ysm.mock.host;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class HostIo {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String role;
    private final Path root;
    private final Path actionFile;
    private final Path observations;
    private long sequence;
    private String completedAction;

    private HostIo(String role, Path root, Path actionFile) throws IOException {
        this.role = role;
        this.root = root;
        this.actionFile = actionFile;
        observations = root.resolve("observations.jsonl");
        Files.createDirectories(root.resolve("acks"));
    }

    static HostIo open() throws IOException {
        var role = requiredEnvironment("YSM_MOCK_ROLE");
        var root = Path.of(requiredEnvironment("YSM_MOCK_ROLE_DIR")).toAbsolutePath().normalize();
        var action = Path.of(requiredEnvironment("YSM_MOCK_ACTION_FILE")).toAbsolutePath().normalize();
        if (!action.startsWith(root)) {
            throw new IllegalArgumentException("Role action file must stay inside its role directory");
        }
        return new HostIo(role, root, action);
    }

    String role() {
        return role;
    }

    synchronized HostAction nextAction() throws IOException {
        if (!Files.isRegularFile(actionFile)) {
            return null;
        }
        var action = GSON.fromJson(Files.readString(actionFile, StandardCharsets.UTF_8), HostAction.class);
        return action == null || action.id().equals(completedAction) ? null : action;
    }

    synchronized void ready(Map<String, ?> facts) throws IOException {
        event("process-ready", null, facts);
        writeAtomically(root.resolve("ready.json"), json(Map.of("role", role, "ready", true, "facts", facts)));
    }

    synchronized void event(String kind, String actionId, Map<String, ?> facts) throws IOException {
        var observation = new LinkedHashMap<String, Object>();
        observation.put("sequence", ++sequence);
        observation.put("role", role);
        observation.put("actionId", actionId == null ? "lifecycle" : actionId);
        observation.put("eventKind", kind);
        observation.put("thread", Thread.currentThread().getName());
        observation.put("recordedAt", Instant.now().toString());
        observation.put("facts", Map.copyOf(facts));
        Files.writeString(observations, GSON.toJson(observation) + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    synchronized void complete(HostAction action, Map<String, ?> facts) throws IOException {
        event("action-completed", action.id(), facts);
        completedAction = action.id();
        writeAtomically(root.resolve("acks").resolve(action.id() + ".json"),
            json(Map.of("id", action.id(), "name", action.name(), "success", true, "facts", facts)));
    }

    synchronized void fail(HostAction action, Throwable failure) throws IOException {
        var message = describe(failure);
        event("action-failed", action.id(), Map.of("failure", message));
        completedAction = action.id();
        writeAtomically(root.resolve("acks").resolve(action.id() + ".json"),
            json(Map.of("id", action.id(), "name", action.name(), "success", false, "failure", message)));
    }

    private static String describe(Throwable failure) {
        var message = new StringBuilder();
        var current = failure;
        for (var depth = 0; current != null && depth < 8; depth++) {
            if (depth != 0) {
                message.append(" <- ");
            }
            message.append(current.getClass().getName()).append(": ")
                .append(Objects.toString(current.getMessage(), ""));
            current = current.getCause();
        }
        return message.toString();
    }

    private static byte[] json(Object value) {
        return (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        var moved = false;
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
