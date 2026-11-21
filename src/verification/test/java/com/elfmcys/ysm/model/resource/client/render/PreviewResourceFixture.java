package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.RenderTargetResources;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Supplies independent uses from the production cache while retaining a real target-owned pixel allocation. */
public final class PreviewResourceFixture implements AutoCloseable {
    public static final int PIXEL = 0x44332211;
    private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
    private final ModelRenderTargetCache cache =
        new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, failure -> {
            throw new AssertionError(failure);
        }, HostTexturePublisher.production(), () -> 4, () -> 0);
    private final CompletableFuture<ModelRenderTargetLoader.LoadResult> loading = new CompletableFuture<>();
    private final NativeImage pixels = new NativeImage(1, 1, false);
    private final ModelContent content;
    private final ResourceRequest request;
    private final RenderTargetKey key;
    private ModelRenderTarget target;

    public PreviewResourceFixture(ModelContent content, ResourceRequest request) {
        this.content = content;
        this.request = request;
        key = new RenderTargetKey(request.targetId(), request.requestedTexture(), request.bakeProfile(),
            request.expressionBinding(), request.purpose());
        pixels.setPixelRGBA(0, 0, PIXEL);
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
    }

    public ResourceLease acquire() {
        return cache.getOrStart(request, content, key, (cancelled, dispatch) -> loading,
            (identity, targetKey, use) -> true, this, WorkPriority.BACKGROUND);
    }

    public void finishPreparation() throws Exception {
        target = new ModelRenderTarget(content.modelId(), request.targetId(), new RenderTargetResources() {},
            new CommonAsset(Map.of(), TargetSoundResources.empty(content.representation().identity())),
            TestExpressionModules.store(TestExpressionModules.create(), Map.of()),
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class), null, null, pixels::close,
            Map.of());
        loading.complete(
            new ModelRenderTargetLoader.LoadResult.Ready(ModelRenderTargetLoader.ModelCandidate.testing(target)));
        cache.tick();
    }

    public int readPixel() {
        return pixels.getPixelRGBA(0, 0);
    }

    @Override
    public void close() {
        try {
            cache.close();
        } finally {
            if (target != null)
                target.close();
            else
                pixels.close();
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
        }
    }
}
