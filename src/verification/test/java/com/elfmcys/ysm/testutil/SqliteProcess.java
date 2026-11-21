package com.elfmcys.ysm.testutil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** JNI and JDBC embed separate SQLite copies, which cannot coordinate POSIX locks within one process. */
public final class SqliteProcess implements AutoCloseable {
    private final Process process;
    private final BufferedReader output;

    public SqliteProcess(Path database) throws IOException {
        var arguments = Files.createTempFile(database.getParent(), "jdbc-", ".args");
        Files.write(arguments,
            List.of("-cp", System.getProperty("java.class.path"), SqliteProcess.class.getName(),
                database.toAbsolutePath().toString()).stream()
                .map(value -> "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\"").toList());
        process =
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        output = process.inputReader(StandardCharsets.UTF_8);
        try {
            await("READY");
        } catch (IOException failure) {
            process.destroyForcibly();
            throw failure;
        } finally {
            Files.deleteIfExists(arguments);
        }
    }

    public void execute(String... statements) throws IOException {
        for (var statement : statements) {
            var command = Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8)) + "\n";
            process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            await("OK");
        }
    }

    public static void execute(Path database, String... statements) throws IOException {
        try (var jdbc = new SqliteProcess(database)) {
            jdbc.execute(statements);
        }
    }

    private void await(String expected) throws IOException {
        try {
            var response = CompletableFuture.supplyAsync(() -> {
                try {
                    String line;
                    while ((line = output.readLine()) != null)
                        if (expected.equals(line))
                            return line;
                    return null;
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }).get(10, TimeUnit.SECONDS);
            if (!expected.equals(response))
                throw new IOException("JDBC process expected " + expected + " but returned " + response);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("JDBC process wait interrupted", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            process.destroyForcibly();
            throw new IOException("JDBC process did not respond", failure);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("JDBC process did not exit");
            }
            if (process.exitValue() != 0)
                throw new IOException("JDBC process failed with exit code " + process.exitValue());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("JDBC process close interrupted", failure);
        } finally {
            output.close();
        }
    }

    public static void main(String[] arguments) throws Exception {
        try (var jdbc = DriverManager.getConnection("jdbc:sqlite:" + arguments[0]);
            var statement = jdbc.createStatement();
            var input = new BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("READY");
            System.out.flush();
            String line;
            while ((line = input.readLine()) != null) {
                statement.execute(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
                System.out.println("OK");
                System.out.flush();
            }
        }
    }
}
