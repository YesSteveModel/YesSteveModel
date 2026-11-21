package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.file.ChannelReadPhase;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.storage.NativeArtifactIndex;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CacheReadFailureTest {
    @Test
    void unavailableRootRemainsAnOrdinaryReadMiss(@TempDir Path temporary) throws Exception {
        var root = Files.write(temporary.resolve("cache"), new byte[] {7});
        try (var cache = new AtomicSharedCache(root)) {
            var store = new PreviewStore(root, cache);
            assertTrue(store.readEncoded(new Hash256(new byte[32])).isEmpty());
            assertArrayEquals(new byte[] {7}, Files.readAllBytes(root));
        }
    }

    @Test
    void terminalFailuresSurviveWrappingAndSuppressedClose() throws Exception {
        assertTrue(CacheReadFailure.recoverable(new IOException("disk unavailable")));
        for (var terminal : List.of(new InterruptedIOException(), new ClosedChannelException(),
            new ChannelReadPhase.CloseFailure(new IOException("close")))) {
            var content = AssetLoadException.content("bad content");
            content.addSuppressed(terminal);
            assertFalse(CacheReadFailure.recoverable(content));
            assertSame(content, assertThrows(IOException.class, () -> CacheReadFailure.miss("test", content)));
        }
        var cancelled = new CancellationException("cancelled");
        assertSame(cancelled,
            assertThrows(CancellationException.class, () -> CacheReadFailure.recoverable(new IOException(cancelled))));
        var oom = new OutOfMemoryError("test");
        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> CacheReadFailure.recoverable(new IOException(oom))));
        assertSame(oom, assertThrows(OutOfMemoryError.class,
            () -> CacheReadFailure.recoverable(new ChannelReadPhase.CloseFailure(oom))));
        assertSame(cancelled, assertThrows(CancellationException.class,
            () -> CacheReadFailure.recoverable(new ChannelReadPhase.CloseFailure(cancelled))));
        assertThrows(ClosedChannelException.class,
            () -> StorageIndex.require(StorageResult.refused(StorageResult.Failure.CLOSED)));
        assertThrows(CancellationException.class,
            () -> StorageIndex.require(StorageResult.refused(StorageResult.Failure.CANCELLED)));
        assertThrows(IllegalStateException.class,
            () -> StorageIndex.require(StorageResult.refused(StorageResult.Failure.INTERNAL)));
    }

    @Test
    @EnabledIfSystemProperty(named = "ysm.cacheReadProbe", matches = "true")
    void previewReadsOpenDirectlyAndPreserveTerminalFailures(@TempDir Path root) throws Exception {
        try (var cache = new AtomicSharedCache(root)) {
            var store = new PreviewStore(root, cache);
            var id = new Hash256(new byte[32]);
            Path target;
            try (var staging = cache.reserve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.PREVIEW)) {
                staging.file().writeBytes(new byte[] {1, 2, 3});
                target = staging.publish(file -> true, move -> {
                    move.run();
                    cache.catalogIndex().publish(NativeArtifactIndex.ExactKind.PREVIEW, id.toString(),
                        staging.location());
                });
            }
            var probe = Class.forName("com.elfmcys.ysm.benchmark.ReadHandleProbe");
            var inject = probe.getMethod("fail", Path.class, String.class);
            var queries = probe.getMethod("queries");
            for (var failure : List.of("", "missing", "access", "io", "interrupted", "closed", "cancelled", "oom",
                "defect", "close", "read-close")) {
                inject.invoke(null, target, failure);
                var before = ((List<?>)queries.invoke(null)).size();
                try {
                    switch (failure) {
                        case "" -> {
                            try (var bytes = store.readEncoded(id).orElseThrow()) {
                                assertEquals(3, bytes.size());
                            }
                        }
                        case "missing", "access", "io" -> assertTrue(store.readEncoded(id).isEmpty());
                        case "interrupted", "closed", "close", "read-close" ->
                            assertThrows(IOException.class, () -> store.load(id));
                        case "cancelled" -> assertThrows(CancellationException.class, () -> store.probe(id));
                        case "oom" -> assertThrows(OutOfMemoryError.class, () -> store.load(id));
                        case "defect" -> assertThrows(IllegalStateException.class, () -> store.load(id));
                    }
                    @SuppressWarnings("unchecked")
                    var events = (List<Map<String, Object>>)queries.invoke(null);
                    var stage = events.subList(before, events.size());
                    var targetEvents =
                        stage.stream().filter(event -> event.get("path").equals(target.toString())).toList();
                    assertEquals(List.of("open"), targetEvents.stream().map(event -> event.get("operation")).toList(),
                        failure);
                    var directoryQueries =
                        stage.stream().filter(event -> event.get("operation").equals("readAttributes"))
                            .map(event -> event.get("path")).toList();
                    assertEquals(directoryQueries.size(), new java.util.HashSet<>(directoryQueries).size(), failure);
                } finally {
                    inject.invoke(null, target, null);
                }
            }
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target));
        }
    }
}
