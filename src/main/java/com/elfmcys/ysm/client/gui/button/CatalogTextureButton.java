package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.gui.GuiModelFraming;
import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.util.RenderUtil;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class CatalogTextureButton extends Button
    implements com.elfmcys.ysm.client.demand.ClientDemandCoordinator.CardDisplay, AutoCloseable {
    private static final int CARD_OVERLAY_Z = 3500;

    private final Hash256 modelHash;
    private final String path;
    private final String texture;
    private final CustomGuiPlayerEntity entity;
    private final SelectionHandler selection;
    private final com.elfmcys.ysm.client.demand.ClientDemandCoordinator.PageScope page;
    private final GuiModelFraming framing = new GuiModelFraming();
    private @Nullable ModelRenderTarget renderTarget;
    private @Nullable Throwable error;
    private boolean closed;
    private boolean displaying;

    public CatalogTextureButton(int x, int y, Hash256 modelHash, String path, String texture,
        CustomGuiPlayerEntity entity, com.elfmcys.ysm.client.demand.ClientDemandCoordinator.PageScope page,
        SelectionHandler selection) {
        super(x, y, 54, 102, Component.literal(texture), ignored -> {
        }, DEFAULT_NARRATION);
        this.modelHash = modelHash;
        this.path = path;
        this.texture = texture;
        this.entity = entity;
        this.selection = selection;
        this.page = page;
        page.card(this, target());
    }

    public com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Target target() {
        return new com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Target(modelHash, texture);
    }

    @Override
    public void activate(ModelResourceDemand.Mode mode) {
        if (closed)
            return;
        entity.setResourceDemandMode(mode);
        if (!displaying) {
            entity.getPreviewInfo().setPreview(AnimationRegister.IDLE);
            entity.updateModelAndTexture(modelHash, texture);
            entity.demandScope();
            displaying = true;
        }
    }

    @Override
    public void deactivate() {
        if (!displaying)
            return;
        displaying = false;
        renderTarget = null;
        error = null;
        entity.retire();
    }

    @Override
    public void onPress() {
        selection.select(modelHash, path, texture, renderTarget);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateLazyFailure();
        graphics.fillGradient(getX(), getY(), getX() + width, getY() + height, 0xFF434242, 0xFF434242);
        if (renderTarget == null || error != null) {
            var text = error == null ? ".".repeat((int)((Util.getMillis() / 250L) % 4L)) : "!";
            graphics.drawCenteredString(Minecraft.getInstance().font, text, getX() + width / 2,
                getY() + (height - 20) / 2, error == null ? 0xFFF3EFE0 : 0xFFFF5555);
        } else {
            renderEntity(graphics);
        }
        renderName(graphics);
        if (isHoveredOrFocused()) {
            graphics.fillGradient(getX(), getY() + 1, getX() + 1, getY() + height - 1, CARD_OVERLAY_Z, 0xFFF3EFE0,
                0xFFF3EFE0);
            graphics.fillGradient(getX(), getY(), getX() + width, getY() + 1, CARD_OVERLAY_Z, 0xFFF3EFE0, 0xFFF3EFE0);
            graphics.fillGradient(getX() + width - 1, getY() + 1, getX() + width, getY() + height - 1, CARD_OVERLAY_Z,
                0xFFF3EFE0, 0xFFF3EFE0);
            graphics.fillGradient(getX(), getY() + height - 1, getX() + width, getY() + height, CARD_OVERLAY_Z,
                0xFFF3EFE0, 0xFFF3EFE0);
        }
    }

    private void updateLazyFailure() {
        if (closed)
            return;
        renderTarget = entity.displayedModel();
        error = entity.modelLoadFailure();
        if (error != null || renderTarget == null || renderTarget.playerResources() == null) {
            return;
        }
        var resources = renderTarget.playerResources();
        if (resources.animations().hasFailures() || resources.fpArmAnimations().hasFailures()) {
            error = new IllegalStateException("One or more preview animations failed to load");
        }
        if (error != null) {
            renderTarget = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        page.removeCard(this);
        deactivate();
    }

    private void renderEntity(GuiGraphics graphics) {
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        RenderSystem.enableScissor((int)(getX() * scale), (int)(window.getHeight() - ((getY() + height - 20) * scale)),
            (int)(width * scale), (int)((height - 20) * scale));
        try {
            RenderUtil.renderFramedModelInGui(getX(), getY(), width, height - 20,
                Minecraft.getInstance().getFrameTime(), entity, RegisterEntityRenderersEvent.getPlayerRenderer(), false,
                true, framing);
        } finally {
            RenderSystem.disableScissor();
        }
    }

    private void renderName(GuiGraphics graphics) {
        Font font = Minecraft.getInstance().font;
        var name = renderTarget == null ? texture
            : LanguageManager.getI18n(renderTarget, "files.player.texture.%s".formatted(texture), texture);
        List<FormattedCharSequence> split = font.split(Component.literal(name), 50);
        if (split.size() > 1) {
            graphics.drawCenteredString(font, split.get(0), getX() + width / 2, getY() + height - 19, 0xF3EFE0);
            graphics.drawCenteredString(font, split.get(1), getX() + width / 2, getY() + height - 10, 0xF3EFE0);
        } else {
            graphics.drawCenteredString(font, Component.literal(name), getX() + width / 2, getY() + height - 15,
                0xF3EFE0);
        }
    }

    @FunctionalInterface
    public interface SelectionHandler {
        void select(Hash256 hash, String path, String texture, @Nullable ModelRenderTarget renderTarget);
    }
}
