package com.elfmcys.ysm.mock.supervisor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessSupervisor {
    private ProcessSupervisor() {}

    public static Process launchJava(Path javaExecutable, List<String> jvmArguments, Path classpathJar,
        String mainClass, List<String> arguments, Path workingDirectory, Map<String, String> environment, Path output)
        throws IOException {
        var command = new ArrayList<String>();
        command.add(javaExecutable.toAbsolutePath().normalize().toString());
        command.add("-Djava.awt.headless=true");
        command.addAll(jvmArguments);
        command.add("-cp");
        command.add(classpathJar.toAbsolutePath().normalize().toString());
        command.add(mainClass);
        command.addAll(arguments);
        Files.createDirectories(workingDirectory);
        Files.createDirectories(output.getParent());
        var builder = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true)
            .redirectOutput(output.toFile());
        builder.environment().putAll(environment);
        return builder.start();
    }

    public static int awaitExit(Process process, Duration timeout) throws IOException, InterruptedException {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IOException("Timed out waiting for process " + process.pid());
        }
        return process.exitValue();
    }

    public static Process launchModDev(Path script, Path gameDirectory, Map<String, String> environment, Path output)
        throws IOException {
        return launchModDev(script, gameDirectory, environment, output, List.of());
    }

    public static Process launchModDev(Path script, Path gameDirectory, Map<String, String> environment, Path output,
        List<String> jvmArguments) throws IOException {
        var parsed = parseModDevScript(script);
        var command = new ArrayList<>(parsed.command().stream().map(value -> value.replace("%%%%", "%%")).toList());
        command.addAll(1, jvmArguments);
        Files.createDirectories(gameDirectory);
        Files.createDirectories(output.getParent());
        var builder = new ProcessBuilder(command).directory(gameDirectory.toFile()).redirectErrorStream(true)
            .redirectOutput(output.toFile());
        parsed.environment().forEach((key, value) -> builder.environment().put(key, value.replace("%%%%", "%%")));
        builder.environment().putAll(environment);
        return builder.start();
    }

    static ParsedScript parseModDevScript(Path script) throws IOException {
        var environment = new LinkedHashMap<String, String>();
        List<String> command = null;
        for (var raw : Files.readAllLines(script, StandardCharsets.UTF_8)) {
            var line = raw.strip();
            if (line.regionMatches(true, 0, "set ", 0, 4)) {
                var assignment = line.substring(4);
                var separator = assignment.indexOf('=');
                if (separator > 0) {
                    environment.put(assignment.substring(0, separator), assignment.substring(separator + 1));
                }
            } else if (line.startsWith("\"") && line.contains("java")) {
                command = splitWindowsCommand(line);
            }
        }
        if (command == null || command.isEmpty()) {
            throw new IOException("No Java command in ModDevGradle script: " + script);
        }
        return new ParsedScript(Map.copyOf(environment), List.copyOf(command));
    }

    static List<String> splitWindowsCommand(String line) {
        var result = new ArrayList<String>();
        var token = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < line.length(); index++) {
            var character = line.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!token.isEmpty()) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quote in command: " + line);
        }
        if (!token.isEmpty()) {
            result.add(token.toString());
        }
        return result;
    }

    public static void awaitFile(Path file, Process process, Duration timeout)
        throws IOException, InterruptedException {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(file)) {
                return;
            }
            if (process != null && !process.isAlive()) {
                throw new IOException(
                    "Process " + process.pid() + " exited with " + process.exitValue() + " before " + file);
            }
            Thread.sleep(50);
        }
        throw new IOException("Timed out waiting for " + file);
    }

    public static CleanupResult terminateExact(Process process, Duration grace) throws InterruptedException {
        if (process == null) {
            return new CleanupResult(List.of(), List.of(), false);
        }
        var handles = new ArrayList<>(process.toHandle().descendants().toList());
        handles.sort(Comparator.comparingInt(ProcessSupervisor::depth).reversed());
        handles.add(process.toHandle());
        var targeted = handles.stream().map(ProcessHandle::pid).toList();
        handles.forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroy();
            }
        });
        var deadline = System.nanoTime() + grace.toNanos();
        for (var handle : handles) {
            var remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                } catch (ExecutionException | TimeoutException ignored) {
                    // A forced cleanup below is evidence of an infrastructure failure.
                }
            }
        }
        var forced = false;
        for (var handle : handles) {
            if (handle.isAlive()) {
                forced = true;
                handle.destroyForcibly();
            }
        }
        for (var handle : handles) {
            if (handle.isAlive()) {
                try {
                    handle.onExit().get(10, TimeUnit.SECONDS);
                } catch (ExecutionException | TimeoutException ignored) {
                    // Reported through remaining below.
                }
            }
        }
        var remaining = handles.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
        return new CleanupResult(targeted, remaining, forced);
    }

    private static int depth(ProcessHandle handle) {
        var depth = 0;
        var parent = handle.parent();
        while (parent.isPresent() && depth < 128) {
            depth++;
            parent = parent.get().parent();
        }
        return depth;
    }

    public record ParsedScript(Map<String, String> environment, List<String> command) {
    }

    public record CleanupResult(List<Long> targeted, List<Long> remaining, boolean forced) {
        public boolean clean() {
            return remaining.isEmpty();
        }
    }
}
