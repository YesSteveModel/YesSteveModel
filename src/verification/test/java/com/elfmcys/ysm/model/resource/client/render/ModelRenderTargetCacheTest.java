package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.CacheRegion;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.RenderTargetResources;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRenderTargetCacheTest {
    private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);

    @AfterEach
    void stopScheduler() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination();
    }

    private static final BakeProfile PROFILE = new BakeProfile("test");
    private static final Object OWNER = new Object();
    private static final AtomicInteger NEXT_CONTAINER_ID = new AtomicInteger(100);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closingLeaseAroundCacheShutdownDropsItsTargetWithoutAnotherTick(boolean releaseFirst) throws Exception {
        var id = hash(14);
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var target = target();
            var lease = cache.getOrStart(request(id), content(id), key(), (cancelled, dispatch) -> ready(target),
                current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertInstanceOf(AcquireResult.Ready.class, lease.poll());
            if (releaseFirst)
                lease.close();
            cache.close();
            lease.close();
            assertInstanceOf(AcquireResult.Ended.class, lease.poll());
            var stateField = lease.getClass().getDeclaredField("state");
            stateField.setAccessible(true);
            var state = stateField.get(lease);
            for (var name : List.of("cache", "flight", "interest", "ready")) {
                var field = state.getClass().getDeclaredField(name);
                field.setAccessible(true);
                assertNull(field.get(state), name);
            }
            var releases = ModelRenderTargetCache.class.getDeclaredField("releases");
            releases.setAccessible(true);
            assertTrue(((java.util.Queue<?>)releases.get(cache)).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sameContentRolesKeepSeparateFlightsReadyAndReceivingLeases(boolean intrinsicFirst) throws Exception {
        var content = content(hash(13));
        var ordinary = request(content.modelId());
        var intrinsic = new ResourceRequest(PublishedModelId.INTRINSIC_DEFAULT, "player", "default", PROFILE,
            ordinary.expressionBinding(), ModelResourcePurpose.FULL);
        var intrinsicKey = new RenderTargetKey("player", "default", PROFILE, intrinsic.expressionBinding(),
            intrinsic.purpose(), PublishedModelId.Role.INTRINSIC_DEFAULT);
        var first = intrinsicFirst ? intrinsic : ordinary;
        var second = intrinsicFirst ? ordinary : intrinsic;
        var firstKey = intrinsicFirst ? intrinsicKey : key();
        var secondKey = intrinsicFirst ? key() : intrinsicKey;
        var starts = new AtomicInteger();
        var firstTarget = target();
        var secondTarget = target();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var a = cache.getOrStart(first, content, firstKey, (cancelled, dispatch) -> {
                starts.incrementAndGet();
                return ready(firstTarget);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            var b = cache.getOrStart(second, content, secondKey, (cancelled, dispatch) -> {
                starts.incrementAndGet();
                return ready(secondTarget);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertEquals(2, starts.get());
            assertTrue(hasReady(cache, first, content, firstKey, current()));
            assertTrue(hasReady(cache, second, content, secondKey, current()));
            assertSame(firstTarget, assertInstanceOf(AcquireResult.Ready.class, a.poll()).target());
            assertSame(secondTarget, assertInstanceOf(AcquireResult.Ready.class, b.poll()).target());
            assertFalse(a.isCurrent(second));
            assertFalse(b.isCurrent(first));
            assertThrows(IllegalArgumentException.class,
                () -> cache.findReady(ordinary, content, intrinsicKey, current()));
            a.close();
            cache.tick();
            assertInstanceOf(AcquireResult.Ready.class, b.poll());
            b.close();
        }
    }

    @BeforeAll
    static void establishRenderOwner() {
        ClientConfig.init().setConfig(CommentedConfig.inMemory());
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 30, 17})
    void retentionCountsTargetsAndPreservesIndependentActiveAndRequiredLeases(int count) throws Exception {
        var limit = ClientConfig.MODEL_RETENTION_COUNT;
        var original = limit.get();
        limit.set(count);
        var closes = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> closes.incrementAndGet(), failure -> {
            throw new AssertionError(failure);
        })) {
            var activeId = hash(90);
            var activeContent = content(activeId);
            var activeTarget = target();
            var active = cache.getOrStart(request(activeId), activeContent, key(),
                (ignored, dispatch) -> ready(activeTarget), current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            var shared = cache.findReady(request(activeId), activeContent, key(), current()).orElseThrow();
            var requiredId = hash(91);
            var requiredContent = content(requiredId);
            var requiredTarget = target();
            var required = cache.getOrStartRequiredAsync(request(requiredId), requiredContent, key(),
                (ignored, dispatch) -> CompletableFuture.completedFuture(readyResult(requiredTarget)), current(), OWNER,
                Runnable::run);
            var contents = new java.util.ArrayList<ModelContent>();
            for (int index = 0; index <= count; ++index) {
                var id = hash(200 + index);
                var content = content(id);
                contents.add(content);
                var target = target();
                var lease = cache.getOrStart(request(id), content, key(), (ignored, dispatch) -> ready(target),
                    current(), OWNER, WorkPriority.BACKGROUND);
                cache.tick();
                lease.close();
                cache.tick();
            }
            assertTrue(cache.findReady(request(hash(200)), contents.get(0), key(), current()).isEmpty());
            assertTrue(hasReady(cache, request(activeId), activeContent, key(), current()));
            active.close();
            cache.tick();
            assertTrue(hasReady(cache, request(activeId), activeContent, key(), current()));
            assertSame(activeTarget, assertInstanceOf(AcquireResult.Ready.class, shared.poll()).target());
            if (count > 0) {
                var id = hash(200 + count);
                try (var reused = cache.findReady(request(id), contents.get(count), key(), current()).orElseThrow()) {
                    assertInstanceOf(AcquireResult.Ready.class, reused.poll());
                }
                cache.tick();
            }
            limit.set(0);
            cache.tick();
            assertTrue(cache.findReady(request(hash(200 + count)), contents.get(count), key(), current()).isEmpty());
            assertTrue(hasReady(cache, request(activeId), activeContent, key(), current()));
            assertInstanceOf(AcquireResult.Ready.class, required.poll());
            shared.close();
            cache.tick();
            assertTrue(cache.findReady(request(activeId), activeContent, key(), current()).isEmpty());
            assertInstanceOf(AcquireResult.Ready.class, required.poll());
            required.close();
            cache.tick();
            assertTrue(cache.findReady(request(requiredId), requiredContent, key(), current()).isEmpty());
            assertEquals(0, closes.get());
        } finally {
            limit.set(original);
        }
    }

    @Test
    void configuredLimitEvictsOldestUnusedTargetsAndPreservesActiveTargets() throws Exception {
        var limit = ClientConfig.MODEL_RETENTION_COUNT;
        var original = limit.get();
        limit.set(2);
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var firstContent = content(hash(81));
            var secondContent = content(hash(82));
            var activeContent = content(hash(83));
            var firstTarget = target();
            var secondTarget = target();
            var activeTarget = target();
            var first = cache.getOrStart(request(hash(81)), firstContent, key(),
                (ignored, dispatch) -> ready(firstTarget), current(), OWNER, WorkPriority.BACKGROUND);
            var second = cache.getOrStart(request(hash(82)), secondContent, key(),
                (ignored, dispatch) -> ready(secondTarget), current(), OWNER, WorkPriority.BACKGROUND);
            var active = cache.getOrStart(request(hash(83)), activeContent, key(),
                (ignored, dispatch) -> ready(activeTarget), current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            first.close();
            second.close();
            cache.tick();
            assertTrue(hasReady(cache, request(hash(81)), firstContent, key(), current()));
            assertTrue(hasReady(cache, request(hash(82)), secondContent, key(), current()));
            assertTrue(hasReady(cache, request(hash(83)), activeContent, key(), current()));

            limit.set(1);
            cache.tick();
            assertTrue(cache.findReady(request(hash(81)), firstContent, key(), current()).isEmpty());
            try (var reused = cache.findReady(request(hash(82)), secondContent, key(), current()).orElseThrow()) {
                assertInstanceOf(AcquireResult.Ready.class, reused.poll());
            }

            limit.set(0);
            cache.tick();
            assertTrue(cache.findReady(request(hash(82)), secondContent, key(), current()).isEmpty());
            assertTrue(hasReady(cache, request(hash(83)), activeContent, key(), current()));
            assertInstanceOf(AcquireResult.Ready.class, active.poll());
            active.close();
            cache.tick();
            assertTrue(cache.findReady(request(hash(83)), activeContent, key(), current()).isEmpty());
        } finally {
            limit.set(original);
        }
    }

    @Test
    void oneFrozenPlanSharesPreparationAcrossConsumers() throws Exception {
        var modelId = hash(70);
        var content = content(modelId);
        var host = ExpressionHost.create(HostAvailability.none());
        var dynamic = host.expressionBinding();
        var exact = host.expressionBinding();
        var dynamicRequest = new ResourceRequest(modelId, "player", "default", PROFILE, dynamic);
        var exactRequest = new ResourceRequest(modelId, "player", "default", PROFILE, exact);
        var dynamicKey = new RenderTargetKey("player", "default", PROFILE, dynamic);
        var exactKey = new RenderTargetKey("player", "default", PROFILE, exact);
        var loads = new AtomicInteger();
        var dynamicTarget = target();
        var exactTarget = target();

        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var dynamicLease = cache.getOrStart(dynamicRequest, content, dynamicKey, (ignored, dispatch) -> {
                loads.incrementAndGet();
                return ready(dynamicTarget);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            var exactLease = cache.getOrStart(exactRequest, content, exactKey, (ignored, dispatch) -> {
                loads.incrementAndGet();
                return ready(exactTarget);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();

            assertEquals(1, loads.get());
            assertTrue(hasReady(cache, exactRequest, content, exactKey, current()));
            assertSame(dynamicTarget, assertInstanceOf(AcquireResult.Ready.class, dynamicLease.poll()).target());
            assertSame(dynamicTarget, assertInstanceOf(AcquireResult.Ready.class, exactLease.poll()).target());
            dynamicLease.close();
            exactLease.close();
        }
    }

    @Test
    void guiAndFullTargetsKeepSeparateFlightsReadyEntriesAndLeaseEligibility() throws Exception {
        var modelId = hash(71);
        var content = content(modelId);
        var binding = ExpressionHost.create(HostAvailability.none()).expressionBinding();
        var full = new ResourceRequest(modelId, "player", "default", PROFILE, binding, ModelResourcePurpose.FULL);
        var gui = new ResourceRequest(modelId, "player", "default", PROFILE, binding, ModelResourcePurpose.GUI);
        var fullKey = new RenderTargetKey("player", "default", PROFILE, binding, ModelResourcePurpose.FULL);
        var guiKey = new RenderTargetKey("player", "default", PROFILE, binding, ModelResourcePurpose.GUI);
        var fullTarget = target();
        var guiTarget = target();
        var loads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var fullLease = cache.getOrStart(full, content, fullKey, (ignored, dispatch) -> {
                loads.incrementAndGet();
                return ready(fullTarget);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            var guiLease = cache.getOrStart(gui, content, guiKey, (ignored, dispatch) -> {
                loads.incrementAndGet();
                return ready(guiTarget);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            assertEquals(ModelResourcePurpose.FULL, fullLease.purpose());
            assertEquals(ModelResourcePurpose.GUI, guiLease.purpose());
            cache.tick();

            assertEquals(2, loads.get());
            assertTrue(hasReady(cache, full, content, fullKey, current()));
            assertTrue(hasReady(cache, gui, content, guiKey, current()));
            assertSame(fullTarget, assertInstanceOf(AcquireResult.Ready.class, fullLease.poll()).target());
            assertSame(guiTarget, assertInstanceOf(AcquireResult.Ready.class, guiLease.poll()).target());
            assertFalse(fullLease.isCurrent(gui));
            assertFalse(guiLease.isCurrent(full));
            assertEquals(ModelResourcePurpose.FULL, fullLease.purpose());
            assertEquals(ModelResourcePurpose.GUI, guiLease.purpose());
            fullLease.close();
            guiLease.close();
        }
    }

    @Test
    void multiConsumerReadyReuseAndEvictionOnlyDropCacheReachability() throws Exception {
        var hash = hash(1);
        var content = content(hash);
        var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var firstTarget = target();
        var replacementTarget = target();
        var loads = new AtomicInteger();
        var closes = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> closes.incrementAndGet(), ignored -> {
        }, ignored -> {
            throw new AssertionError("Testing candidates bypass the host");
        }, () -> 4, () -> 0)) {
            ModelRenderTargetCache.Loader loader = (ignored, dispatch) -> {
                loads.incrementAndGet();
                return pending;
            };
            var first =
                cache.getOrStart(request(hash), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);
            var second =
                cache.getOrStart(request(hash), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);

            assertNotSame(first, second);
            assertEquals(1, loads.get());
            pending.complete(readyResult(firstTarget));
            cache.tick();
            assertSame(firstTarget, assertInstanceOf(AcquireResult.Ready.class, first.poll()).target());
            assertSame(firstTarget, assertInstanceOf(AcquireResult.Ready.class, second.poll()).target());
            assertTrue(hasReady(cache, request(hash), content, key(), current()));

            cache.tick();
            assertTrue(hasReady(cache, request(hash), content, key(), current()));
            first.close();
            second.close();
            cache.tick();
            assertTrue(cache.findReady(request(hash), content, key(), current()).isEmpty());
            assertEquals(0, closes.get());

            var replacement = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return ready(replacementTarget);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertEquals(2, loads.get());
            assertSame(replacementTarget, assertInstanceOf(AcquireResult.Ready.class, replacement.poll()).target());
            replacement.close();
        }
        assertEquals(0, closes.get());
    }

    @Test
    void retainedCancellationProbeDoesNotRetainEvictedReadyTarget() throws Exception {
        var probe = new AtomicReference<BooleanSupplier>();
        var collected = new ReferenceQueue<ModelRenderTarget>();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
            throw new AssertionError("Eviction must not close a shared target");
        }, ignored -> {
            throw new AssertionError("The loader must complete successfully");
        }, ignored -> {
            throw new AssertionError("Testing candidates bypass the host");
        }, () -> 4, () -> 0)) {
            var target = publishAndEvict(cache, probe, collected);
            assertFalse(probe.get().getAsBoolean());
            Reference<? extends ModelRenderTarget> released = null;
            for (var attempt = 0; attempt < 50 && released == null; attempt++) {
                System.gc();
                released = collected.remove(100);
            }
            assertSame(target, released, "A retained read probe must not retain the completed target");
            assertFalse(probe.get().getAsBoolean());
            Reference.reachabilityFence(probe);
        }
    }

    private static WeakReference<ModelRenderTarget> publishAndEvict(ModelRenderTargetCache cache,
        AtomicReference<BooleanSupplier> probe, ReferenceQueue<ModelRenderTarget> collected) throws Exception {
        var target = target();
        var reference = new WeakReference<>(target, collected);
        var hash = hash(84);
        var content = content(hash);
        try (var lease = cache.getOrStart(request(hash), content, key(), (cancelled, dispatch) -> {
            probe.set(cancelled);
            return ready(target);
        }, current(), OWNER, WorkPriority.BACKGROUND)) {
            cache.tick();
            assertSame(target, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
        }
        cache.tick();
        assertTrue(cache.findReady(request(hash), content, key(), current()).isEmpty());
        assertEquals(0, cache.loadingCount());
        return reference;
    }

    @Test
    void failedFlightLosesEligibilityBeforeSameKeyRetry() throws Exception {
        var hash = hash(2);
        var content = content(hash);
        var loads = new AtomicInteger();
        var loaded = target();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var first = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return failed(ResourceFailure.Kind.DETERMINISTIC, new IOException("broken"));
            }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertEquals(ResourceFailure.Kind.DETERMINISTIC,
                assertInstanceOf(AcquireResult.Failed.class, first.poll()).failure().kind());
            assertEquals(0, cache.loadingCount());

            var retry = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return ready(loaded);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertEquals(2, loads.get());
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, retry.poll()).target());
        }
    }

    @Test
    void preAdmissionCollapseHasNoTransportEffect() {
        var hash = hash(3);
        var content = content(hash);
        var flight = new AtomicReference<BooleanSupplier>();
        var transportCancels = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStart(request(hash), content, key(), (context, dispatch) -> {
                flight.set(context);
                return new CompletableFuture<>();
            }, current(), OWNER, WorkPriority.BACKGROUND);

            lease.cancelPending();
            cache.tick();
            assertTrue(flight.get().getAsBoolean());
            assertEquals(0, transportCancels.get());
            assertEquals(0, cache.loadingCount());
        }
    }

    @Test
    void postAdmissionLastInterestCancelsExactTransferOnce() {
        var hash = hash(4);
        var content = content(hash);
        var pending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var reports = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        }, ignored -> reports.incrementAndGet())) {
            ModelRenderTargetCache.Loader loader = (context, dispatch) -> {
                return pending;
            };
            var first =
                cache.getOrStart(request(hash), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);
            var second =
                cache.getOrStart(request(hash), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);

            first.cancelPending();
            cache.tick();
            assertEquals(0, pending.cancelCalls.get());
            assertEquals(1, cache.loadingCount());
            second.cancelPending();
            second.cancelPending();
            cache.tick();
            assertEquals(1, pending.cancelCalls.get());
            assertEquals(0, cache.loadingCount());
            assertEquals(0, reports.get());
        }
    }

    @Test
    void exceptionalLoaderCompletionReportsContractViolationExactlyOnce() {
        var hash = hash(13);
        var content = content(hash);
        var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var reported = new AtomicReference<Throwable>();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        }, failure -> {
            assertTrue(reported.compareAndSet(null, failure));
        })) {
            var lease = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> pending, current(),
                OWNER, WorkPriority.BACKGROUND);
            var cause = new IOException("loader failed exceptionally");

            pending.completeExceptionally(cause);
            cache.tick();

            var failure = assertInstanceOf(AcquireResult.Failed.class, lease.poll());
            var violation = assertInstanceOf(IllegalStateException.class, failure.failure().cause());
            assertSame(cause, violation.getCause());
            assertSame(violation, reported.get());
            assertEquals(0, cache.loadingCount());
        }
    }

    @Test
    void sessionRetirementCancelsOnlyItsExactPendingWork() {
        var epoch = new Object();
        var process = new Object();
        var epochPending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var processPending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            cache.getOrStart(request(hash(40)), content(hash(40)), key(), (ignored, dispatch) -> epochPending,
                current(), epoch, WorkPriority.BACKGROUND);
            cache.getOrStart(request(hash(41)), content(hash(41)), key(), (ignored, dispatch) -> processPending,
                current(), process, WorkPriority.BACKGROUND);

            cache.clearSession(epoch);
            cache.tick();

            assertEquals(1, epochPending.cancelCalls.get());
            assertEquals(0, processPending.cancelCalls.get());
            assertEquals(1, cache.loadingCount());
        }
    }

    @Test
    void readyProbeNeverStartsWorkAndReturnsAnIndependentConsumer() throws Exception {
        var hash = hash(60);
        var content = content(hash);
        var loaded = target();
        var loads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            assertTrue(cache.findReady(request(hash), content, key(), current()).isEmpty());

            var builder = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return ready(loaded);
            }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            var probe = cache.findReady(request(hash), content, key(), current()).orElseThrow();

            assertEquals(1, loads.get());
            assertNotSame(builder, probe);
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, probe.poll()).target());
            builder.close();
            probe.close();
        }
    }

    @Test
    void detachedUseCannotPublishOrReplaceAnOrdinaryFlight() throws Exception {
        var id = hash(63);
        var content = content(id);
        var detachedTarget = target();
        var ordinaryTarget = target();
        var detachedLoading = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var ordinaryLoading = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var detached = cache.getOrStartDetached(request(id), content, key(),
                (cancelled, dispatch) -> detachedLoading, OWNER, WorkPriority.BACKGROUND);
            var ordinary = cache.getOrStart(request(id), content, key(), (cancelled, dispatch) -> ordinaryLoading,
                current(), OWNER, WorkPriority.BACKGROUND);
            detachedLoading.complete(readyResult(detachedTarget));
            cache.tick();
            assertSame(detachedTarget, assertInstanceOf(AcquireResult.Ready.class, detached.poll()).target());
            assertInstanceOf(AcquireResult.Pending.class, ordinary.poll());
            assertTrue(cache.findReady(request(id), content, key(), current()).isEmpty());
            ordinaryLoading.complete(readyResult(ordinaryTarget));
            cache.tick();
            assertSame(ordinaryTarget, assertInstanceOf(AcquireResult.Ready.class, ordinary.poll()).target());
            detached.close();
            cache.tick();
            try (var retained = cache.findReady(request(id), content, key(), current()).orElseThrow()) {
                assertSame(ordinaryTarget, assertInstanceOf(AcquireResult.Ready.class, retained.poll()).target());
            }
            assertSame(ordinaryTarget, assertInstanceOf(AcquireResult.Ready.class, ordinary.poll()).target());
            assertInstanceOf(AcquireResult.Ended.class, detached.poll());
            ordinary.close();
        }
    }

    @Test
    void cachedProbeDoesNotJoinOrKeepAnExistingFullFlightAlive() {
        var hash = hash(61);
        var content = content(hash);
        var fullWork = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var cacheLoads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var full = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> fullWork, current(),
                OWNER, WorkPriority.BACKGROUND);

            var probe = cache.getOrStartCached(request(hash), content, key(), (ignored, dispatch) -> {
                cacheLoads.incrementAndGet();
                return new CompletableFuture<>();
            }, current(), OWNER, WorkPriority.BACKGROUND);

            assertInstanceOf(AcquireResult.Missing.class, probe.poll());
            probe.close();
            assertEquals(0, cacheLoads.get());
            full.cancelPending();
            cache.tick();
            assertEquals(1, fullWork.cancelCalls.get());
        }
    }

    @Test
    void fullOfflineDemandReplacesAProbeOnlyFlight() throws Exception {
        var hash = hash(62);
        var content = content(hash);
        var probeWork = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var loaded = target();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var probe = cache.getOrStartCached(request(hash), content, key(), (ignored, dispatch) -> probeWork,
                current(), OWNER, WorkPriority.BACKGROUND);
            var full = cache.getOrStartOffline(request(hash), content, key(), (ignored, dispatch) -> ready(loaded),
                current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();

            assertEquals(1, probeWork.cancelCalls.get());
            assertInstanceOf(AcquireResult.Failed.class, probe.poll());
            probe.close();
            var lease = full;
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
            lease.close();
        }
    }

    @Test
    void inFlightOverflowIsDisposedByTheOwnerTick() {
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var interests = new ArrayList<ResourceLease>();
            for (var marker = 0; marker < ModelRenderTargetCache.MAX_BACKGROUND_IN_FLIGHT; marker++) {
                var hash = wideHash(marker + 1);
                interests.add(cache.getOrStart(request(hash), content(hash), key(),
                    (ignored, dispatch) -> new CompletableFuture<>(), current(), OWNER, WorkPriority.BACKGROUND));
            }
            var overflowHash = wideHash(ModelRenderTargetCache.MAX_IN_FLIGHT + 1);
            var overflow = cache.getOrStart(request(overflowHash), content(overflowHash), key(),
                (ignored, dispatch) -> new CompletableFuture<>(), current(), OWNER, WorkPriority.BACKGROUND);

            assertInstanceOf(AcquireResult.Pending.class, overflow.poll());
            cache.tick();
            assertInstanceOf(AcquireResult.Failed.class, overflow.poll());
            Reference.reachabilityFence(interests);
        }
    }

    @Test
    void localInterestPromotesOneQueuedFlightAndKeepsIndependentLeases() throws Exception {
        var blockerEntered = new CountDownLatch(1);
        var unblockBackground = new CountDownLatch(1);
        scheduler.backgroundExecutor().execute(() -> {
            blockerEntered.countDown();
            await(unblockBackground);
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
        var id = hash(101);
        var content = content(id);
        var loaded = target();
        var admissions = new AtomicInteger();
        var productions = new AtomicInteger();
        var executionThread = new AtomicReference<Thread>();
        var context = new AtomicReference<ClientResourceScheduler.Dispatch>();
        ModelRenderTargetCache.Loader loader = (cancelled, dispatch) -> {
            admissions.incrementAndGet();
            context.set(dispatch);
            return CompletableFuture.supplyAsync(() -> {
                productions.incrementAndGet();
                executionThread.set(Thread.currentThread());
                return readyResult(loaded);
            }, dispatch);
        };
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var background =
                cache.getOrStart(request(id), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);
            assertEquals(0, productions.get());
            var local =
                cache.getOrStart(request(id), content, key(), loader, current(), OWNER, WorkPriority.LOCAL_PLAYER);
            var repeated =
                cache.getOrStart(request(id), content, key(), loader, current(), OWNER, WorkPriority.LOCAL_PLAYER);
            var completed = new CountDownLatch(1);
            scheduler.dispatch(WorkPriority.LOCAL_PLAYER).execute(completed::countDown);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(1, unblockBackground.getCount());
            assertEquals(Thread.NORM_PRIORITY, executionThread.get().getPriority());
            assertEquals(1, admissions.get());
            assertEquals(1, productions.get());
            assertNotSame(background, local);
            repeated.close();
            cache.tick();
            assertEquals(WorkPriority.LOCAL_PLAYER, context.get().priority());
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, background.poll()).target());
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, local.poll()).target());
            local.close();
            cache.tick();
            assertEquals(WorkPriority.LOCAL_PLAYER, context.get().priority());
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, background.poll()).target());
            var another = cache.findReady(request(id), content, key(), current()).orElseThrow();
            assertNotSame(background, another);
            background.close();
            cache.tick();
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, another.poll()).target());
            another.close();
        } finally {
            unblockBackground.countDown();
        }
    }

    @Test
    void cacheCloseTerminatesCapacityFailureAndRejectsQueuedOrLateCandidatesOnce() throws Exception {
        var rejections = new AtomicInteger();
        var cache = new ModelRenderTargetCache(scheduler, ignored -> rejections.incrementAndGet(), failure -> {
            throw new AssertionError(failure);
        });
        var interests = new ArrayList<ResourceLease>();
        var late = new CancelResistantFuture<ModelRenderTargetLoader.LoadResult>();
        var lateId = hash(120);
        var lateLease = cache.getOrStart(request(lateId), content(lateId), key(), (cancelled, dispatch) -> late,
            current(), OWNER, WorkPriority.LOCAL_PLAYER);
        var queuedTarget = target();
        var queuedId = hash(121);
        var queuedLease = cache.getOrStart(request(queuedId), content(queuedId), key(),
            (cancelled, dispatch) -> ready(queuedTarget), current(), OWNER, WorkPriority.LOCAL_PLAYER);
        addPending(cache, interests, 1000, ModelRenderTargetCache.MAX_BACKGROUND_IN_FLIGHT, WorkPriority.BACKGROUND);
        var overflow = cache.getOrStart(request(hash(122)), content(hash(122)), key(),
            (cancelled, dispatch) -> new CompletableFuture<>(), current(), OWNER, WorkPriority.LOCAL_PLAYER);
        var overflowTerminal = cache.terminal(overflow);
        var queuedTerminal = cache.terminal(queuedLease);
        var lateTerminal = cache.terminal(lateLease);
        try (cache) {
            assertFalse(overflowTerminal.isDone());
        }
        assertInstanceOf(AcquireResult.Failed.class, overflowTerminal.join());
        assertInstanceOf(AcquireResult.Failed.class, queuedTerminal.join());
        assertInstanceOf(AcquireResult.Failed.class, lateTerminal.join());
        assertEquals(1, rejections.get());
        late.complete(readyResult(target()));
        cache.close();
        assertEquals(2, rejections.get());
        assertEquals(0, cache.loadingCount());
        Reference.reachabilityFence(interests);
    }

    @ParameterizedTest
    @CsvSource({"session,false", "close,false", "replacement,false", "session,true", "close,true", "replacement,true"})
    void cancellationBeforeLoaderReturnRejectsItsReadyCandidateWithoutReplacingTheTerminal(String retirement,
        boolean required) throws Exception {
        var rejections = new AtomicInteger();
        var violations = new AtomicInteger();
        var host = new ArrayDeque<Runnable>();
        var replacement = new AtomicReference<ResourceLease>();
        var id = hash(129);
        var content = content(id);
        var candidate = readyResult(target());
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> rejections.incrementAndGet(),
            ignored -> violations.incrementAndGet())) {
            ModelRenderTargetCache.Loader loader = (cancelled, dispatch) -> {
                switch (retirement) {
                    case "session" -> {
                        cache.clearSession(OWNER);
                        cache.tick();
                    }
                    case "close" -> cache.close();
                    case "replacement" -> replacement.set(cache.getOrStart(request(id), content, key(),
                        (ignored, replacementDispatch) -> new CompletableFuture<>(), current(), new Object(),
                        WorkPriority.LOCAL_PLAYER));
                    default -> throw new AssertionError("Unknown test retirement: " + retirement);
                }
                assertTrue(cancelled.getAsBoolean());
                return CompletableFuture.completedFuture(candidate);
            };
            var lease = required
                ? cache.getOrStartRequiredAsync(request(id), content, key(), loader, current(), OWNER, host::add)
                : cache.getOrStart(request(id), content, key(), loader, current(), OWNER, WorkPriority.LOCAL_PLAYER);
            var terminal = cache.terminal(lease);
            var cancelled = assertInstanceOf(AcquireResult.Failed.class, terminal.join());
            assertEquals(ResourceFailure.Kind.TRANSIENT, cancelled.failure().kind());
            assertInstanceOf(CancellationException.class, cancelled.failure().cause());
            assertEquals(1, rejections.get());
            assertTrue(host.isEmpty());
            cache.tick();
            cache.tick();
            assertSame(cancelled, terminal.join());
            assertSame(cancelled, lease.poll());
            assertEquals(1, rejections.get());
            assertEquals(0, violations.get());
            assertEquals(retirement.equals("replacement") ? 1 : 0, cache.loadingCount());
            if (replacement.get() != null) {
                assertInstanceOf(AcquireResult.Pending.class, replacement.get().poll());
                replacement.get().close();
            }
            lease.close();
        }
        assertEquals(1, rejections.get());
        assertEquals(0, violations.get());
    }

    private static void addPending(ModelRenderTargetCache cache, List<ResourceLease> interests, int start, int count,
        WorkPriority priority) {
        for (var marker = start; marker < start + count; marker++) {
            var id = wideHash(marker);
            interests.add(cache.getOrStart(request(id), content(id), key(),
                (cancelled, dispatch) -> new CompletableFuture<>(), current(), OWNER, priority));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void cancellationRejectsAQueuedCandidateBeforeReadyCommit() throws Exception {
        var hash = hash(5);
        var content = content(hash);
        var pending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var loaded = target();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStart(request(hash), content, key(), (context, dispatch) -> pending, current(),
                OWNER, WorkPriority.BACKGROUND);
            var terminal = cache.terminal(lease);

            pending.complete(readyResult(loaded));
            lease.cancelPending();
            cache.tick();

            assertEquals(0, pending.cancelCalls.get());
            assertInstanceOf(AcquireResult.Failed.class, terminal.join());
        }
    }

    @Test
    void cancelledOldFlightCannotOverwriteSameKeyReplacementAba() throws Exception {
        var hash = hash(6);
        var content = content(hash);
        var oldPending = new CancelResistantFuture<ModelRenderTargetLoader.LoadResult>();
        var replacementPending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var oldTarget = target();
        var replacementTarget = target();
        var rejected = new AtomicInteger();
        var loads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> rejected.incrementAndGet(), failure -> {
            throw new AssertionError(failure);
        })) {
            var old = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return oldPending;
            }, current(), OWNER, WorkPriority.BACKGROUND);
            old.cancelPending();
            cache.tick();
            var replacement = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return replacementPending;
            }, current(), OWNER, WorkPriority.BACKGROUND);

            oldPending.complete(readyResult(oldTarget));
            assertEquals(1, rejected.get());
            assertInstanceOf(AcquireResult.Pending.class, replacement.poll());
            replacementPending.complete(readyResult(replacementTarget));
            cache.tick();

            assertEquals(2, loads.get());
            assertSame(replacementTarget, assertInstanceOf(AcquireResult.Ready.class, replacement.poll()).target());
        }
    }

    @Test
    void normalAcquireAtomicallyReplacesOfflineFlight() {
        var hash = hash(7);
        var content = content(hash);
        var offlinePending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var normalPending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var offline = cache.getOrStartOffline(request(hash), content, key(), (context, dispatch) -> offlinePending,
                current(), OWNER, WorkPriority.BACKGROUND);
            var normal = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> normalPending,
                current(), OWNER, WorkPriority.BACKGROUND);

            assertInstanceOf(AcquireResult.Failed.class, offline.poll());
            offline.close();
            assertEquals(1, offlinePending.cancelCalls.get());
            assertInstanceOf(AcquireResult.Pending.class, normal.poll());
        }
    }

    @Test
    void authorityChangePreservesTheHeldReadyValueWithoutCacheRetirement() throws Exception {
        var hash = hash(8);
        var content = content(hash);
        var loaded = target();
        var current = new AtomicReference<>(content.representation().identity());
        ModelRenderTargetCache.CurrentLookup lookup = (identity, key, request) -> identity.equals(current.get());
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> ready(loaded), lookup,
                OWNER, WorkPriority.BACKGROUND);
            cache.tick();

            current.set(new ModelFileIdentity(hash, Hash256.parse(hash(80).toString().substring(5))));

            assertFalse(lease.isCurrent(request(hash)));
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
            try (var retained =
                cache.findReady(request(hash), content, key(), (identity, k, req) -> true).orElseThrow()) {
                assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, retained.poll()).target());
            }
        }
    }

    @Test
    void invalidLoaderOutcomeFailsFlightWithoutRetainedFailureHit() {
        var hash = hash(9);
        var content = content(hash);
        var reports = new AtomicInteger();
        var loads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        }, ignored -> reports.incrementAndGet())) {
            var first = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return null;
            }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertInstanceOf(IllegalStateException.class,
                assertInstanceOf(AcquireResult.Failed.class, first.poll()).failure().cause());

            var retry = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return failed(ResourceFailure.Kind.TRANSIENT, new CancellationException("retry"));
            }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertInstanceOf(AcquireResult.Failed.class, retry.poll());
            assertEquals(2, loads.get());
            assertEquals(1, reports.get());
        }
    }

    @Test
    void closedHandleObservesEnded() throws Exception {
        var hash = hash(10);
        var loaded = target();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStart(request(hash), content(hash), key(), (ignored, dispatch) -> ready(loaded),
                current(), OWNER, WorkPriority.BACKGROUND);
            lease.close();
            assertInstanceOf(AcquireResult.Ended.class, lease.poll());
            assertFalse(lease.isCurrent(request(hash)));
        }
    }

    @Test
    void ordinaryTerminalWaitsForTheOwnerTickBeforeExposure() throws Exception {
        var loaded = target();
        var id = hash(11);
        var content = content(id);
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStart(request(id), content, key(), (ignored, dispatch) -> ready(loaded), current(),
                OWNER, WorkPriority.BACKGROUND);

            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            assertTrue(cache.findReady(request(id), content, key(), current()).isEmpty());

            cache.tick();
            assertTrue(hasReady(cache, request(id), content, key(), current()));
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
        }
    }

    @Test
    void requiredBootstrapPublishesOnTheOwnerWithoutWaitingForTick() throws Exception {
        var loaded = target();
        var id = hash(12);
        var content = content(id);
        var admissions = new AtomicInteger();
        var hostTasks = new ArrayDeque<Runnable>();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStartRequiredAsync(request(id), content, key(), (ignored, dispatch) -> {
                admissions.incrementAndGet();
                return CompletableFuture.completedFuture(readyResult(loaded));
            }, current(), OWNER, hostTasks::add);

            assertEquals(1, admissions.get());
            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            assertTrue(cache.findReady(request(id), content, key(), current()).isEmpty());
            hostTasks.remove().run();
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "session", "release", "replacement"})
    void requiredCandidateWaitingOnTheHostIsDisposedByRetirementWithoutHostProgress(String retirement)
        throws Exception {
        var rejections = new AtomicInteger();
        var violations = new AtomicInteger();
        var host = new ArrayDeque<Runnable>();
        var replacement = new AtomicReference<ResourceLease>();
        var id = hash(131);
        var content = content(id);
        var loaded = target();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> rejections.incrementAndGet(),
            ignored -> violations.incrementAndGet())) {
            var lease = cache.getOrStartRequiredAsync(request(id), content, key(),
                (cancelled, dispatch) -> ready(loaded), current(), OWNER, host::add);
            var terminal = cache.terminal(lease);
            assertEquals(1, host.size());
            assertEquals(0, rejections.get());
            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            switch (retirement) {
                case "close" -> cache.close();
                case "session" -> {
                    cache.clearSession(OWNER);
                    cache.tick();
                }
                case "release" -> {
                    lease.close();
                    cache.tick();
                }
                case "replacement" -> replacement.set(
                    cache.getOrStart(request(id), content, key(), (cancelled, dispatch) -> new CompletableFuture<>(),
                        current(), new Object(), WorkPriority.LOCAL_PLAYER));
                default -> throw new AssertionError("Unknown test retirement: " + retirement);
            }
            var cancelled = assertInstanceOf(AcquireResult.Failed.class, terminal.join());
            assertEquals(ResourceFailure.Kind.TRANSIENT, cancelled.failure().kind());
            assertEquals(1, rejections.get());
            assertEquals(1, host.size());
            host.remove().run();
            assertSame(cancelled, terminal.join());
            assertEquals(1, rejections.get());
            assertEquals(0, violations.get());
            if (retirement.equals("close")) {
                assertThrows(IllegalStateException.class,
                    () -> cache.findReady(request(id), content, key(), current()));
            } else {
                assertTrue(cache.findReady(request(id), content, key(), current()).isEmpty());
            }
            if (replacement.get() != null) {
                assertInstanceOf(AcquireResult.Pending.class, replacement.get().poll());
                replacement.get().close();
            }
            lease.close();
        }
        assertEquals(1, rejections.get());
        assertEquals(0, violations.get());
    }

    @Test
    void hostPublicationFailureRejectsTheCandidateWithoutChangingReady() throws Exception {
        ImageSource source = () -> {
            throw new AssertionError("Injected decoder owns this test boundary");
        };
        var image = new NativeImage(1, 1, false);
        var prepared = new PreparedTextureSet(new PBRImageSources(source, null, null), "test/",
            ModelResourceFailures.none(), ignored -> image);
        prepared.prepare(() -> false);
        var attempts = new AtomicInteger();
        var cache = new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, ignored -> {
        }, ignored -> {
            attempts.incrementAndGet();
            throw new IOException("host failed");
        }, () -> 4, () -> 0);
        var candidate = new ModelRenderTargetLoader.ModelCandidate(closeableTarget(), prepared);
        var id = hash(50);
        var content = content(id);
        try (cache) {
            var lease = cache.getOrStart(request(id), content, key(),
                (ignored, dispatch) -> CompletableFuture
                    .completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(candidate)),
                current(), OWNER, WorkPriority.BACKGROUND);

            cache.tick();

            assertEquals(1, attempts.get());
            assertTrue(cache.findReady(request(id), content, key(), current()).isEmpty());
            assertInstanceOf(AcquireResult.Failed.class, lease.poll());
            assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
        }
    }

    @Test
    void hostBindingIsAdoptedBeforeTheCandidateBecomesReady() throws Exception {
        ImageSource source = () -> {
            throw new AssertionError("Injected decoder owns this test boundary");
        };
        var image = new NativeImage(1, 1, false);
        var prepared = new PreparedTextureSet(new PBRImageSources(source, null, null), "test/",
            ModelResourceFailures.none(), ignored -> image);
        prepared.prepare(() -> false);
        @SuppressWarnings("removal")
        var id = new ResourceLocation("ysm", "test/model");
        var textureManager = (TextureManager)UnsafeUtil.getUnsafe().allocateInstance(TextureManager.class);
        HostTexturePublisher publisher = candidateTextures -> {
            candidateTextures.transfer().closeUnregistered();
            return new HostTexturePublisher.PublishedTextureBinding(id, textureManager, List.of());
        };
        var cache = new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, ignored -> {
        }, publisher, () -> 4, () -> 0);
        var candidate = new ModelRenderTargetLoader.ModelCandidate(closeableTarget(), prepared);
        try (cache) {
            var lease = cache.getOrStart(request(hash(51)), content(hash(51)), key(),
                (ignored, dispatch) -> CompletableFuture
                    .completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(candidate)),
                current(), OWNER, WorkPriority.BACKGROUND);

            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            cache.tick();
            var target = assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target();
            assertEquals(id, target.textureId());
            assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
        }
    }

    @Test
    void identicalRepresentationInAnotherRegionDoesNotJoinTheOldPendingWork(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var original = content(hash(81));
        var local = bound(original, root.resolve("local"));
        var global = bound(original, root.resolve("global"));
        var oldWork = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var newWork = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var loads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var oldLease = cache.getOrStart(request(hash(81)), local, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return oldWork;
            }, current(), OWNER, WorkPriority.BACKGROUND);
            var newLease = cache.getOrStart(request(hash(81)), global, key(), (ignored, dispatch) -> {
                loads.incrementAndGet();
                return newWork;
            }, current(), OWNER, WorkPriority.BACKGROUND);
            assertEquals(2, loads.get());
            var newTarget = target();
            newWork.complete(readyResult(newTarget));
            cache.tick();
            assertSame(newTarget, assertInstanceOf(AcquireResult.Ready.class, newLease.poll()).target());
            assertInstanceOf(AcquireResult.Pending.class, oldLease.poll());
            var oldTarget = target();
            oldWork.complete(readyResult(oldTarget));
            cache.tick();
            assertSame(oldTarget, assertInstanceOf(AcquireResult.Ready.class, oldLease.poll()).target());
            oldLease.close();
            newLease.close();
        }
    }

    private static ModelContent bound(ModelContent source, java.nio.file.Path root) {
        var binding = ContentStorageBinding.remote(new CacheRegion(root));
        return new ModelContent() {
            @Override
            public ModelRepresentation representation() {
                return source.representation();
            }

            @Override
            public ChunkDataSource chunks() {
                return source.chunks();
            }

            @Override
            public ContentStorageBinding storageBinding() {
                return binding;
            }
        };
    }

    private static ModelRenderTargetCache.CurrentLookup current() {
        return (content, key, request) -> true;
    }

    private static CompletableFuture<ModelRenderTargetLoader.LoadResult> ready(ModelRenderTarget target) {
        return CompletableFuture.completedFuture(readyResult(target));
    }

    private static ModelRenderTargetLoader.LoadResult.Ready readyResult(ModelRenderTarget target) {
        return new ModelRenderTargetLoader.LoadResult.Ready(ModelRenderTargetLoader.ModelCandidate.testing(target));
    }

    private static CompletableFuture<ModelRenderTargetLoader.LoadResult> failed(ResourceFailure.Kind kind,
        Throwable cause) {
        return CompletableFuture
            .completedFuture(new ModelRenderTargetLoader.LoadResult.Failed(new ResourceFailure(kind, cause)));
    }

    private static ResourceRequest request(ModelId modelId) {
        return new ResourceRequest(modelId, "player", "default", PROFILE);
    }

    private static RenderTargetKey key() {
        return new RenderTargetKey("player", "default", PROFILE);
    }

    private static ModelId hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return ModelId.fromHash(new Hash256(bytes));
    }

    private static ModelId wideHash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        ByteBuffer.wrap(bytes).putInt(marker);
        return ModelId.fromHash(new Hash256(bytes));
    }

    private static ModelRenderTarget target() throws InstantiationException {
        return closeableTarget();
    }

    private static ModelRenderTarget closeableTarget() throws InstantiationException {
        var assets = new CommonAsset(Map.of(),
            TargetSoundResources.empty(new ModelFileIdentity(hash(99), new Hash256(new byte[Hash256.SIZE]))));
        return new ModelRenderTarget(hash(99), "test", new RenderTargetResources() {}, assets,
            com.elfmcys.ysm.expression.aot.TestExpressionModules
                .store(com.elfmcys.ysm.expression.aot.TestExpressionModules.create(), Map.of()),
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
    }

    private static ModelContent content(ModelId modelId) {
        var representation = representation(new ModelFileIdentity(modelId,
            Hash256.parse(hash(NEXT_CONTAINER_ID.getAndIncrement()).toString().substring(5))));
        return new ModelContent() {
            @Override
            public ModelId modelId() {
                return modelId;
            }

            @Override
            public ModelRepresentation representation() {
                return representation;
            }

            @Override
            public ModelFileView modelFile() {
                return null;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }
        };
    }

    private static boolean hasReady(ModelRenderTargetCache cache, ResourceRequest request, ModelContent content,
        RenderTargetKey key, ModelRenderTargetCache.CurrentLookup currentLookup) {
        var found = cache.findReady(request, content, key, currentLookup);
        if (found.isEmpty()) {
            return false;
        }
        found.get().close();
        return true;
    }

    private static ModelRepresentation representation(ModelFileIdentity identity) {
        try {
            var value = (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
            var field = ModelRepresentation.class.getDeclaredField("identity");
            UnsafeUtil.getUnsafe().putObject(value, UnsafeUtil.getUnsafe().objectFieldOffset(field), identity);
            return value;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static class CountingFuture<T> extends CompletableFuture<T> {
        protected final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class CancelResistantFuture<T> extends CountingFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            return false;
        }
    }
}
