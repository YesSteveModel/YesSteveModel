package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class StarButton extends FlatColorButton {
    private final static ResourceLocation ICON = new ResourceLocation(YesSteveModel.MOD_ID, "texture/icon.png");

    public StarButton(int x, int y) {
        super(x, y, 20, 20, Component.empty(), (b) -> {
        });
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float pPartialTick) {
        super.renderWidget(graphics, mouseX, mouseY, pPartialTick);
        int startX = (this.width - 16) / 2;
        int startY = (this.height - 16) / 2;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(modelInfoCap -> player
                .getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP).ifPresent(starModelsCap -> {
                    var modelHash = modelInfoCap.getModelHash();
                    if (modelHash != null && starModelsCap.containModel(modelHash)) {
                        graphics.blit(ICON, this.getX() + startX, this.getY() + startY, 16, 16, 16, 0, 16, 16, 256,
                            256);
                    } else {
                        graphics.blit(ICON, this.getX() + startX, this.getY() + startY, 16, 16, 0, 0, 16, 16, 256, 256);
                    }
                }));
        }
    }

    @Override
    public void onPress() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(modelInfoCap -> player
                .getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP).ifPresent(starModelsCap -> {
                    var modelHash = modelInfoCap.getModelHash();
                    if (modelHash == null) {
                        return;
                    }
                    if (starModelsCap.containModel(modelHash)) {
                        starModelsCap.removeModel(modelHash);
                        ClientProtocolGateway.updateStar(modelHash, false);
                    } else {
                        starModelsCap.addModel(modelHash);
                        ClientProtocolGateway.updateStar(modelHash, true);
                    }
                }));
        }
    }
}
