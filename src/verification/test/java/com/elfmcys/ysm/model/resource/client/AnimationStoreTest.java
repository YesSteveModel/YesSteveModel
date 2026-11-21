package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.ChannelReadPhase;
import com.elfmcys.ysm.format.schema.file.FileChunkDataSource;
import com.elfmcys.ysm.format.schema.file.ReadPhaseSet;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AnimationStoreTest {
    @Test
    void anAnimationIndexCanBeTransferredToOnlyOneRoot() {
        var index = AnimationStore.Index.eager(Map.of("idle", animation("idle")));
        try (var owner =
            AnimationStore.lazyBound(List.of(), name -> null, index, ignored -> ModelResourceFailureGate.none())) {
            assertThrows(IllegalStateException.class, () -> AnimationStore.lazyBound(List.of(), name -> null, index,
                ignored -> ModelResourceFailureGate.none()));
            assertEquals("idle", owner.get("idle").name);
        }
    }

    @Test
    void rejectedFallbackTransferLeavesTheLocalIndexUnowned() {
        var local = AnimationStore.Index.eager(Map.of("walk", animation("walk")));
        var fallback = AnimationStore.Index.eager(Map.of("idle", animation("idle")));
        try (var fallbackOwner = AnimationStore.fromIndices(fallback, null)) {
            assertThrows(IllegalStateException.class, () -> AnimationStore.fromIndices(local, fallback));
            try (var localOwner = AnimationStore.fromIndices(local, null)) {
                assertEquals("walk", localOwner.get("walk").name);
                assertEquals("idle", fallbackOwner.get("idle").name);
            }
        }
    }

    @Test
    void publishedNamespacesProveAbsenceAndExposeImmutableNames() {
        try (var complete = AnimationStore.eager(Map.of())) {
            assertTrue(complete.resolve("missing").cacheable());
            assertNull(complete.resolve("missing").animation());
            assertThrows(UnsupportedOperationException.class, () -> complete.keySet().add("missing"));
            try (var local = AnimationStore.lazyBound(List.of(), name -> null, AnimationStore.Index.eager(Map.of()),
                ignored -> ModelResourceFailureGate.none())) {
                assertTrue(local.resolve("missing").cacheable());
            }
        }
    }

    @Test
    void failuresAndNullPayloadNeverBecomeAbsenceOrStableFallback() {
        for (var first : new Object[] {new IOException("unavailable"), new IllegalArgumentException("bad content"),
            "null payload"}) {
            var loads = new AtomicInteger();
            var replacement = animation("replacement");
            var expected = animation("verified");
            var fallback = AnimationStore.Index.eager(Map.of("a", replacement));
            try (var source = AnimationStore.lazyBound(List.of("a"), name -> {
                if (loads.incrementAndGet() == 1) {
                    if (first instanceof Exception error)
                        throw error;
                    return null;
                }
                return expected;
            }, fallback, ignored -> ModelResourceFailureGate.none()).shared()) {
                var failed = source.resolve("a");
                assertSame(replacement, failed.animation());
                assertTrue(failed.fallback());
                assertFalse(failed.cacheable());
                var retry = source.resolve("a");
                if (first instanceof IllegalArgumentException) {
                    assertFalse(retry.cacheable(), "Deterministic failures remain with the resource gate");
                    assertSame(replacement, retry.animation());
                    assertEquals(1, loads.get());
                } else {
                    assertTrue(retry.cacheable());
                    assertFalse(retry.fallback());
                    assertSame(expected, retry.animation());
                    assertEquals(2, loads.get());
                }
            }
        }
    }

    @Test
    void verifiedContentSurvivesUnreadableSourceAndFallbackOriginStaysContextLocal() {
        var readable = new java.util.concurrent.atomic.AtomicBoolean(true);
        var loads = new AtomicInteger();
        var expected = animation("a");
        try (var backing = AnimationStore.lazyBound(List.of("a"), name -> {
            loads.incrementAndGet();
            if (!readable.get())
                throw new IOException("removed");
            return expected;
        }, null, ignored -> ModelResourceFailureGate.none()).shared()) {
            var direct = backing.resolve("a");
            readable.set(false);
            try (var inherited = AnimationStore.lazyBound(List.of(), name -> null,
                AnimationStore.Index.eager(Map.of("a", expected)), ignored -> ModelResourceFailureGate.none())) {
                var fallback = inherited.resolve("a");
                assertSame(expected, fallback.animation());
                assertTrue(fallback.cacheable());
                assertTrue(fallback.fallback());
                assertFalse(backing.resolve("a").fallback());
                assertFalse(direct.fallback());
                assertEquals(1, loads.get());
            }
        }
    }

    @Test
    void closingBatchInsideTheChannelOpenerLetsTheAcceptedReadFinishAndClosesOnce(@TempDir Path root) throws Exception {
        var path = Files.write(root.resolve("input"), new byte[] {7});
        for (var failClose : List.of(false, true)) {
            var closes = new AtomicInteger();
            var batchRef = new AtomicReference<AnimationStore.ReadBatch>();
            var storeRef = new AtomicReference<AnimationStore>();
            var expected = animation("idle");
            var source = new AnimationStore.AnimationLoader() {
                @Override
                public Animation load(String name) {
                    throw new AssertionError("Expected a local phase");
                }

                @Override
                public AnimationStore.AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
                    var phase = phases.acquire(this, () -> ChannelReadPhase.open(() -> {
                        var physical = FileChannel.open(path);
                        batchRef.get().close();
                        storeRef.get().close();
                        assertFalse(storeRef.get().isClosed());
                        return (SeekableByteChannel)Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {SeekableByteChannel.class}, (proxy, method, args) -> {
                                try {
                                    var result = method.invoke(physical, args);
                                    if (method.getName().equals("close")) {
                                        closes.incrementAndGet();
                                        if (failClose)
                                            throw new IOException("failed after physical close");
                                    }
                                    return result == physical ? proxy : result;
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
                    }, channel -> {
                    }));
                    return name -> phase.read(channel -> {
                        var value = java.nio.ByteBuffer.allocate(1);
                        assertEquals(1, channel.read(value));
                        assertEquals(7, value.get(0));
                        return expected;
                    });
                }
            };
            try (
                var store =
                    AnimationStore.lazyBound(List.of("idle"), source, null, ignored -> ModelResourceFailureGate.none());
                var batch = store.openReadPhase()) {
                storeRef.set(store);
                batchRef.set(batch);
                if (failClose)
                    assertThrows(UncheckedIOException.class, () -> batch.animations().get("idle"));
                else
                    assertSame(expected, batch.animations().get("idle"));
                assertEquals(1, closes.get());
                assertEquals(0, store.activeUses());
                assertTrue(store.isClosed());
                assertThrows(IllegalStateException.class, () -> batch.animations().get("idle"));
                batch.close();
                store.close();
                assertEquals(1, closes.get());
            }
        }
    }

    @Test
    void closingAnEnteredReadStillAllowsItsFailureToOpenTheFallbackPhase() throws Exception {
        var batchRef = new AtomicReference<AnimationStore.ReadBatch>();
        var closes = new AtomicInteger();
        var source = new AnimationStore.AnimationLoader() {
            @Override
            public Animation load(String name) throws Exception {
                batchRef.get().close();
                throw new IOException("local payload");
            }

            @Override
            public AnimationStore.AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
                phases.acquire(this, () -> (AutoCloseable)closes::incrementAndGet);
                return this;
            }
        };
        var expected = animation("idle");
        var fallback = AnimationStore.Index.lazy(List.of("idle"), new AnimationStore.AnimationLoader() {
            @Override
            public Animation load(String name) {
                return expected;
            }

            @Override
            public AnimationStore.AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
                phases.acquire(this, () -> (AutoCloseable)closes::incrementAndGet);
                return this;
            }
        });
        try (
            var store =
                AnimationStore.lazyBound(List.of("idle"), source, fallback, ignored -> ModelResourceFailureGate.none());
            var batch = store.openReadPhase()) {
            batchRef.set(batch);
            var result = batch.animations().resolve("idle");
            assertSame(expected, result.animation());
            assertTrue(result.fallback());
            assertFalse(result.cacheable());
            assertEquals(2, closes.get());
            assertEquals(0, store.activeUses());
        }
    }

    @Test
    void physicalCloseFailureDuringAFileReadCannotBeRecoveredByFallback(@TempDir Path root) throws Exception {
        var path = Files.write(root.resolve("input"), new byte[] {1});
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var fallbackLoads = new AtomicInteger();
        var remembered = new AtomicReference<Throwable>();
        var chunks = new FileChunkDataSource(path, 1, () -> {
            var physical = FileChannel.open(path);
            opens.incrementAndGet();
            return (SeekableByteChannel)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {SeekableByteChannel.class}, (proxy, method, args) -> {
                    try {
                        var result = method.invoke(physical, args);
                        if (method.getName().equals("close")) {
                            closes.incrementAndGet();
                            throw new IOException("failed after physical close");
                        }
                        return result == physical ? proxy : result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        });
        var descriptor = new AssetContainerView.ChunkInfo("invalid-range", "", 0, 2, 0, 0, 0, 0, null);
        var source = new AnimationStore.AnimationLoader() {
            @Override
            public Animation load(String name) {
                throw new AssertionError("Expected a local phase");
            }

            @Override
            public AnimationStore.AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
                var phase = phases.acquire(chunks, chunks::openReadPhase);
                return name -> {
                    {
                        var ignored = phase.readPayload(descriptor, BufferType.ARRAY);
                        return animation(name);
                    }
                };
            }
        };
        var fallback = AnimationStore.Index.lazy(List.of("idle"), name -> {
            fallbackLoads.incrementAndGet();
            return animation(name);
        });
        try (
            var store =
                AnimationStore.lazyBound(List.of("idle", "other"), source, fallback, ignored -> gate(remembered));
            var batch = store.openReadPhase()) {
            var failure = assertThrows(UncheckedIOException.class, () -> batch.animations().get("idle"));
            assertInstanceOf(AssetLoadException.class, failure.getCause());
            assertInstanceOf(ChannelReadPhase.CloseFailure.class, failure.getCause().getCause());
            assertEquals(1, opens.get());
            assertEquals(1, closes.get());
            assertEquals(0, fallbackLoads.get());
            assertNull(remembered.get());
            assertThrows(UncheckedIOException.class, () -> batch.animations().get("other"));
            assertEquals(1, opens.get(), "Physical close failure seals the whole batch");
        }
        assertEquals(1, closes.get());
    }

    @Test
    void endingAFailedStageCannotHideItsCloseFailureBehindFallback() throws Exception {
        var closes = new AtomicInteger();
        var fallbackLoads = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var source = new AnimationStore.AnimationLoader() {
            @Override
            public Animation load(String name) throws Exception {
                throw new IOException("payload");
            }

            @Override
            public AnimationStore.AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
                phases.acquire(this, () -> (AutoCloseable)() -> {
                    closes.incrementAndGet();
                    throw new IOException("physical close");
                });
                return this;
            }
        };
        var fallback = AnimationStore.Index.lazy(List.of("idle"), name -> {
            fallbackLoads.incrementAndGet();
            return animation(name);
        });
        try (var store = AnimationStore.lazyBound(List.of("idle"), source, fallback, ignored -> gate(failure));
            var batch = store.openReadPhase()) {
            assertThrows(UncheckedIOException.class, () -> batch.animations().get("idle"));
            assertEquals(1, closes.get());
            assertEquals(0, fallbackLoads.get());
            assertNull(failure.get());
        }
        assertEquals(1, closes.get());
    }

    @Test
    void cancellationAndResourceFailureDoNotStartAFallbackStageOrPoisonTheName() throws Exception {
        for (var failure : new Throwable[] {new CancellationException("cancel"), new OutOfMemoryError("injected")}) {
            var closes = new AtomicInteger();
            var fallbackLoads = new AtomicInteger();
            var remembered = new AtomicReference<Throwable>();
            var source = new AnimationStore.AnimationLoader() {
                @Override
                public Animation load(String name) {
                    if (failure instanceof RuntimeException runtime)
                        throw runtime;
                    throw (Error)failure;
                }

                @Override
                public AnimationStore.AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
                    phases.acquire(this, () -> (AutoCloseable)closes::incrementAndGet);
                    return this;
                }
            };
            var fallback = AnimationStore.Index.lazy(List.of("idle"), name -> {
                fallbackLoads.incrementAndGet();
                return animation(name);
            });
            try (var store = AnimationStore.lazyBound(List.of("idle"), source, fallback, ignored -> gate(remembered))) {
                try (var batch = store.openReadPhase()) {
                    assertSame(failure, assertThrows(failure.getClass(), () -> batch.animations().get("idle")));
                    assertEquals(0, fallbackLoads.get());
                    assertNull(remembered.get());
                    assertEquals(AnimationStore.State.UNLOADED, store.state("idle"));
                }
                assertEquals(1, closes.get());
            }
        }
    }

    private static ModelResourceFailureGate gate(AtomicReference<Throwable> failure) {
        return new ModelResourceFailureGate() {
            @Override
            public Optional<Throwable> failure() {
                return Optional.ofNullable(failure.get());
            }

            @Override
            public void fail(Throwable cause) {
                failure.compareAndSet(null, cause);
            }
        };
    }

    @Test
    void marksOnlyResolvedFallbackAnimationsForLegacyHandLoops() {
        var local = animation("local");
        var inherited = animation("inherited");
        try (var store = AnimationStore.lazyBound(List.of("local"), ignored -> local,
            AnimationStore.Index.eager(Map.of("inherited", inherited)), ignored -> ModelResourceFailureGate.none())) {
            var consumer = store.view();
            assertSame(local, consumer.resolve("local").animation());
            assertFalse(consumer.resolve("local").fallback());
            assertSame(inherited, consumer.resolve("inherited").animation());
            assertTrue(consumer.resolve("inherited").fallback());
            try (var direct = AnimationStore.eager(Map.of("inherited", inherited))) {
                assertFalse(direct.resolve("inherited").fallback(), "Shared content has no mutable origin flag");
            }
        }
    }

    @Test
    void consumerViewsShareOneBindingWithoutIndependentCloseAuthority() {
        var binds = new AtomicInteger();
        var source = AnimationProto.newBuilder().setName("idle").setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).build();
        try (var backing = AnimationStore.lazy(List.of("idle"), ignored -> source, ignored -> {
            binds.incrementAndGet();
            return animation("idle");
        }).shared()) {
            var first = backing.view();
            var second = backing.view();
            assertFalse(first.isEmpty());
            assertEquals(1, first.size());
            assertEquals(1, first.keySet().size());
            var value = first.get("idle");
            assertSame(value, second.get("idle"));
            assertEquals(1, binds.get());
            assertThrows(UnsupportedOperationException.class, () -> first.keySet().add("other"));
            assertSame(value, second.get("idle"));
        }
    }

    @Test
    void accessFailureRemainsConsumerLocalWhileAnotherConsumerCanBind() {
        var loads = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var gate = new ModelResourceFailureGate() {
            @Override
            public Optional<Throwable> failure() {
                return Optional.ofNullable(failure.get());
            }

            @Override
            public void fail(Throwable cause) {
                failure.compareAndSet(null, cause);
            }
        };
        var source = AnimationProto.newBuilder().setName("idle").setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).build();
        try (var backing = AnimationStore.lazy(List.of("idle"), ignored -> {
            if (loads.incrementAndGet() == 1)
                throw new IOException("source access");
            return source;
        }, ignored -> animation("idle")).shared()) {
            var first = backing.view(ignored -> gate);
            var second = backing.view();
            assertNull(first.get("idle"));
            assertTrue(failure.get() instanceof IOException);
            var value = second.get("idle");
            assertEquals("idle", value.name);
            assertNull(first.get("idle"));
            assertEquals(2, loads.get());
        }
    }

    @Test
    void loadsAndBindsOnlyTheFirstRequestedAnimation() {
        var loads = new HashMap<String, Integer>();
        var binds = new HashMap<String, Integer>();
        var store = AnimationStore.lazy(List.of("walk", "idle"), name -> {
            loads.merge(name, 1, Integer::sum);
            return AnimationProto.newBuilder().setName(name).setLength(0)
                .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).build();
        }, source -> {
            binds.merge(source.name(), 1, Integer::sum);
            return animation(source.name());
        });

        assertEquals(AnimationStore.State.UNLOADED, store.state("walk"));
        assertEquals(AnimationStore.State.UNLOADED, store.state("idle"));
        assertFalse(store.hasFailures());

        var walk = store.get("walk");

        assertEquals("walk", walk.name);
        assertEquals(1, loads.get("walk"));
        assertEquals(1, binds.get("walk"));
        assertNull(loads.get("idle"));
        assertNull(binds.get("idle"));
        assertEquals(AnimationStore.State.READY, store.state("walk"));
        assertEquals(AnimationStore.State.UNLOADED, store.state("idle"));
        assertSame(walk, store.get("walk"));
        assertEquals(1, loads.get("walk"));
    }

    @Test
    void bindFailureIsIsolatedToOneAnimation() {
        var loads = new HashMap<String, Integer>();
        var ready = animation("ready");
        var store = AnimationStore.lazy(List.of("ready", "broken"), name -> {
            loads.merge(name, 1, Integer::sum);
            return AnimationProto.newBuilder().setName(name).setLength(0)
                .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).build();
        }, source -> {
            if (source.name().equals("broken")) {
                throw new IOException("bind failed");
            }
            return ready;
        });

        assertSame(ready, store.get("ready"));
        assertNull(store.get("broken"));
        assertEquals(AnimationStore.State.READY, store.state("ready"));
        assertEquals(AnimationStore.State.FAILED, store.state("broken"));
        assertTrue(store.hasFailures());
        assertSame(ready, store.get("ready"));
        assertNull(store.get("broken"));
        assertEquals(1, loads.get("ready"));
        assertEquals(1, loads.get("broken"));
    }

    @Test
    void fallsBackWhenLocalNameIsAbsentOrPayloadIsMissing() {
        var fallbackAnimation = animation("shared");
        var fallbackOnly = animation("fallback-only");
        var fallback = AnimationStore.Index.eager(Map.of("shared", fallbackAnimation, "fallback-only", fallbackOnly));
        var store = AnimationStore.lazy(List.of("shared"), name -> null, ignored -> animation("unused"), fallback);

        assertSame(fallbackAnimation, store.get("shared"));
        assertSame(fallbackOnly, store.get("fallback-only"));
    }

    @Test
    void bindsPresentLocalAnimationBeforeUsingFallback() {
        var fallbackAnimation = animation("shared");
        var localAnimation = animation("shared");
        var fallback = AnimationStore.Index.eager(Map.of("shared", fallbackAnimation));
        var store = AnimationStore.lazy(List.of("shared"),
            name -> AnimationProto.newBuilder().setName(name).setLength(0)
                .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).build(),
            ignored -> localAnimation, fallback);

        assertSame(localAnimation, store.get("shared"));
    }

    @Test
    void failedAnimationFallsBackAndDoesNotRetry() {
        var fallbackAnimation = animation("shared");
        var fallback = AnimationStore.Index.eager(Map.of("shared", fallbackAnimation));
        var loads = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var gate = new ModelResourceFailureGate() {
            @Override
            public Optional<Throwable> failure() {
                return Optional.ofNullable(failure.get());
            }

            @Override
            public void fail(Throwable cause) {
                failure.compareAndSet(null, cause);
            }
        };
        var store = AnimationStore.lazy(List.of("shared"), name -> {
            loads.incrementAndGet();
            throw new IOException("broken");
        }, ignored -> animation("unused"), fallback, ignored -> gate);

        assertSame(fallbackAnimation, store.get("shared"));
        assertSame(fallbackAnimation, store.get("shared"));
        assertEquals(1, loads.get());
        assertEquals(AnimationStore.State.FAILED, store.state("shared"));

        var reconstructed = AnimationStore.lazy(List.of("shared"), name -> {
            loads.incrementAndGet();
            return null;
        }, ignored -> animation("unused"), AnimationStore.Index.eager(Map.of("shared", fallbackAnimation)),
            ignored -> gate);
        assertSame(fallbackAnimation, reconstructed.get("shared"));
        assertEquals(1, loads.get());
    }

    private static Animation animation(String name) {
        return new Animation(name, 0, LoopType.PLAY_ONCE, null, List.of(), List.of(), List.of());
    }
}
