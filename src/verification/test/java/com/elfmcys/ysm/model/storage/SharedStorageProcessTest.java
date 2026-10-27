package com.elfmcys.ysm.model.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SharedStorageProcessTest {
    @TempDir
    Path temp;

    @Test
    void concurrentCategoryUpdatesSurviveReopenAndDoNotCrossDataOrVersion() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        var go = temp.resolve("go");
        try (var first = child("index", cache, data, "v1", "custom", go.toString(), "custom", "11");
            var second = child("index", cache, data, "v1", "auth", go.toString(), "auth", "22")) {
            first.ready();
            second.ready();
            Files.writeString(go, "go");
            first.success();
            second.success();
        }
        var index = index(cache, data, "v1");
        assertEquals(Set.of("custom/same.mxc", "auth/same.mxc"), index.read().keySet());
        assertEquals(11, index.find("custom/same.mxc").orElseThrow().modelId().bytes()[0]);
        assertEquals(22, index.find("auth/same.mxc").orElseThrow().modelId().bytes()[0]);
        var original = Files.readAllBytes(index.path());
        try (
            var otherData =
                child("index", cache, temp.resolve("other-data"), "v1", "other-data", go.toString(), "custom", "33");
            var otherVersion = child("index", cache, data, "v2", "other-version", go.toString(), "custom", "44")) {
            otherData.ready();
            otherVersion.ready();
            otherData.success();
            otherVersion.success();
        }
        assertArrayEquals(original, Files.readAllBytes(index.path()));
        assertEquals(33,
            index(cache, temp.resolve("other-data"), "v1").read().get("custom/same.mxc").modelId().bytes()[0]);
        assertEquals(44, index(cache, data, "v2").read().get("custom/same.mxc").modelId().bytes()[0]);
    }

    @Test
    void activeProcessBlocksPruneAndItsCrashedRegistrationDoesNotBlockRecovery() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        var scope = ConvertedScope.of(data, cache, "v1");
        var object = scope.root().resolve("objects/model/fixture.mxc");
        Files.createDirectories(object.getParent());
        Files.writeString(object, "original-object");
        var otherScope = ConvertedScope.of(data, cache, "v2");
        var otherObject = otherScope.root().resolve("objects/model/fixture.mxc");
        Files.createDirectories(otherObject.getParent());
        Files.writeString(otherObject, "other-version-object");
        try (var peer = child("consumer", cache, data, "v1", "consumer", object.toString())) {
            peer.ready();
            try (var observer = new ConvertedCacheCoordinator(scope, new AtomicSharedCache(cache))) {
                observer.register();
                assertEquals(ConvertedCacheCoordinator.PruneResult.SKIPPED_ACTIVE_CONSUMER,
                    observer.pruneOnce(Set.of()));
                assertEquals("original-object", Files.readString(object));
            }
            peer.process().getOutputStream().write(1);
            peer.process().getOutputStream().flush();
            peer.success();
            assertEquals("original-object", Files.readString(temp.resolve("consumer.ready.read")));
        }
        try (var crashed = child("consumer", cache, data, "v1", "crashed-consumer")) {
            crashed.ready();
            crashed.kill();
        }
        try (var registrations = Files.list(scope.root().resolve("locks/converted-consumers"))) {
            assertTrue(registrations.anyMatch(path -> path.toString().endsWith(".consumer")));
        }
        try (var observer = new ConvertedCacheCoordinator(scope, new AtomicSharedCache(cache))) {
            observer.register();
            assertEquals(ConvertedCacheCoordinator.PruneResult.COMPLETED, observer.pruneOnce(Set.of()));
        }
        assertFalse(Files.exists(object));
        assertEquals("other-version-object", Files.readString(otherObject));
    }

    @Test
    void probingLocalPeerPreservesItsLockForOtherProcesses() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        var scope = ConvertedScope.of(data, cache, "v1");
        try (var peer = new ConvertedCacheCoordinator(scope, new AtomicSharedCache(cache))) {
            peer.register();
            try (var first = new ConvertedCacheCoordinator(scope, new AtomicSharedCache(cache))) {
                first.register();
                assertEquals(ConvertedCacheCoordinator.PruneResult.SKIPPED_ACTIVE_CONSUMER, first.pruneOnce(Set.of()));
            }
            try (var observer = child("prune", cache, data, "v1", "local-peer-observer")) {
                observer.ready();
                observer.success();
                assertEquals("SKIPPED_ACTIVE_CONSUMER", Files.readString(observer.readyFile()));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"write", "derived"})
    void interruptedWriterLeavesOldCompleteBytesAndNextProcessCanCommit(String mode) throws Exception {
        var cache = temp.resolve("cache");
        var target = cache.resolve("objects/same.bin");
        Files.createDirectories(target.getParent());
        var old = new byte[] {1, 3, 5, 7};
        var replacement = new byte[1024 * 1024];
        java.util.Arrays.fill(replacement, (byte)0x5a);
        Files.write(target, old);
        var input = Files.write(temp.resolve("payload.bin"), replacement);
        try (var writer =
            child(mode, cache, temp.resolve("data"), "v1", "writer", target.toString(), input.toString(), "hold")) {
            writer.ready();
            assertArrayEquals(old, Files.readAllBytes(target));
            writer.kill();
        }
        assertArrayEquals(old, Files.readAllBytes(target));
        try (var writer = child(mode, cache, temp.resolve("data"), "v1", "next-writer", target.toString(),
            input.toString(), "finish")) {
            writer.ready();
            writer.success();
        }
        assertArrayEquals(replacement, Files.readAllBytes(target));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"write", "derived"})
    void concurrentProcessesPublishOnlyACompleteContainer(String mode) throws Exception {
        var manifest = getClass().getResource("/assets/ysm/builtin/default/ysm.json");
        final Path fixture;
        try (var directory = new com.elfmcys.ysm.format.vfs.Directory(
            Path.of(java.util.Objects.requireNonNull(manifest).toURI()).getParent())) {
            fixture = com.elfmcys.ysm.format.parser.ModelParser.parseBuiltinDefault(directory,
                Files.createDirectories(temp.resolve("fixture")));
        }
        var expected = Files.readAllBytes(fixture);
        var cache = temp.resolve("cache");
        var target = cache.resolve("objects/shared.mxc");
        try (var first = child(mode, cache, temp.resolve("data"), "v1", "first-container", target.toString(),
            fixture.toString(), "hold")) {
            first.ready();
            assertFalse(Files.exists(target));
            try (var second = child(mode, cache, temp.resolve("data"), "v1", "second-container", target.toString(),
                fixture.toString(), "finish")) {
                first.process().getOutputStream().write(1);
                first.process().getOutputStream().flush();
                first.success();
                second.success();
            }
        }
        assertArrayEquals(expected, Files.readAllBytes(target));
        try (var source = ManagedContainer.verifyFile(fixture); var reopened = ManagedContainer.verifyFile(target)) {
            assertEquals(source.identity(), reopened.identity());
        }
    }

    private ConvertedSourceIndexStore index(Path cache, Path data, String version) {
        return new ConvertedSourceIndexStore(ConvertedScope.of(data, cache, version), new AtomicSharedCache(cache));
    }

    private Child child(String mode, Path cache, Path data, String version, String name, String... rest)
        throws Exception {
        var ready = temp.resolve(name + ".ready");
        var log = temp.resolve(name + ".log");
        var args = new ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-cp");
        args.add(classpath());
        args.add(SharedStorageProcess.class.getName());
        args.add(mode);
        args.add(cache.toString());
        args.add(data.toString());
        args.add(version);
        args.add(ready.toString());
        args.addAll(java.util.List.of(rest));
        var argfile = temp.resolve(name + ".args");
        Files.write(argfile, args.subList(1, args.size()).stream()
            .map(value -> "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\"").toList());
        var process = new ProcessBuilder(args.get(0), "@" + argfile).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        return new Child(process, ready, log);
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private record Child(Process process, Path readyFile, Path log) implements AutoCloseable {
        void ready() throws Exception {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(readyFile)) {
                if (!process.isAlive() || System.nanoTime() > deadline)
                    fail(Files.readString(log));
                Thread.sleep(2);
            }
        }

        void success() throws Exception {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "Child timed out: " + log);
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(log);
                } catch (Exception failure) {
                    return failure.toString();
                }
            });
        }

        void kill() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive())
                kill();
        }
    }
}
