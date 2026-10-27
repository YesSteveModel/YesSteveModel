package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(TextureManager.class)
public class TextureManagerMixin {
    @Unique
    private final Map<ResourceLocation, Integer> ysm$gpuIds = new HashMap<>();

    @Shadow
    @Final
    private Map<ResourceLocation, AbstractTexture> byPath;

    @Inject(
        method = "register(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V",
        at = @At("RETURN"))
    private void afterRegister(ResourceLocation id, AbstractTexture texture, CallbackInfo ci) {
        if ("ysm".equals(id.getNamespace())) {
            var installed = byPath.get(id);
            var accepted = installed != MissingTextureAtlasSprite.getTexture();
            if (accepted) {
                ResourceDiagnostics.track(ResourceDiagnostics.Kind.TEXTURE, installed, id.toString());
                ysm$gpuIds.put(id, installed.getId());
            }
            ResourceDiagnostics.log("texture register: {} type={} accepted={}", id,
                installed.getClass().getSimpleName(), accepted);
        }
    }

    @Inject(method = "safeClose", at = @At("RETURN"))
    private void afterClose(ResourceLocation id, AbstractTexture texture, CallbackInfo ci) {
        if ("ysm".equals(id.getNamespace())) {
            ResourceDiagnostics.untrack(ResourceDiagnostics.Kind.TEXTURE, texture);
            var gpuId = ysm$gpuIds.remove(id);
            if (ResourceDiagnostics.isEnabled()) {
                ResourceDiagnostics.log("texture remove: {} gpuId={} gpuAlive={}", id, gpuId,
                    gpuId == null ? null : GL11.glIsTexture(gpuId));
            }
        }
    }
}
