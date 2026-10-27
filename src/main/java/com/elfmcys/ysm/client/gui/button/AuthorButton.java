package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.gui.ModelScreen;

import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.info.AuthorProto;
import com.google.common.collect.Lists;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

public class AuthorButton extends Button {
    private final AuthorProto author;
    private final ModelRenderTarget model;
    private ResourceLocation avatar;
    private com.elfmcys.ysm.model.resource.client.asset.PageImages.Image avatarImage;
    private final int index;
    private final List<Component> tooltips;
    private int selectedContactIndex = -1;
    private final ModelScreen parent;

    public AuthorButton(int pX, int pY, AuthorProto author, ModelRenderTarget model, ResourceLocation avatar, int index,
        ModelScreen parent) {
        super(pX, pY, 70, 130, Component.empty(), b -> {
        }, DEFAULT_NARRATION);
        this.author = author;
        this.model = model;
        this.avatar = avatar;
        this.index = index;
        this.tooltips = Lists.newArrayList();
        if (this.author != null) {
            updateTooltips(false);
        }
        this.parent = parent;
    }

    public static AuthorButton empty(int pX, int pY, ModelScreen parent) {
        return new AuthorButton(pX, pY, null, null, null, -1, parent);
    }

    public void setAvatar(ResourceLocation avatar) {
        this.avatar = avatar;
    }

    public void setAvatarImage(com.elfmcys.ysm.model.resource.client.asset.PageImages.Image image) {
        avatarImage = image;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var supplied = avatarImage == null ? null : avatarImage.texture();
        var avatar = supplied == null ? this.avatar : supplied.id();
        Font font = Minecraft.getInstance().font;
        if (author == null || model == null || avatar == null) {
            graphics.fillGradient(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height,
                0x8F_434242, 0x8F_434242);
            graphics.drawCenteredString(font, Component.literal("......"), this.getX() + this.width / 2,
                this.getY() + this.height / 2, ChatFormatting.GRAY.getColor());
            return;
        }
        if (this.isHoveredOrFocused()) {
            graphics.fillGradient(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height,
                0x8F_306BAC, 0x8F_306BAC);
        } else {
            graphics.fillGradient(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height,
                0x8F_434242, 0x8F_434242);
        }
        graphics.blit(avatar, this.getX() + 3, this.getY() + 3, 64, 64, 0, 0, 64, 64, 64, 64);

        String authorName = LanguageManager.getI18n(model, "metadata.authors.%d.name".formatted(index), author.name());
        String authorRole = LanguageManager.getI18n(model, "metadata.authors.%d.role".formatted(index), author.role());
        String authorComment =
            LanguageManager.getI18n(model, "metadata.authors.%d.comment".formatted(index), author.comment().orElse(""));

        renderScrollingString(graphics, font, Component.literal(authorName), this.getX() + 2, this.getY() + 72,
            this.getX() + width - 2, this.getY() + 82, ChatFormatting.GOLD.getColor());
        graphics.drawCenteredString(font, authorRole, this.getX() + 35, this.getY() + 82,
            ChatFormatting.GREEN.getColor());
        drawWordWrap(graphics, Component.literal(authorComment), this.getX() + 3, this.getY() + 95, 64, 0xFFFFFFFF);
    }

    public void drawWordWrap(GuiGraphics graphics, FormattedText text, int x, int y, int lineWidth, int color) {
        Font font = Minecraft.getInstance().font;
        for (FormattedCharSequence formattedcharsequence : font.split(text, lineWidth)) {
            graphics.drawString(font, formattedcharsequence, x, y, color, false);
            y += 9;
            if (y > this.getY() + this.height) {
                return;
            }
        }
    }

    public void renderToolTip(GuiGraphics graphics, Screen screen, int pMouseX, int pMouseY) {
        if (this.isHovered && !tooltips.isEmpty()) {
            graphics.renderComponentTooltip(screen.getMinecraft().font, tooltips, pMouseX, pMouseY);
        } else {
            if (selectedContactIndex != -1) {
                selectedContactIndex = -1;
                updateTooltips(false);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double pMouseX, double pMouseY, double pDelta) {
        if (pDelta > 0) {
            if (selectedContactIndex > 0) {
                selectedContactIndex--;
                updateTooltips(false);
            }
            return true;
        } else if (pDelta < 0) {
            if (selectedContactIndex < tooltips.size() - 2) {
                selectedContactIndex++;
                updateTooltips(false);
            }
            return true;
        }
        return super.mouseScrolled(pMouseX, pMouseY, pDelta);
    }

    private void updateTooltips(boolean copied) {
        if (author == null) {
            return;
        }

        tooltips.clear();
        for (int i = 0; i < author.contacts().size(); i++) {
            var contact = author.contacts().get(i);
            MutableComponent component = Component.literal(contact.key() + ": " + contact.value_());
            if (i == selectedContactIndex) {
                component.append(
                    Component.literal(copied ? " ✓" : " ◀").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
            }
            tooltips.add(component);
        }
        if (!tooltips.isEmpty()) {
            tooltips.add(Component.translatable("gui.yes_steve_model.model.info.contact.click_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public void onPress() {
        if (author == null) {
            return;
        }

        int index = selectedContactIndex;
        if (index == -1) {
            index = 0;
        }
        if (index < 0 || index >= author.contacts().size()) {
            return;
        }
        String value = author.contacts().get(index).value_();
        if (value == null) {
            return;
        }

        if (value.startsWith("http://") || value.startsWith("https://")) {
            parent.showScreen(new ConfirmLinkScreen(yes -> {
                if (!parent.hasCurrentWorld())
                    return;
                if (yes) {
                    Util.getPlatform().openUri(value);
                }
                parent.showScreen(parent);
            }, value, true));
        } else {
            Minecraft.getInstance().keyboardHandler.setClipboard(value);
            if (selectedContactIndex == -1) {
                selectedContactIndex = 0;
            }
            updateTooltips(true);
        }
    }
}
