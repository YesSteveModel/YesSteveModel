package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.model.catalog.CatalogScanProgress;
import net.minecraft.network.chat.Component;

public final class CatalogProgressText {
    private CatalogProgressText() {}

    public static Component of(CatalogScanProgress progress) {
        return switch (progress.phase()) {
            case DISCOVERING -> Component.translatable("gui.yes_steve_model.catalog_progress.discovering");
            case PROCESSING -> Component.translatable("gui.yes_steve_model.catalog_progress.processing",
                progress.processed(), progress.total());
            case FINALIZING -> Component.translatable("gui.yes_steve_model.catalog_progress.finalizing");
        };
    }
}
