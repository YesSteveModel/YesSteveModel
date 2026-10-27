package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRenderTargetTextureBindingTest {
    @BeforeAll
    static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
    }

    @Test
    void targetExposesOnlyAnAdoptedBindingAndReleasesItOnce() throws Exception {
        var target = target();
        var releases = new AtomicInteger();
        @SuppressWarnings("removal")
        var id = new ResourceLocation("ysm", "test/target");

        assertThrows(IllegalStateException.class, target::textureId);
        target.adoptTexture(id, releases::incrementAndGet);
        assertEquals(id, target.textureId());

        target.close();
        target.close();
        assertEquals(1, releases.get());
    }

    private static ModelRenderTarget target() throws Exception {
        var bytes = new byte[Hash256.SIZE];
        return new ModelRenderTarget(new Hash256(bytes), "test", new RenderTargetResources() {},
            (CommonAsset)UnsafeUtil.getUnsafe().allocateInstance(CommonAsset.class),
            com.elfmcys.ysm.expression.aot.TestExpressionModules.create(),
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
    }
}
