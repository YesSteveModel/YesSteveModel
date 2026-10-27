package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.util.DirectoryAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Holds the real prune gate in a separate JVM, including on Windows filesystems. */
public final class ConvertedGateProcess implements AutoCloseable {
    private final Process process;

    public ConvertedGateProcess(Path cacheRoot) throws Exception {
        var gate = cacheRoot.resolve("locks/converted-consumers/admission.lck");
        Files.createDirectories(gate.getParent());
        var classpath = String.join(java.io.File.pathSeparator,
            Path.of(ConvertedGateProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(DirectoryAccess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            classpath, ConvertedGateProcess.class.getName(), gate.toString()).redirectErrorStream(true).start();
        try {
            var ready = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.inputReader().readLine();
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }).get(10, TimeUnit.SECONDS);
            if (!"LOCKED".equals(ready)) {
                throw new IllegalStateException("Prune gate process failed: " + ready);
            }
        } catch (Throwable failure) {
            process.destroyForcibly();
            throw failure;
        }
    }

    public static void main(String[] arguments) throws Exception {
        var gate = Path.of(arguments[0]);
        try (var directory = DirectoryAccess.open(gate.getParent());
            var channel = directory.file(gate.getFileName().toString(), true, true, false); var lock = channel.lock()) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
        }
    }

    @Override
    public void close() throws Exception {
        process.getOutputStream().close();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Prune gate process did not exit");
        }
    }
}
