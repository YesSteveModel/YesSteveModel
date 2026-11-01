package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.model.resource.client.asset.PageImages;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.catalog.client.ModelPackInfo;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class PackButton extends Button implements AutoCloseable {
    private final static ResourceLocation ICON =
        new ResourceLocation(YesSteveModel.MOD_ID, "texture/default_pack_icon.png");

    private final ModelPackInfo pack;
    private final @Nullable PageImages.Image cover;

    public PackButton(int x, int y, int width, int height, ModelPackInfo pack, @Nullable ModelPackDescriptor descriptor,
        PageImages assets, OnPress onPress) {
        super(x, y, width, height, Component.literal(LanguageManager.getI18n(pack, "name", pack.name())), onPress,
            DEFAULT_NARRATION);
        this.pack = pack;
        cover = descriptor != null && descriptor.coverHash() != null ? assets.packCover(descriptor) : null;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float frameDeltaTime) {
        var icon = cover == null ? null : cover.texture();
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;

        int backgroundColor = 0xFF_9B51E0;
        graphics.fillGradient(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height,
            backgroundColor, backgroundColor);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (icon == null) {
            graphics.blit(ICON, this.getX(), this.getY(), 0, 0, this.width, this.height, this.width, this.height);
        } else {
            graphics.blit(icon.id(), this.getX(), this.getY(), 0, 0, this.width, this.height, this.width, this.height);
        }
        RenderSystem.disableBlend();

        Component message = this.getMessage();
        List<FormattedCharSequence> split = font.split(message, 45);
        if (split.size() > 1) {
            drawCenteredString(graphics, font, split.get(0), this.getX() + this.width / 2,
                this.getY() + this.height - 19, 0x555555);
            drawCenteredString(graphics, font, split.get(1), this.getX() + this.width / 2,
                this.getY() + this.height - 10, 0x555555);
        } else {
            drawCenteredString(graphics, font, this.getMessage(), this.getX() + this.width / 2,
                this.getY() + this.height - 15, 0x555555);
        }

        if (this.isHoveredOrFocused()) {
            int sideColor = 0xFF_E1BEE7;
            graphics.fillGradient(this.getX(), this.getY() + 1, this.getX() + 1, this.getY() + this.height - 1,
                sideColor, sideColor);
            graphics.fillGradient(this.getX(), this.getY(), this.getX() + this.width, this.getY() + 1, sideColor,
                sideColor);
            graphics.fillGradient(this.getX() + this.width - 1, this.getY() + 1, this.getX() + this.width,
                this.getY() + this.height - 1, sideColor, sideColor);
            graphics.fillGradient(this.getX(), this.getY() + this.height - 1, this.getX() + this.width,
                this.getY() + this.height, sideColor, sideColor);
        }
    }

    public void renderComponentTooltip(GuiGraphics graphics, Screen screen, int pMouseX, int pMouseY) {
        String desc = LanguageManager.getI18n(pack, "description", pack.desc());
        if (StringUtils.isBlank(desc) && !Screen.hasShiftDown()) {
            return;
        }
        var mutableComponents = new java.util.ArrayList<Component>();
        if (!StringUtils.isBlank(desc))
            mutableComponents.add(Component.literal(desc));
        if (Screen.hasShiftDown())
            mutableComponents.add(Component.literal(pack.hierarchy()));
        if (this.isHovered()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0f, 0f, 4000);
            graphics.renderComponentTooltip(screen.getMinecraft().font, mutableComponents, pMouseX, pMouseY);
            graphics.pose().popPose();
        }
    }

    private static void drawCenteredString(GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
        graphics.drawString(font, text, x - font.width(text) / 2, y, color, false);
    }

    private static void drawCenteredString(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y,
        int color) {
        graphics.drawString(font, text, x - font.width(text) / 2, y, color, false);
    }

    @Override
    public void close() {}

    @Nullable PageImages.Image coverImage() { return cover; }
}
