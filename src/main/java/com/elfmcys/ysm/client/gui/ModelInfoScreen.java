package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.button.AuthorButton;
import com.elfmcys.ysm.client.gui.button.FlatColorButton;
import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.asset.PageImages;
import com.elfmcys.ysm.model.resource.client.asset.ModelAssetSelector;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.proto.mixel.manifest.info.AuthorProto;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("removal")
public class ModelInfoScreen extends ModelScreen {
    private final static ResourceLocation DEFAULT_AVATAR =
        new ResourceLocation(YesSteveModel.MOD_ID, "texture/default_avatar.png");
    private final static Map<String, Component> LINK_TYPE_PRESET =
        ImmutableMap.of("home", Component.translatable("gui.yes_steve_model.url.home"), "donate",
            Component.translatable("gui.yes_steve_model.url.donate"));
    private final Map<Integer, PageImages.Image> avatars = new HashMap<>();
    private com.elfmcys.ysm.client.demand.ClientDemandCoordinator.PageScope pageScope;
    private final Map<Integer, AuthorButton> authorButtons = new HashMap<>();

    private final PlayerModelScreen parent;
    private final ModelRenderTarget model;
    private final ModelInfoView modelInfo;
    private int startAuthorIndex = 0;
    private PageImages pageAssets;
    private int x;
    private int y;

    public ModelInfoScreen(PlayerModelScreen parent, ModelRenderTarget model) {
        super(Component.literal("Model Info GUI"), parent);
        this.parent = parent;
        this.model = model;
        this.modelInfo = model.info();
    }

    @Override
    public void removed() {
        closePage();
        super.removed();
    }

    @Override
    protected void init() {
        if (!hasCurrentWorld())
            return;
        this.clearWidgets();
        authorButtons.clear();

        this.x = (width - 420) / 2;
        this.y = (height - 235) / 2;

        var metadata = this.modelInfo.getMetadata();
        List<AuthorProto> authors = metadata == null ? List.of() : metadata.authors();
        if (authors.size() <= startAuthorIndex) {
            startAuthorIndex = 0;
        }

        var signature = pageIdentity();
        if (pageScope == null || !pageScope.matches(signature)) {
            var service = ClientModelService.instance();
            pageScope = service.demands().replacePage(pageScope, signature);
            pageAssets = new PageImages(service.createAssetBatch());
            pageScope.action(pageAssets);
            avatars.clear();
            for (var index = startAuthorIndex; index < Math.min(authors.size(), startAuthorIndex + 5); index++)
                if (authors.get(index).hasAvatar())
                    avatars.put(index, pageAssets.presentation(model.modelHash(),
                        ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, index));
        }
        for (int i = 0; i < 5; i++) {
            int index = startAuthorIndex + i;
            if (index >= authors.size()) {
                for (; i < 5; i++) {
                    addRenderableWidget(AuthorButton.empty(this.x + 25 + 75 * i, this.y + 15, this));
                }
                continue;
            }
            var author = authors.get(index);
            var button =
                new AuthorButton(this.x + 25 + 75 * i, this.y + 15, author, model, DEFAULT_AVATAR, index, this);
            authorButtons.put(index, button);
            addRenderableWidget(button);
            button.setAvatarImage(avatars.get(index));
        }

        addRenderableWidget(new FlatColorButton(x + 2, y + 25, 18, 100, Component.literal("<"), (b) -> {
            if (startAuthorIndex > 0) {
                startAuthorIndex = Math.max(0, startAuthorIndex - 5);
                this.init();
            }
        }).setTooltips("gui.yes_steve_model.pre_page"));
        addRenderableWidget(new FlatColorButton(x + 25 + 75 * 5, y + 25, 18, 100, Component.literal(">"), (b) -> {
            if (startAuthorIndex + 5 < authors.size()) {
                startAuthorIndex = startAuthorIndex + 5;
                this.init();
            }
        }).setTooltips("gui.yes_steve_model.next_page"));

        int y = this.y + 150;
        for (var i = 0; i < Math.min(metadata.links().size(), 2); i++) {
            var link = metadata.links().get(i);
            var type = link.key();
            var value = link.value_();

            var displayText = LINK_TYPE_PRESET.get(type);
            if (displayText == null) {
                displayText = Component.literal(type);
            }

            addRenderableWidget(new FlatColorButton(this.x + 310, y, 85, 20, displayText, b -> openUrl(value)));
            y += 25;
        }
        addRenderableWidget(new FlatColorButton(this.x + 310, y, 85, 20,
            Component.translatable("gui.yes_steve_model.model.return"), (b) -> {
                showScreen(parent);
            }));
    }

    @Override
    public void tick() {
        if (hasCurrentWorld() && (pageScope == null || !pageScope.matches(pageIdentity())))
            init();
    }

    private List<?> pageIdentity() {
        var content = ClientModelService.instance().catalog().find(model.modelHash())
            .map(entry -> (Object)entry.content().representation().identity()).orElse("missing");
        return List.of(model.modelHash(), content, startAuthorIndex);
    }

    private void openUrl(@Nullable String homeUrl) {
        if (homeUrl != null && StringUtils.isNoneBlank(homeUrl)) {
            showScreen(new ConfirmLinkScreen(yes -> {
                if (!hasCurrentWorld())
                    return;
                if (yes) {
                    Util.getPlatform().openUri(homeUrl);
                }
                showScreen(this);
            }, homeUrl, true));
        }
    }

    private void closePage() {
        if (pageScope != null)
            pageScope.close();
        pageScope = null;
        pageAssets = null;
        avatars.clear();
        authorButtons.clear();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        graphics.fillGradient(this.x + 25, this.y + 150, this.x + 305, this.y + 220, 0x8F_5B5B5B, 0x8F_5B5B5B);

        var metadata = this.modelInfo.getMetadata();
        if (metadata != null) {
            String tips = LanguageManager.getI18n(model, "metadata.tips", metadata.tips().orElse(""));
            List<FormattedCharSequence> splitDesc = font.split(Component.literal(tips), 270);
            int offset = 0;
            for (FormattedCharSequence desc : splitDesc) {
                graphics.drawString(font, desc, this.x + 30, this.y + 154 + offset, 0xFFFFFFFF);
                offset += font.lineHeight;
                if (offset > font.lineHeight * 7) {
                    break;
                }
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        this.renderables.stream().filter(r -> r instanceof AuthorButton)
            .forEach(r -> ((AuthorButton)r).renderToolTip(graphics, this, mouseX, mouseY));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
