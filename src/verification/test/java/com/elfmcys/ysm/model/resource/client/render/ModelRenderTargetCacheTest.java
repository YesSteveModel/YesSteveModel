package com.elfmcys.ysm.model.resource.client.render;

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
import com.elfmcys.ysm.model.domain.Hash256;
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
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> closes.incrementAndGet())) {
            var activeId = hash(90);
            var activeContent = content(activeId);
            var activeTarget = target();
            var active = cache.getOrStart(request(activeId), activeContent, key(), (ignored, dispatch) -> ready(activeTarget),
                current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            var shared = cache.findReady(request(activeId), activeContent, key(), current()).orElseThrow();
            var requiredId = hash(91);
            var requiredTarget = target();
            var required = cache.getOrStartRequired(request(requiredId), content(requiredId), key(),
                ignored -> readyResult(requiredTarget), current(), OWNER, Runnable::run);
            var contents = new java.util.ArrayList<ModelContent>();
            for (int index = 0; index <= count; ++index) {
                var id = hash(200 + index);
                var content = content(id);
                contents.add(content);
                var target = target();
                var lease = cache.getOrStart(request(id), content, key(), (ignored, dispatch) -> ready(target), current(), OWNER, WorkPriority.BACKGROUND);
                cache.tick();
                lease.close();
                cache.tick();
            }
            assertEquals(count, cache.unusedCount());
            assertEquals(count + 2, cache.readyCount());
            active.close();
            cache.tick();
            assertEquals(count, cache.unusedCount());
            assertSame(activeTarget, assertInstanceOf(AcquireResult.Ready.class, shared.poll()).target());
            if (count > 0) {
                var id = hash(200 + count);
                var reused = cache.findReady(request(id), contents.get(count), key(), current()).orElseThrow();
                cache.tick();
                assertEquals(count - 1, cache.unusedCount());
                reused.close();
                cache.tick();
                assertEquals(count, cache.unusedCount());
            }
            limit.set(0);
            cache.tick();
            assertEquals(0, cache.unusedCount());
            assertEquals(2, cache.readyCount());
            assertInstanceOf(AcquireResult.Ready.class, required.poll());
            shared.close();
            cache.tick();
            assertEquals(1, cache.readyCount());
            assertInstanceOf(AcquireResult.Ready.class, required.poll());
            required.close();
            cache.tick();
            assertEquals(0, cache.readyCount());
            assertEquals(0, closes.get());
        } finally {
            limit.set(original);
        }
    }

    @Test
    void offlineMemoryFailureCannotBecomeAnOptionalCacheMiss() {
        var id = hash(115);
        var memory = new OutOfMemoryError("injected offline preparation");
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {})) {
            var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
            var result = cache.getOrStartCached(request(id), content(id), key(),
                (cancelled, dispatch) -> pending, current(), OWNER, WorkPriority.BACKGROUND);
            pending.complete(new ModelRenderTargetLoader.LoadResult.Failed(
                new ResourceFailure(ResourceFailure.Kind.TRANSIENT, memory)));
            cache.tick();
            assertSame(memory, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
            assertEquals(0, cache.loadingCount());
            cache.tick();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrappedCompilerMemoryFailureCannotBecomeAnOptionalCacheMiss(boolean cacheOnly) {
        var id = hash(116);
        var memory = new OutOfMemoryError("injected compiler allocation");
        var compilerFailure = new org.codehaus.commons.compiler.InternalCompilerException("compiler allocation", memory);
        var backendFailure = new dev.semaweave.compilation.BackendException(
            dev.semaweave.compilation.BackendException.FailureKind.INTERNAL,
            new dev.semaweave.backend.janino.JaninoJavaCompilerBackend().descriptor(), List.of(), 0, compilerFailure);
        var failure = com.elfmcys.ysm.format.AssetLoadException.access("Expression compiler is unavailable", backendFailure);
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {})) {
            var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
            var result = cacheOnly
                ? cache.getOrStartCached(request(id), content(id), key(),
                    (cancelled, dispatch) -> pending, current(), OWNER, WorkPriority.BACKGROUND)
                : cache.getOrStartOffline(request(id), content(id), key(),
                    (cancelled, dispatch) -> pending, current(), OWNER, WorkPriority.BACKGROUND);
            pending.complete(ModelRenderTargetLoader.failure(failure));
            cache.tick();
            // A necessary compiler failure must end this acquisition, rather than authorize another preparation.
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
            assertEquals(0, cache.loadingCount());
            assertEquals(0, cache.readyCount());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryCompilerAccessFailureRemainsAnOptionalCacheMiss(boolean cacheOnly) {
        var id = hash(117);
        var compilerFailure = new org.codehaus.commons.compiler.InternalCompilerException("compiler unavailable",
            new IllegalStateException("ordinary backend failure"));
        var backendFailure = new dev.semaweave.compilation.BackendException(
            dev.semaweave.compilation.BackendException.FailureKind.INTERNAL,
            new dev.semaweave.backend.janino.JaninoJavaCompilerBackend().descriptor(), List.of(), 0, compilerFailure);
        var failure = com.elfmcys.ysm.format.AssetLoadException.access("Expression compiler is unavailable", backendFailure);
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {})) {
            var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
            var result = cacheOnly
                ? cache.getOrStartCached(request(id), content(id), key(),
                    (cancelled, dispatch) -> pending, current(), OWNER, WorkPriority.BACKGROUND)
                : cache.getOrStartOffline(request(id), content(id), key(),
                    (cancelled, dispatch) -> pending, current(), OWNER, WorkPriority.BACKGROUND);
            pending.complete(ModelRenderTargetLoader.failure(failure));
            cache.tick();
            assertTrue(result.join().isEmpty());
            assertEquals(0, cache.loadingCount());
        }
    }

    @Test
    void memoryFailureUsesTheAcquisitionTerminalWithoutContentOrContractFailure() throws Exception {
        var id = hash(113);
        var memory = new OutOfMemoryError("injected");
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {},
            failure -> { throw new AssertionError("Memory failure is not a loader contract violation", failure); })) {
            var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
            var lease = cache.getOrStart(request(id), content(id), key(), (cancelled, dispatch) -> pending,
                current(), OWNER, WorkPriority.BACKGROUND);
            pending.completeExceptionally(new java.util.concurrent.CompletionException(memory));
            cache.tick();
            var failed = assertInstanceOf(AcquireResult.Failed.class, lease.poll()).failure();
            assertEquals(ResourceFailure.Kind.TRANSIENT, failed.kind());
            assertSame(memory, failed.cause());
            assertEquals(0, cache.loadingCount());
            lease.close();
            var nextTarget = readyResult(target());
            var next = cache.getOrStart(request(hash(114)), content(hash(114)), key(),
                (cancelled, dispatch) -> CompletableFuture.completedFuture(nextTarget), current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertInstanceOf(AcquireResult.Ready.class, next.poll());
            next.close();
        }
    }

    @Test
    void synchronousMemoryFailureUsesTheSameTerminal() {
        var id = hash(118);
        var memory = new OutOfMemoryError("injected synchronous loader");
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {},
            failure -> { throw new AssertionError("Unexpected contract failure", failure); })) {
            var lease = cache.getOrStart(request(id), content(id), key(), (cancelled, dispatch) -> { throw memory; },
                current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            var failed = assertInstanceOf(AcquireResult.Failed.class, lease.poll()).failure();
            assertSame(memory, failed.cause());
            assertEquals(ResourceFailure.Kind.TRANSIENT, failed.kind());
            assertEquals(0, cache.loadingCount());
            lease.close();
        }
    }

    @Test
    void configuredLimitEvictsOldestUnusedTargetsAndPreservesActiveTargets() throws Exception {
        var limit = ClientConfig.MODEL_RETENTION_COUNT;
        var original = limit.get();
        limit.set(2);
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        })) {
            var firstContent = content(hash(81));
            var secondContent = content(hash(82));
            var activeContent = content(hash(83));
            var firstTarget = target();
            var secondTarget = target();
            var activeTarget = target();
            var first = cache.getOrStart(request(hash(81)), firstContent, key(), (ignored, dispatch) -> ready(firstTarget),
                current(), OWNER, WorkPriority.BACKGROUND);
            var second = cache.getOrStart(request(hash(82)), secondContent, key(), (ignored, dispatch) -> ready(secondTarget),
                current(), OWNER, WorkPriority.BACKGROUND);
            var active = cache.getOrStart(request(hash(83)), activeContent, key(), (ignored, dispatch) -> ready(activeTarget),
                current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            first.close();
            second.close();
            cache.tick();
            assertEquals(2, cache.unusedCount());
            assertEquals(3, cache.readyCount());

            limit.set(1);
            cache.tick();
            assertEquals(1, cache.unusedCount());
            assertTrue(cache.findReady(request(hash(81)), firstContent, key(), current()).isEmpty());
            try (var reused = cache.findReady(request(hash(82)), secondContent, key(), current()).orElseThrow()) {
                assertInstanceOf(AcquireResult.Ready.class, reused.poll());
            }

            limit.set(0);
            cache.tick();
            assertEquals(0, cache.unusedCount());
            assertEquals(1, cache.readyCount());
            assertInstanceOf(AcquireResult.Ready.class, active.poll());
            active.close();
            cache.tick();
            assertEquals(0, cache.readyCount());
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

        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        })) {
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
            assertEquals(1, cache.readyCount());
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
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        })) {
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
            assertEquals(2, cache.readyCount());
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
            var first = cache.getOrStart(request(hash), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);
            var second = cache.getOrStart(request(hash), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);

            assertNotSame(first, second);
            assertEquals(1, loads.get());
            pending.complete(readyResult(firstTarget));
            cache.tick();
            assertSame(firstTarget, assertInstanceOf(AcquireResult.Ready.class, first.poll()).target());
            assertSame(firstTarget, assertInstanceOf(AcquireResult.Ready.class, second.poll()).target());
            assertEquals(1, cache.readyCount());

            cache.tick();
            assertEquals(1, cache.readyCount());
            first.close();
            second.close();
            cache.tick();
            assertEquals(0, cache.readyCount());
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
        try (var lease = cache.getOrStart(request(hash), content(hash), key(), (cancelled, dispatch) -> {
            probe.set(cancelled);
            return ready(target);
        }, current(), OWNER, WorkPriority.BACKGROUND)) {
            cache.tick();
            assertSame(target, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
        }
        cache.tick();
        assertEquals(0, cache.readyCount());
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
            var first = cache.getOrStart(request(hash), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);
            var second = cache.getOrStart(request(hash), content, key(), loader, current(), OWNER, WorkPriority.BACKGROUND);

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
            var lease = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> pending, current(), OWNER, WorkPriority.BACKGROUND);
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
            cache.getOrStart(request(hash(40)), content(hash(40)), key(), (ignored, dispatch) -> epochPending, current(), epoch, WorkPriority.BACKGROUND);
            cache.getOrStart(request(hash(41)), content(hash(41)), key(), (ignored, dispatch) -> processPending, current(),
                process, WorkPriority.BACKGROUND);

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
    void cachedProbeDoesNotJoinOrKeepAnExistingFullFlightAlive() {
        var hash = hash(61);
        var content = content(hash);
        var fullWork = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var cacheLoads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var full = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> fullWork, current(), OWNER, WorkPriority.BACKGROUND);

            var probe = cache.getOrStartCached(request(hash), content, key(), (ignored, dispatch) -> {
                cacheLoads.incrementAndGet();
                return new CompletableFuture<>();
            }, current(), OWNER, WorkPriority.BACKGROUND);

            assertTrue(probe.join().isEmpty());
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
            var probe = cache.getOrStartCached(request(hash), content, key(), (ignored, dispatch) -> probeWork, current(), OWNER, WorkPriority.BACKGROUND);
            var full =
                cache.getOrStartOffline(request(hash), content, key(), (ignored, dispatch) -> ready(loaded), current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();

            assertEquals(1, probeWork.cancelCalls.get());
            assertTrue(probe.join().isEmpty());
            var lease = full.join().orElseThrow();
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
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        })) {
            var background = cache.getOrStart(request(id), content, key(), loader, current(), OWNER,
                WorkPriority.BACKGROUND);
            assertEquals(0, productions.get());
            var local = cache.getOrStart(request(id), content, key(), loader, current(), OWNER,
                WorkPriority.LOCAL_PLAYER);
            var repeated = cache.getOrStart(request(id), content, key(), loader, current(), OWNER,
                WorkPriority.LOCAL_PLAYER);
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
    void backgroundAdmissionLeavesTwoSlotsAndPromotionReleasesOnlyItsBackgroundSlot() {
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var interests = new ArrayList<ResourceLease>();
            var firstId = wideHash(1);
            var firstContent = content(firstId);
            interests.add(cache.getOrStart(request(firstId), firstContent, key(),
                (cancelled, dispatch) -> new CompletableFuture<>(), current(), OWNER, WorkPriority.BACKGROUND));
            addPending(cache, interests, 2, ModelRenderTargetCache.MAX_BACKGROUND_IN_FLIGHT - 1,
                WorkPriority.BACKGROUND);
            var deniedStarts = new AtomicInteger();
            var denied = cache.getOrStart(request(wideHash(300)), content(wideHash(300)), key(),
                (cancelled, dispatch) -> {
                    deniedStarts.incrementAndGet();
                    return new CompletableFuture<>();
                }, current(), OWNER, WorkPriority.BACKGROUND);
            interests.add(cache.getOrStart(request(firstId), firstContent, key(), (cancelled, dispatch) -> {
                throw new AssertionError("A promoted Flight must keep its original producer");
            }, current(), OWNER, WorkPriority.LOCAL_PLAYER));
            addPending(cache, interests, 301, 1, WorkPriority.BACKGROUND);
            addPending(cache, interests, 302, 1, WorkPriority.LOCAL_PLAYER);
            var totalOverflow = cache.getOrStart(request(wideHash(303)), content(wideHash(303)), key(),
                (cancelled, dispatch) -> {
                    deniedStarts.incrementAndGet();
                    return new CompletableFuture<>();
                }, current(), OWNER, WorkPriority.LOCAL_PLAYER);
            assertEquals(ModelRenderTargetCache.MAX_IN_FLIGHT, cache.loadingCount());
            assertEquals(0, deniedStarts.get());
            cache.tick();
            assertInstanceOf(AcquireResult.Failed.class, denied.poll());
            assertInstanceOf(AcquireResult.Failed.class, totalOverflow.poll());
            assertEquals(ModelRenderTargetCache.MAX_IN_FLIGHT, cache.loadingCount());
            Reference.reachabilityFence(interests);
        }
    }

    @Test
    void promotionAfterSchedulerShutdownDoesNotReleaseBackgroundAdmission() {
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var interests = new ArrayList<ResourceLease>();
            var id = wideHash(1);
            var content = content(id);
            var dispatch = new AtomicReference<ClientResourceScheduler.Dispatch>();
            interests.add(cache.getOrStart(request(id), content, key(), (cancelled, acceptedDispatch) -> {
                dispatch.set(acceptedDispatch);
                return new CompletableFuture<>();
            }, current(), OWNER, WorkPriority.BACKGROUND));
            addPending(cache, interests, 2, ModelRenderTargetCache.MAX_BACKGROUND_IN_FLIGHT - 1,
                WorkPriority.BACKGROUND);
            scheduler.shutdown();
            for (var attempt = 0; attempt < 3; attempt++) {
                interests.add(cache.getOrStart(request(id), content, key(), (cancelled, ignored) -> {
                    throw new AssertionError("A local interest must reuse the admitted Flight");
                }, current(), OWNER, WorkPriority.LOCAL_PLAYER));
            }
            var starts = new AtomicInteger();
            var denied = cache.getOrStart(request(wideHash(300)), content(wideHash(300)), key(),
                (cancelled, ignored) -> {
                    starts.incrementAndGet();
                    return new CompletableFuture<>();
                }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertEquals(WorkPriority.BACKGROUND, dispatch.get().priority());
            assertEquals(0, starts.get());
            assertInstanceOf(AcquireResult.Failed.class, denied.poll());
            assertEquals(ModelRenderTargetCache.MAX_BACKGROUND_IN_FLIGHT, cache.loadingCount());
            Reference.reachabilityFence(interests);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "release", "session", "replacement"})
    void exactRetirementReturnsBackgroundAdmissionOnce(String retirement) {
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var interests = new ArrayList<ResourceLease>();
            var id = wideHash(1);
            var content = content(id);
            var oldOwner = new Object();
            var oldWork = new CancelResistantFuture<ModelRenderTargetLoader.LoadResult>();
            var old = cache.getOrStart(request(id), content, key(), (cancelled, dispatch) -> oldWork,
                current(), oldOwner, WorkPriority.BACKGROUND);
            var oldTerminal = cache.terminal(old);
            interests.add(old);
            addPending(cache, interests, 2, ModelRenderTargetCache.MAX_BACKGROUND_IN_FLIGHT - 1,
                WorkPriority.BACKGROUND);
            ResourceLease replacement = null;
            switch (retirement) {
                case "release" -> old.close();
                case "session" -> cache.clearSession(oldOwner);
                case "replacement" -> {
                    replacement = cache.getOrStart(request(id), content, key(),
                        (cancelled, dispatch) -> new CompletableFuture<>(), current(), OWNER,
                        WorkPriority.LOCAL_PLAYER);
                    interests.add(replacement);
                }
                default -> oldWork.complete(new ModelRenderTargetLoader.LoadResult.Failed(
                    new ResourceFailure(ResourceFailure.Kind.TRANSIENT, new IOException("controlled failure"))));
            }
            cache.tick();
            assertInstanceOf(AcquireResult.Failed.class, oldTerminal.join());
            oldWork.complete(new ModelRenderTargetLoader.LoadResult.Failed(
                new ResourceFailure(ResourceFailure.Kind.TRANSIENT, new IOException("late old failure"))));
            cache.tick();
            if (replacement != null) {
                assertInstanceOf(AcquireResult.Pending.class, replacement.poll());
            }
            addPending(cache, interests, 300, 1, WorkPriority.BACKGROUND);
            var deniedStarts = new AtomicInteger();
            var denied = cache.getOrStart(request(wideHash(301)), content(wideHash(301)), key(),
                (cancelled, dispatch) -> {
                    deniedStarts.incrementAndGet();
                    return new CompletableFuture<>();
                }, current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertEquals(0, deniedStarts.get());
            assertInstanceOf(AcquireResult.Failed.class, denied.poll());
            assertEquals(ModelRenderTargetCache.MAX_BACKGROUND_IN_FLIGHT + (replacement == null ? 0 : 1),
                cache.loadingCount());
            Reference.reachabilityFence(interests);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void queuedCompletionPromotionPreservesCandidateAndBackgroundShare(int limit) throws Exception {
        var rejections = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> rejections.incrementAndGet(),
            failure -> { throw new AssertionError(failure); }, ignored -> {
                throw new AssertionError("Testing candidates bypass the host");
            }, () -> limit)) {
            var backgrounds = new ArrayList<ResourceLease>();
            var promotedId = hash(104);
            var promotedContent = content(promotedId);
            var promotedTarget = target();
            for (var index = 0; index < 3; index++) {
                var id = hash(101 + index);
                var loaded = target();
                backgrounds.add(cache.getOrStart(request(id), content(id), key(),
                    (cancelled, dispatch) -> ready(loaded), current(), OWNER, WorkPriority.BACKGROUND));
            }
            var promoted = cache.getOrStart(request(promotedId), promotedContent, key(),
                (cancelled, dispatch) -> ready(promotedTarget), current(), OWNER, WorkPriority.BACKGROUND);
            var localInterest = cache.getOrStart(request(promotedId), promotedContent, key(), (cancelled, dispatch) -> {
                throw new AssertionError("Queued completion promotion must preserve the existing candidate");
            }, current(), OWNER, WorkPriority.LOCAL_PLAYER);
            var locals = new ArrayList<ResourceLease>();
            for (var index = 0; index < limit; index++) {
                var id = hash(110 + index);
                var loaded = target();
                locals.add(cache.getOrStart(request(id), content(id), key(),
                    (cancelled, dispatch) -> ready(loaded), current(), OWNER, WorkPriority.LOCAL_PLAYER));
            }
            localInterest.close();
            cache.tick();
            assertEquals(limit, cache.readyCount());
            assertSame(promotedTarget, assertInstanceOf(AcquireResult.Ready.class, promoted.poll()).target());
            assertInstanceOf(AcquireResult.Ready.class, backgrounds.get(0).poll());
            assertInstanceOf(AcquireResult.Pending.class, backgrounds.get(1).poll());
            for (var index = 0; index < locals.size(); index++) {
                if (index < limit - 2)
                    assertInstanceOf(AcquireResult.Ready.class, locals.get(index).poll());
                else
                    assertInstanceOf(AcquireResult.Pending.class, locals.get(index).poll());
            }
            assertEquals(0, rejections.get());
        }
    }

    @Test
    void cacheCloseTerminatesCapacityFailureAndRejectsQueuedOrLateCandidatesOnce() throws Exception {
        var rejections = new AtomicInteger();
        var cache = new ModelRenderTargetCache(scheduler, ignored -> rejections.incrementAndGet());
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
    @ValueSource(strings = {"cancel", "stale", "failure"})
    void localNonReadyDispositionStillConsumesItsSharedTickBudget(String disposition) throws Exception {
        var rejections = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> rejections.incrementAndGet(),
            failure -> { throw new AssertionError(failure); }, ignored -> {
                throw new AssertionError("Testing candidates bypass the host");
            }, () -> 2)) {
            var firstTarget = target();
            var firstId = hash(125);
            var first = cache.getOrStart(request(firstId), content(firstId), key(),
                (cancelled, dispatch) -> disposition.equals("failure")
                    ? failed(ResourceFailure.Kind.TRANSIENT, new IOException("controlled failure"))
                    : ready(firstTarget),
                (identity, key, request) -> !disposition.equals("stale"), OWNER, WorkPriority.LOCAL_PLAYER);
            var firstTerminal = cache.terminal(first);
            if (disposition.equals("cancel")) {
                first.close();
            }
            var secondTarget = target();
            var second = cache.getOrStart(request(hash(126)), content(hash(126)), key(),
                (cancelled, dispatch) -> ready(secondTarget), current(), OWNER, WorkPriority.LOCAL_PLAYER);
            var backgroundTarget = target();
            var background = cache.getOrStart(request(hash(127)), content(hash(127)), key(),
                (cancelled, dispatch) -> ready(backgroundTarget), current(), OWNER, WorkPriority.BACKGROUND);
            cache.tick();
            assertInstanceOf(AcquireResult.Failed.class, firstTerminal.join());
            assertInstanceOf(AcquireResult.Pending.class, second.poll());
            assertSame(backgroundTarget, assertInstanceOf(AcquireResult.Ready.class, background.poll()).target());
            assertEquals(disposition.equals("failure") ? 0 : 1, rejections.get());
            cache.tick();
            assertSame(secondTarget, assertInstanceOf(AcquireResult.Ready.class, second.poll()).target());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void schedulerRejectionTerminatesAsTransientWithoutAContractViolation(boolean continuation) {
        scheduler.shutdown();
        var violations = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        }, ignored -> violations.incrementAndGet())) {
            ModelRenderTargetLoader.LoadResult result = new ModelRenderTargetLoader.LoadResult.Failed(
                new ResourceFailure(ResourceFailure.Kind.TRANSIENT, new IOException("unused")));
            var lease = cache.getOrStart(request(hash(128)), content(hash(128)), key(), (cancelled, dispatch) -> {
                if (continuation) {
                    return CompletableFuture.completedFuture(result).thenApplyAsync(value -> value, dispatch);
                }
                return CompletableFuture.supplyAsync(() -> result, dispatch);
            }, current(), OWNER, WorkPriority.LOCAL_PLAYER);
            cache.tick();
            assertEquals(ResourceFailure.Kind.TRANSIENT,
                assertInstanceOf(AcquireResult.Failed.class, lease.poll()).failure().kind());
            assertEquals(0, cache.loadingCount());
            assertEquals(0, violations.get());
        }
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

    @ParameterizedTest
    @CsvSource({"false,exceptional", "true,exceptional", "false,null", "true,null"})
    void cancellationBeforeLoaderReturnIgnoresItsLateFailureOrMissingOutcome(boolean required, String completion)
        throws Exception {
        var violations = new AtomicInteger();
        var host = new ArrayDeque<Runnable>();
        var id = hash(130);
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
            throw new AssertionError("A failed loader has no candidate to dispose");
        }, ignored -> violations.incrementAndGet())) {
            ModelRenderTargetCache.Loader loader = (cancelled, dispatch) -> {
                cache.clearSession(OWNER);
                cache.tick();
                assertTrue(cancelled.getAsBoolean());
                return completion.equals("exceptional")
                    ? CompletableFuture.failedFuture(new IOException("late loader failure"))
                    : CompletableFuture.completedFuture(null);
            };
            var lease = required
                ? cache.getOrStartRequiredAsync(request(id), content(id), key(), loader, current(), OWNER, host::add)
                : cache.getOrStart(request(id), content(id), key(), loader, current(), OWNER, WorkPriority.LOCAL_PLAYER);
            var terminal = cache.terminal(lease);
            var cancelled = assertInstanceOf(AcquireResult.Failed.class, terminal.join());
            assertInstanceOf(CancellationException.class, cancelled.failure().cause());
            cache.tick();
            assertSame(cancelled, terminal.join());
            assertSame(cancelled, lease.poll());
            assertEquals(0, violations.get());
            assertEquals(0, cache.loadingCount());
            assertTrue(host.isEmpty());
            lease.close();
        }
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
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        })) {
            var lease = cache.getOrStart(request(hash), content, key(), (context, dispatch) -> pending, current(), OWNER, WorkPriority.BACKGROUND);
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
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> rejected.incrementAndGet())) {
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
            var offline =
                cache.getOrStartOffline(request(hash), content, key(), (context, dispatch) -> offlinePending, current(), OWNER, WorkPriority.BACKGROUND);
            var normal = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> normalPending, current(), OWNER, WorkPriority.BACKGROUND);

            assertTrue(offline.join().isEmpty());
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
            var lease = cache.getOrStart(request(hash), content, key(), (ignored, dispatch) -> ready(loaded), lookup, OWNER, WorkPriority.BACKGROUND);
            cache.tick();

            current.set(new ModelFileIdentity(hash, hash(80)));

            assertFalse(lease.isCurrent(request(hash)));
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
            assertEquals(1, cache.readyCount());
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
    void closedHandleCannotBeObserved() throws Exception {
        var hash = hash(10);
        var loaded = target();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease =
                cache.getOrStart(request(hash), content(hash), key(), (ignored, dispatch) -> ready(loaded), current(), OWNER, WorkPriority.BACKGROUND);
            lease.close();
            assertThrows(IllegalStateException.class, lease::poll);
        }
    }

    @Test
    void ordinaryTerminalWaitsForTheOwnerTickBeforeExposure() throws Exception {
        var loaded = target();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStart(request(hash(11)), content(hash(11)), key(), (ignored, dispatch) -> ready(loaded),
                current(), OWNER, WorkPriority.BACKGROUND);

            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            assertEquals(0, cache.readyCount());

            cache.tick();
            assertEquals(1, cache.readyCount());
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
        }
    }

    @Test
    void requiredBootstrapPublishesOnTheOwnerWithoutWaitingForTick() throws Exception {
        var loaded = target();
        var admissions = new AtomicInteger();
        var hostTasks = new ArrayDeque<Runnable>();
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStartRequired(request(hash(12)), content(hash(12)), key(), ignored -> {
                admissions.incrementAndGet();
                return readyResult(loaded);
            }, current(), OWNER, hostTasks::add);

            assertEquals(1, admissions.get());
            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            assertEquals(0, cache.readyCount());
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
                case "replacement" -> replacement.set(cache.getOrStart(request(id), content, key(),
                    (cancelled, dispatch) -> new CompletableFuture<>(), current(), new Object(),
                    WorkPriority.LOCAL_PLAYER));
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
            assertEquals(0, cache.readyCount());
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
    void ordinarySuccessCapAppliesAtTheReadyCommitForDesktopAndMobile() throws Exception {
        assertSuccessCap(4);
        assertSuccessCap(2);
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
        }, () -> 4);
        var candidate = new ModelRenderTargetLoader.ModelCandidate(closeableTarget(), prepared);
        try (cache) {
            var lease = cache.getOrStart(request(hash(50)), content(hash(50)), key(),
                (ignored, dispatch) -> CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(candidate)),
                current(), OWNER, WorkPriority.BACKGROUND);

            cache.tick();

            assertEquals(1, attempts.get());
            assertEquals(0, cache.readyCount());
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
        }, publisher, () -> 4);
        var candidate = new ModelRenderTargetLoader.ModelCandidate(closeableTarget(), prepared);
        try (cache) {
            var lease = cache.getOrStart(request(hash(51)), content(hash(51)), key(),
                (ignored, dispatch) -> CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(candidate)),
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
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        })) {
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

    private void assertSuccessCap(int limit) throws Exception {
        try (var cache = new ModelRenderTargetCache(scheduler, ignored -> {
        }, ignored -> {
        }, ignored -> {
            throw new AssertionError("Testing candidates bypass the host");
        }, () -> limit)) {
            for (var marker = 60; marker < 65; marker++) {
                var hash = hash(marker);
                var target = target();
                cache.getOrStart(request(hash), content(hash), key(), (ignored, dispatch) -> ready(target), current(), OWNER, WorkPriority.BACKGROUND);
            }

            cache.tick();
            assertEquals(limit, cache.readyCount());
            cache.tick();
            assertEquals(Math.min(5, limit * 2), cache.readyCount());
        }
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

    private static ResourceRequest request(Hash256 modelId) {
        return new ResourceRequest(modelId, "player", "default", PROFILE);
    }

    private static RenderTargetKey key() {
        return new RenderTargetKey("player", "default", PROFILE);
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static Hash256 wideHash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        ByteBuffer.wrap(bytes).putInt(marker);
        return new Hash256(bytes);
    }

    private static ModelRenderTarget target() throws InstantiationException {
        return (ModelRenderTarget)UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
    }

    private static ModelRenderTarget closeableTarget() throws InstantiationException {
        return new ModelRenderTarget(hash(99), "test", new RenderTargetResources() {},
            (CommonAsset)UnsafeUtil.getUnsafe().allocateInstance(CommonAsset.class),
            com.elfmcys.ysm.expression.aot.TestExpressionModules.create(),
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
    }

    private static ModelContent content(Hash256 modelId) {
        var representation = representation(new ModelFileIdentity(modelId, hash(NEXT_CONTAINER_ID.getAndIncrement())));
        return new ModelContent() {
            @Override
            public Hash256 modelId() {
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

    @Test
    void requiredBootstrapKeepsFastTerminalDispositionOnTheHostExecutor() throws Exception {
        var loaded = target();
        var terminalThread = new AtomicReference<Thread>();
        Executor immediateHost = task -> {
            var host = new Thread(task, "required-bootstrap-host");
            host.start();
            try {
                host.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(error);
            }
        };
        try (var cache = new ModelRenderTargetCache(scheduler)) {
            var lease = cache.getOrStartRequired(request(hash(13)), content(hash(13)), key(),
                ignored -> readyResult(loaded), (identity, target, request) -> {
                    terminalThread.set(Thread.currentThread());
                    return true;
                }, OWNER, immediateHost);

            assertEquals("required-bootstrap-host", terminalThread.get().getName());
            assertSame(loaded, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
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
