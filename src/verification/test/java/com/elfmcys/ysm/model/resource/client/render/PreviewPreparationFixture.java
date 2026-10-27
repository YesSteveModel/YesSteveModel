package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.RenderTargetResources;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.util.UnsafeUtil;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the actual shared preparation cache with independently cancellable candidate consumers. */
public final class PreviewPreparationFixture implements AutoCloseable {
    private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
    private final PreparedTargetCache<String, AutoCloseable> cache = new PreparedTargetCache<>(scheduler);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    public final AtomicInteger inputsClosed = new AtomicInteger();
    public final AtomicInteger preparedClosed = new AtomicInteger();

    public PreviewPreparationFixture() {
        scheduler.backgroundExecutor().execute(() -> {
            try {
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("preparation gate timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        });
    }

    public CompletableFuture<ModelRenderTargetLoader.ModelCandidate> acquire() {
        var interest = cache.acquire("exact-player", scheduler.dispatch(WorkPriority.BACKGROUND),
            () -> new PreparedTargetCache.Producer<AutoCloseable>() {
            @Override
            public AutoCloseable produce(java.util.function.BooleanSupplier cancelled) {
                return preparedClosed::incrementAndGet;
            }

            @Override
            public void close() {
                inputsClosed.incrementAndGet();
            }
        });
        var result = new CompletableFuture<ModelRenderTargetLoader.ModelCandidate>();
        interest.whenComplete((pin, failure) -> completions.add(() -> {
            if (failure != null)
                result.completeExceptionally(failure);
            else {
                try {
                    var candidate = candidate(pin);
                    if (!result.complete(candidate))
                        candidate.close();
                } catch (Exception error) {
                    pin.close();
                    result.completeExceptionally(error);
                }
            }
        }));
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled())
                interest.cancel(false);
        });
        return result;
    }

    public void finishPreparation() {
        release.countDown();
        try {
            CompletableFuture.runAsync(() -> {}, scheduler.backgroundExecutor()).get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        drainCompletions();
    }

    private void drainCompletions() {
        for (Runnable completion; (completion = completions.poll()) != null;)
            completion.run();
    }

    public static ModelRenderTargetLoader.ModelCandidate candidate(AutoCloseable preparedOwner) throws Exception {
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        }
        var model = new Hash256(new byte[32]);
        var containerBytes = new byte[32];
        containerBytes[0] = 1;
        var target = new ModelRenderTarget(model, "player", new RenderTargetResources() {},
            new CommonAsset(Map.of(),
                TargetSoundResources.empty(new ModelFileIdentity(model, new Hash256(containerBytes))),
                new Object2ReferenceOpenHashMap<>(), new Object2ReferenceOpenHashMap<>()),
            TestExpressionModules.create(), (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class),
            null, null, preparedOwner, Map.of());
        return ModelRenderTargetLoader.ModelCandidate.testing(target);
    }

    @Override
    public void close() {
        try {
            cache.close();
        } finally {
            release.countDown();
            scheduler.shutdown();
            var interrupted = false;
            while (true) {
                try {
                    scheduler.awaitTermination();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            if (interrupted)
                Thread.currentThread().interrupt();
            drainCompletions();
        }
    }
}
