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
    void onlyCompleteNamespacesProveAbsenceAndPublishedNamesCannotChange() {
        try (var incomplete = new AnimationStore(); var complete = AnimationStore.eager(Map.of())) {
            assertFalse(incomplete.resolve("missing").cacheable());
            incomplete.put("a", animation("a"));
            assertFalse(incomplete.resolve("a").cacheable());
            assertTrue(complete.resolve("missing").cacheable());
            assertNull(complete.resolve("missing").animation());
            assertThrows(UnsupportedOperationException.class, () -> complete.put("missing", animation("a")));
            try (var local = AnimationStore.lazyBound(List.of(), name -> null, incomplete,
                ignored -> ModelResourceFailureGate.none())) {
                assertFalse(local.resolve("missing").cacheable(), "Fallback namespace is also required");
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
            try (var fallback = AnimationStore.eager(Map.of("a", replacement));
                var source = AnimationStore.lazyBound(List.of("a"), name -> {
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
        }, null, ignored -> ModelResourceFailureGate.none()).shared();
            var inherited = AnimationStore.lazyBound(List.of(), name -> null, backing,
                ignored -> ModelResourceFailureGate.none())) {
            var direct = backing.resolve("a");
            assertSame(expected, direct.animation());
            assertFalse(direct.fallback());
            readable.set(false);
            var fallback = inherited.resolve("a");
            assertSame(expected, fallback.animation());
            assertTrue(fallback.cacheable());
            assertTrue(fallback.fallback());
            assertFalse(backing.resolve("a").fallback());
            assertFalse(direct.fallback());
            assertEquals(1, loads.get());
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
        try (var fallback = AnimationStore.lazyBound(List.of("idle"), name -> {
            fallbackLoads.incrementAndGet();
            return animation(name);
        }, null, ignored -> ModelResourceFailureGate.none());
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
        try (var fallback = AnimationStore.lazyBound(List.of("idle"), name -> {
            fallbackLoads.incrementAndGet();
            return animation(name);
        }, null, ignored -> ModelResourceFailureGate.none());
            var store = AnimationStore.lazyBound(List.of("idle"), source, fallback, ignored -> gate(failure));
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
            try (var fallback = AnimationStore.lazyBound(List.of("idle"), name -> {
                fallbackLoads.incrementAndGet();
                return animation(name);
            }, null, ignored -> ModelResourceFailureGate.none());
                var store = AnimationStore.lazyBound(List.of("idle"), source, fallback, ignored -> gate(remembered))) {
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
        try (var defaults = AnimationStore.eager(Map.of("inherited", inherited));
            var store = AnimationStore.lazyBound(List.of("local"), ignored -> local, defaults,
                ignored -> ModelResourceFailureGate.none());
            var consumer = AnimationStore.consumerView(store, ignored -> ModelResourceFailureGate.none())) {
            assertSame(local, consumer.resolve("local").animation());
            assertFalse(consumer.resolve("local").fallback());
            assertSame(inherited, consumer.resolve("inherited").animation());
            assertTrue(consumer.resolve("inherited").fallback());
            assertFalse(defaults.resolve("inherited").fallback(), "Shared content has no mutable origin flag");
        }
    }

    @Test
    void consumerViewsShareOneBindingAndCloseWithoutClosingTheirDependency() {
        var binds = new AtomicInteger();
        var source = AnimationProto.newBuilder().setName("idle").setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).build();
        try (var backing = AnimationStore.lazy(List.of("idle"), ignored -> source, ignored -> {
            binds.incrementAndGet();
            return animation("idle");
        }).shared(); var first = AnimationStore.consumerView(backing, ignored -> ModelResourceFailureGate.none());
            var second = AnimationStore.consumerView(backing, ignored -> ModelResourceFailureGate.none())) {
            assertFalse(first.isEmpty());
            assertEquals(1, first.size());
            assertEquals(1, first.entrySet().size());
            var value = first.get("idle");
            assertSame(value, second.get("idle"));
            assertEquals(1, binds.get());
            assertThrows(UnsupportedOperationException.class, () -> first.put("idle", animation("other")));
            first.close();
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
        }, ignored -> animation("idle")).shared(); var first = AnimationStore.consumerView(backing, ignored -> gate);
            var second = AnimationStore.consumerView(backing, ignored -> ModelResourceFailureGate.none())) {
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
        var fallback = AnimationStore.eager(Map.of("shared", fallbackAnimation, "fallback-only", fallbackOnly));
        var store = AnimationStore.lazy(List.of("shared"), name -> null, ignored -> animation("unused"), fallback);

        assertSame(fallbackAnimation, store.get("shared"));
        assertSame(fallbackOnly, store.get("fallback-only"));
    }

    @Test
    void bindsPresentLocalAnimationBeforeUsingFallback() {
        var fallbackAnimation = animation("shared");
        var localAnimation = animation("shared");
        var fallback = AnimationStore.eager(Map.of("shared", fallbackAnimation));
        var store = AnimationStore.lazy(List.of("shared"),
            name -> AnimationProto.newBuilder().setName(name).setLength(0)
                .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).build(),
            ignored -> localAnimation, fallback);

        assertSame(localAnimation, store.get("shared"));
    }

    @Test
    void failedAnimationFallsBackAndDoesNotRetry() {
        var fallbackAnimation = animation("shared");
        var fallback = AnimationStore.eager(Map.of("shared", fallbackAnimation));
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
        }, ignored -> animation("unused"), fallback, ignored -> gate);
        assertSame(fallbackAnimation, reconstructed.get("shared"));
        assertEquals(1, loads.get());
    }

    private static Animation animation(String name) {
        return new Animation(name, 0, LoopType.PLAY_ONCE, null, List.of(), List.of(), List.of());
    }
}
