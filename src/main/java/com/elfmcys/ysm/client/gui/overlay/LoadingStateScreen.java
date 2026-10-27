package com.elfmcys.ysm.client.gui.overlay;

import com.elfmcys.ysm.client.gui.CatalogProgressText;
import com.elfmcys.ysm.config.LoadingStateScreenConfig;
import com.elfmcys.ysm.model.catalog.CatalogScanProgress;
import com.elfmcys.ysm.model.service.ClientModelService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

public class LoadingStateScreen implements IGuiOverlay {
    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        if (LoadingStateScreenConfig.DISABLE_LOADING_STATE_SCREEN.get())
            return;
        var service = ClientModelService.current().orElse(null);
        if (service == null)
            return;
        var progress = service.catalogProgress();
        if (progress == null)
            return;
        var color = switch (progress.phase()) {
            case DISCOVERING -> ChatFormatting.LIGHT_PURPLE;
            case PROCESSING -> ChatFormatting.GREEN;
            case FINALIZING -> ChatFormatting.GOLD;
        };
        var text = Component.translatable("gui.yes_steve_model.sync_hint.title")
            .append(CatalogProgressText.of(progress).copy().withStyle(color));
        var position = LoadingStateScreenConfig.LOADING_STATE_POSITION.get();
        var textWidth = gui.getFont().width(text);
        var x = switch (position) {
            case TOP_LEFT, BOTTOM_LEFT -> 10;
            case TOP_CENTER, BOTTOM_CENTER -> (screenWidth - textWidth) / 2;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - 10 - textWidth;
        };
        var y = switch (position) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> 10;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - 30;
            case BOTTOM_CENTER -> screenHeight - 85;
        };
        graphics.drawString(gui.getFont(), text, x, y, 0xFFFFFF);
        if (progress.phase() == CatalogScanProgress.Phase.PROCESSING && progress.total() > 0) {
            var barWidth = 150;
            var barHeight = 10;
            var barX = switch (position) {
                case TOP_LEFT, BOTTOM_LEFT -> 10;
                case TOP_CENTER, BOTTOM_CENTER -> (screenWidth - barWidth) / 2;
                case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - 10 - barWidth;
            };
            var barY = y + 12;
            graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF555555);
            graphics.fill(barX, barY, barX + (int)(barWidth * ((float)progress.processed() / progress.total())),
                barY + barHeight, 0xFF00FF00);
        }
    }
}
