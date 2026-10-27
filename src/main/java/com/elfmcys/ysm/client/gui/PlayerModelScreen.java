package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.client.event.DownloadScreenInterModEvent;
import com.elfmcys.ysm.client.gui.button.CatalogModelButton;
import com.elfmcys.ysm.client.gui.button.FailedCatalogModelButton;
import com.elfmcys.ysm.client.gui.button.FlatColorButton;
import com.elfmcys.ysm.client.gui.button.FlatIconButton;
import com.elfmcys.ysm.client.gui.button.PackButton;
import com.elfmcys.ysm.client.gui.button.StarButton;
import com.elfmcys.ysm.client.input.PlayerModelScreenKey;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.client.ModelPackInfo;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.asset.PageImages;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.util.ModelIdUtil;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.fml.ModList;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.glfw.GLFW;
import org.jetbrains.annotations.Nullable;

public class PlayerModelScreen extends ModelScreen {
    private final ClientModelService service = ClientModelService.instance();
    private final CatalogBrowserState browser = new CatalogBrowserState();
    private final HashSet<String> clientNotDisplayModels = new HashSet<>();
    private final List<CatalogModelButton> modelButtons = new ArrayList<>();
    private final List<FailedCatalogModelButton> failedButtons = new ArrayList<>();
    private final List<PackButton> packButtons = new ArrayList<>();
    private final Map<String, ModelPackDescriptor> packDescriptors = new LinkedHashMap<>();
    private ClientDemandCoordinator.PageScope pageScope;
    private PageImages pageAssets;
    private final List<net.minecraft.client.gui.components.AbstractWidget> pageWidgets = new ArrayList<>();
    private FlatColorButton textureButton;
    private EditBox textField;
    protected int x;
    protected int y;

    public PlayerModelScreen() {
        super(Component.literal("YSM Player Model GUI"));
        ClientSessionRuntime.reopenCatalog();
        if (NetworkHandler.isRemoteChannelPresent()) {
            clientNotDisplayModels.addAll(ServerConfig.CLIENT_NOT_DISPLAY_MODEL_PATHS.get());
        }
        rebuildCatalog(service.catalog());
    }

    protected PlayerTextureScreen getTextureScreen(ModelRenderTarget renderTarget) {
        return new PlayerTextureScreen(this, renderTarget);
    }

    protected ModelInfoScreen getModelInfoScreen(PlayerModelScreen parent, ModelRenderTarget model) {
        return new ModelInfoScreen(parent, model);
    }

    protected void selectModel(Hash256 hash, String path, String texture, ModelRenderTarget renderTarget) {
        if (!hasCurrentWorld())
            return;
        ClientProtocolGateway.selectModel(hash, texture);
    }

    protected boolean followsPlayerSelection() {
        return true;
    }

    protected @Nullable ClientDemandCoordinator.Target currentTextureTarget() {
        return service.demands().authority();
    }

    protected boolean canOpenTexturePage() {
        if (!hasCurrentWorld())
            return false;
        if (!followsPlayerSelection())
            return true;
        return service.demands().canOpenTextures();
    }

    protected ResourceRequest textureRequest(ClientDemandCoordinator.Target target) {
        return target.modelId() == null
            ? service.defaultResourceRequest("player", ModelResourcePurpose.FULL, null)
            : service.resourceRequest(target.modelId(), "player", target.texture(), ModelResourcePurpose.FULL,
                null);
    }

    protected @Nullable ResourceLease findTextureReady(ResourceRequest request) {
        return service.findReady(request).orElse(null);
    }

    protected boolean permitsTextureRequest(ResourceRequest request) {
        return service.permitsAcquisition(request);
    }

    protected final void openTexturePage(ClientDemandCoordinator.Target target) {
        if (!canOpenTexturePage())
            return;
        ResourceLease lease = null;
        PlayerTextureScreen next = null;
        try {
            var request = textureRequest(target);
            lease = findTextureReady(request);
            if (lease == null || lease.purpose() != ModelResourcePurpose.FULL || !lease.isCurrent(request)
                || !(lease.poll() instanceof AcquireResult.Ready ready))
                return;
            next = getTextureScreen(ready.target());
            if (next == null || !canOpenTexturePage() || !next.receiveReady(request, lease))
                return;
            var received = lease;
            lease = null;
            if (!canOpenTexturePage() || !permitsTextureRequest(request) || !received.isCurrent(request))
                return;
            if (showTextureScreen(next))
                next = null;
        } catch (RuntimeException failure) {
            YesSteveModel.LOGGER.debug("Texture page entry was rejected", failure);
        } finally {
            if (lease != null)
                lease.close();
            if (next != null)
                next.releaseResources();
        }
    }

    protected boolean showTextureScreen(PlayerTextureScreen screen) {
        showScreen(screen);
        return Minecraft.getInstance().screen == screen;
    }

    @Override
    protected void init() {
        if (!hasCurrentWorld())
            return;
        var previousFocus = getFocused();
        var searchFocused = textField != null && textField.isFocused();
        clearWidgets();
        if (browser.catalog() != service.catalog()) {
            rebuildCatalog(service.catalog());
        }
        calculateModelList();

        x = (width - 420) / 2;
        y = (height - 235) / 2;
        var previousSearch = textField == null ? "" : textField.getValue();
        var focused = searchFocused;
        textField = new EditBox(font, x + 144, y + 6, 140, 16, Component.literal("YSM Search Box"));
        textField.setValue(previousSearch);
        textField.setTextColor(0xF3EFE0);
        textField.setFocused(focused);
        textField.moveCursorToEnd();
        addWidget(textField);

        addHeaderButtons();
        addPageButtons();
        var signature = pageSignature();
        if (pageScope != null && pageScope.matches(signature)) {
            for (var slot = 0; slot < pageWidgets.size(); slot++) {
                var widget = pageWidgets.get(slot);
                widget.setX(x + 143 + 55 * (slot % 5));
                widget.setY(y + 28 + 93 * (slot / 5));
                addRenderableWidget(widget);
            }
            if (pageWidgets.contains(previousFocus))
                setFocused(previousFocus);
        } else {
            var previous = pageScope;
            pageScope = service.demands().replacePage(previous, signature);
            closePageWidgets();
            pageAssets = new PageImages(service.createAssetBatch());
            pageScope.action(pageAssets);
            addCatalogButtons(pageAssets);
        }
        if (searchFocused)
            setFocused(textField);
    }

    private net.minecraft.client.gui.components.Button.OnPress textureNavigationAction() {
        return ignored -> {
            if (!canOpenTexturePage())
                return;
            var target = currentTextureTarget();
            if (target != null)
                openTexturePage(target);
        };
    }

    private void addHeaderButtons() {
        addRenderableWidget(new FlatIconButton(x + 5, y + 5, 20, 20, 80, 16, ignored -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
                    var model = capability.getModelRenderTarget();
                    if (model != null && model.info().hasMetadata()) {
                        showScreen(getModelInfoScreen(this, model));
                    }
                });
            }
        })).setTooltips("gui.yes_steve_model.model.info");
        textureButton =
            addRenderableWidget(new FlatIconButton(x + 28, y + 5, 79, 20, 32, 16, textureNavigationAction()))
                .setTooltips("gui.yes_steve_model.model.texture");
        addRenderableWidget(new StarButton(x + 110, y + 5));

        if (StringUtils.isNotBlank(browser.currentPack())) {
            addRenderableWidget(
                new FlatIconButton(x + 110, y + 27, 20, 20, 0, 32, ignored -> backToParent()).setTooltips("gui.back"));
        }

        addRenderableWidget(
            new Checkbox(x + 5, y - 22, 20, 20, Component.translatable("gui.yes_steve_model.show_model_id_first"),
                ClientConfig.SHOW_MODEL_ID_FIRST.get(), true) {
                @Override
                public void onPress() {
                    super.onPress();
                    ClientConfig.SHOW_MODEL_ID_FIRST.set(selected());
                    ClientConfig.SHOW_MODEL_ID_FIRST.save();
                }
            });

        addCategoryButton(x + 328, 32, CatalogBrowserState.Category.ALL, "gui.yes_steve_model.all_models");
        addCategoryButton(x + 308, 48, CatalogBrowserState.Category.AUTH, "gui.yes_steve_model.auth_models");
        addCategoryButton(x + 288, 0, CatalogBrowserState.Category.STAR, "gui.yes_steve_model.star_models");
        addRenderableWidget(
            new FlatIconButton(x + 397, y + 5, 18, 18, 16, 16, ignored -> showScreen(new ConfigScreen(this)))
                .setTooltips("gui.yes_steve_model.config"));
        addRenderableWidget(new FlatIconButton(x + 377, y + 5, 18, 18, 0, 16,
            ignored -> DownloadScreenInterModEvent.openDownloadScreen(this))
            .setTooltips("gui.yes_steve_model.download"));
        addRenderableWidget(
            new FlatIconButton(x + 357, y + 5, 18, 18, 80, 0, ignored -> showScreen(new OpenModelFolderScreen(this)))
                .setTooltips("gui.yes_steve_model.open_model_folder.open"));
    }

    private void addCategoryButton(int buttonX, int u, CatalogBrowserState.Category target, String tooltip) {
        addRenderableWidget(new FlatIconButton(buttonX, y + 5, 18, 18, u, 0, ignored -> {
            if (browser.category() != target) {
                browser.category(target);
                init();
            }
        }).setTooltips(tooltip));
    }

    private void addPageButtons() {
        addRenderableWidget(new FlatColorButton(x + 198, y + 215, 52, 14,
            Component.translatable("gui.yes_steve_model.pre_page"), ignored -> {
                if (browser.page() > 0) {
                    browser.page(browser.page() - 1);
                    init();
                }
            }));
        addRenderableWidget(new FlatColorButton(x + 308, y + 215, 52, 14,
            Component.translatable("gui.yes_steve_model.next_page"), ignored -> {
                if (browser.page() < browser.maxPage()) {
                    browser.page(browser.page() + 1);
                    init();
                }
            }));
    }

    private void addCatalogButtons(PageImages assets) {
        var player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return;
        }
        for (var slot = 0; slot < 10; slot++) {
            var index = slot + browser.page() * 10;
            var xStart = x + 143 + 55 * (slot % 5);
            var yStart = y + 28 + 93 * (slot / 5);
            if (index < browser.packs().size()) {
                var pack = browser.packs().get(index);
                var button = new PackButton(xStart, yStart, 52, 90, pack, packDescriptors.get(pack.hierarchy()), assets,
                    ignored -> {
                        browser.enterPack(pack.hierarchy());
                        init();
                    });
                packButtons.add(button);
                pageWidgets.add(button);
                addRenderableWidget(button);
                continue;
            }
            index -= browser.packs().size();
            if (index >= 0 && index < browser.models().size()) {
                var entry = browser.models().get(index);
                var button = new CatalogModelButton(xStart, yStart, entry, () -> requiresAuthorization(entry), assets,
                    pageScope, new CustomGuiPlayerEntity(), this::selectModel, this::openTexturePage,
                    this::canOpenTexturePage);
                modelButtons.add(button);
                pageWidgets.add(button);
                addRenderableWidget(button);
                continue;
            }
            index -= browser.models().size();
            if (index >= 0 && index < browser.failed().size()) {
                var button = new FailedCatalogModelButton(xStart, yStart, browser.failed().get(index));
                failedButtons.add(button);
                pageWidgets.add(button);
                addRenderableWidget(button);
            }
        }
    }

    private boolean requiresAuthorization(com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry entry) {
        if (!entry.authorizationRequired())
            return false;
        var player = minecraft == null ? null : minecraft.player;
        var auth =
            player == null ? null : player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).orElse(null);
        return ClientSessionRuntime.authoritativeGrants().map(grants -> !grants.contains(entry.modelHash()))
            .orElseGet(() -> auth == null || !auth.containModel(entry.modelHash()));
    }

    private void calculateModelList() {
        var player = minecraft == null ? null : minecraft.player;
        var auth = player == null ? null
            : player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).resolve().orElse(null);
        var sessionGrants = ClientSessionRuntime.authoritativeGrants();
        var stars = player == null ? null
            : player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP).resolve().orElse(null);
        browser.filter(textField == null ? "" : textField.getValue(), locale(), clientNotDisplayModels,
            hash -> sessionGrants.map(grants -> grants.contains(hash))
                .orElseGet(() -> auth != null && auth.containModel(hash)),
            hash -> stars != null && stars.containModel(hash));
    }

    private void rebuildCatalog(ClientCatalogSnapshot next) {
        packDescriptors.clear();
        next.packs().forEach(pack -> packDescriptors.putIfAbsent(pack.hierarchy(), pack));
        browser.rebuild(next, this::packInfo);
    }

    private ModelPackInfo packInfo(ModelPackDescriptor descriptor) {
        var languages = new LinkedHashMap<String, Map<String, String>>();
        descriptor.translations().forEach(
            (locale, text) -> languages.put(locale, Map.of("name", text.name(), "description", text.description())));
        return new ModelPackInfo(descriptor.hierarchy(), descriptor.name(), descriptor.description(), null,
            Map.copyOf(languages));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!hasCurrentWorld())
            return;
        updatePointer(mouseX, mouseY);
        if (textureButton != null)
            textureButton.active = canOpenTexturePage();
        renderBackground(graphics);
        graphics.fillGradient(x, y, x + 135, y + 235, 0xFF222222, 0xFF222222);
        graphics.fillGradient(x + 138, y, x + 420, y + 235, 0xFF222222, 0xFF222222);
        graphics.fillGradient(x + 351, y + 7, x + 352, y + 21, 0xFFF3EFE0, 0xFFF3EFE0);
        textField.render(graphics, mouseX, mouseY, partialTick);
        renderReferenceEntity(graphics, mouseX, mouseY, minecraft.getFrameTime());

        if (textField.getValue().isEmpty() && !textField.isFocused()) {
            graphics.drawString(font,
                Component.translatable("gui.yes_steve_model.search").withStyle(ChatFormatting.ITALIC), x + 148, y + 10,
                0x777777);
        }
        var page = "%d/%d".formatted(browser.page() + 1, browser.maxPage() + 1);
        graphics.drawString(font, page, x + 138 + (282 - font.width(page)) / 2, y + 223 - font.lineHeight / 2,
            0xF3EFE0);
        var version = ModList.get().getModFileById(YesSteveModel.MOD_ID).versionString();
        graphics.drawString(font, version, x + 2, y + 226, ChatFormatting.DARK_GRAY.getColor());
        if (!browser.currentPack().isBlank()) {
            graphics.drawString(font,
                Component
                    .literal("\uD83D\uDCC2 "
                        + com.elfmcys.ysm.model.catalog.source.CatalogLocation.directory(browser.currentPack()).entry())
                    .withStyle(ChatFormatting.GRAY),
                x + 142, y - 12, 0xF3EFE0);
        }
        var progress = service.catalogProgress();
        if (progress != null) {
            var loading = CatalogProgressText.of(progress);
            graphics.drawString(font, loading, x + 414 - font.width(loading),
                y + 215 + Math.round((14 - font.lineHeight) / 2f), ChatFormatting.DARK_GRAY.getColor());
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        renderables.stream().filter(FlatIconButton.class::isInstance).map(FlatIconButton.class::cast)
            .forEach(button -> button.renderToolTip(graphics, this, mouseX, mouseY));
        modelButtons.forEach(button -> button.renderTooltip(graphics, this, mouseX, mouseY));
        failedButtons.forEach(button -> button.renderTooltip(graphics, this, mouseX, mouseY));
        renderables.stream().filter(PackButton.class::isInstance).map(PackButton.class::cast)
            .forEach(button -> button.renderComponentTooltip(graphics, this, mouseX, mouseY));
    }

    protected void renderReferenceEntity(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        RenderSystem.enableScissor((int)((x + 5) * scale), (int)(window.getHeight() - ((y + 200) * scale)),
            (int)(125 * scale), (int)(171 * scale));
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x + 67, y + 190, 70, x + 67 - mouseX,
            y + 85 - mouseY, player);
        RenderSystem.disableScissor();

        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
            var renderTarget = capability.getModelRenderTarget();
            var fallback =
                capability.getModelHash() == null ? "default" : service.displayPath(capability.getModelHash());
            var name = renderTarget == null ? ModelIdUtil.getFileNameFromPath(fallback)
                : renderTarget.getDisplayName(ModelIdUtil.getFileNameFromPath(fallback));
            if (service.demands().feedback().waiting())
                name = ".".repeat((int)((Util.getMillis() / 250L) % 4L));
            var lines = font.split(FormattedText.of(name), 125);
            var lineY = y + 205;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, x + (135 - font.width(line)) / 2, lineY, 0xF3EFE0);
                lineY += 10;
            }
        });
    }

    @Override
    public void tick() {
        if (!hasCurrentWorld())
            return;
        textField.tick();
        if (browser.catalog() != service.catalog()) {
            rebuildCatalog(service.catalog());
            calculateModelList();
        }
        if (pageScope == null || !pageScope.matches(pageSignature()))
            init();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        updatePointer(mouseX, mouseY);
        if (textField.mouseClicked(mouseX, mouseY, button)) {
            setFocused(textField);
            return true;
        }
        if (textField.isFocused()) {
            textField.setFocused(false);
        }
        var handled = super.mouseClicked(mouseX, mouseY, button);
        if (!handled && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !browser.currentPack().isBlank()) {
            getMinecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
            backToParent();
            return true;
        }
        return handled;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        var previous = textField.getValue();
        if (textField.charTyped(codePoint, modifiers)) {
            if (!Objects.equals(previous, textField.getValue())) {
                browser.resetPage();
                init();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (PlayerModelScreenKey.PLAYER_MODEL_KEY.matches(keyCode, scanCode) && !textField.isFocused()) {
            onClose();
            return true;
        }
        var previous = textField.getValue();
        if (textField.keyPressed(keyCode, scanCode, modifiers)) {
            if (!Objects.equals(previous, textField.getValue())) {
                browser.resetPage();
                init();
            }
            return true;
        }
        var handled = textField.isFocused() && textField.isVisible() && keyCode != GLFW.GLFW_KEY_ESCAPE
            && keyCode != GLFW.GLFW_KEY_TAB || super.keyPressed(keyCode, scanCode, modifiers);
        updateKeyboardPointer();
        return handled;
    }

    @Override
    protected void insertText(String text, boolean overwrite) {
        if (overwrite) {
            textField.setValue(text);
        } else {
            textField.insertText(text);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        updatePointer(mouseX, mouseY);
        if (delta != 0 && mouseX > x + 143 && mouseX < x + 430 && mouseY > y + 25 && mouseY < y + 235) {
            if (delta > 0 && browser.page() > 0) {
                browser.page(browser.page() - 1);
                init();
            } else if (delta < 0 && browser.page() < browser.maxPage()) {
                browser.page(browser.page() + 1);
                init();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        var search = textField == null ? "" : textField.getValue();
        var previousFocus = getFocused();
        var searchFocused = textField != null && textField.isFocused();
        var previousPage = pageScope;
        super.resize(minecraft, width, height);
        textField.setValue(search);
        if (searchFocused)
            setFocused(textField);
        else if (pageScope == previousPage && pageWidgets.contains(previousFocus))
            setFocused(previousFocus);
    }

    @Override
    public void removed() {
        closePage();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void closeModelButtons() {
        modelButtons.forEach(CatalogModelButton::close);
        modelButtons.clear();
        failedButtons.clear();
    }

    private void closePageWidgets() {
        closeModelButtons();
        packButtons.forEach(PackButton::close);
        packButtons.clear();
        pageWidgets.clear();
    }

    private void closePage() {
        if (pageScope != null)
            pageScope.close();
        pageScope = null;
        pageAssets = null;
        closePageWidgets();
    }

    private void backToParent() {
        browser.backToParent();
        init();
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        updatePointer(mouseX, mouseY);
    }

    private void updateKeyboardPointer() {
        if (pageScope != null)
            pageScope.pointer(getFocused() instanceof CatalogModelButton card ? card.target() : null);
    }

    private void updatePointer(double mouseX, double mouseY) {
        if (minecraft != null && minecraft.getLastInputType().isKeyboard()) {
            updateKeyboardPointer();
        } else if (pageScope != null) {
            pageScope.pointer(modelButtons.stream().filter(button -> button.isMouseOver(mouseX, mouseY))
                .map(CatalogModelButton::target).findFirst().orElse(null));
        }
    }

    private List<String> pageSignature() {
        var result = new ArrayList<String>(10);
        for (var slot = 0; slot < 10; slot++) {
            var index = slot + browser.page() * 10;
            if (index < browser.packs().size()) {
                var pack = browser.packs().get(index);
                var descriptor = packDescriptors.get(pack.hierarchy());
                result.add("pack:" + pack.hierarchy() + ":" + pack.name() + ":" + pack.desc() + ":" + pack.lang() + ":"
                    + (descriptor == null ? "synthetic" : descriptor.rootKind() + ":" + descriptor.coverSize() + ":"
                        + Objects.hashCode(descriptor.coverHash())));
                continue;
            }
            index -= browser.packs().size();
            if (index >= 0 && index < browser.models().size()) {
                var entry = browser.models().get(index);
                result.add("model:" + entry.modelHash() + ":" + entry.content().representation().identity() + ":"
                    + entry.entry() + ":" + entry.origin());
                continue;
            }
            index -= browser.models().size();
            if (index >= 0 && index < browser.failed().size()) {
                result.add("failed:" + browser.failed().get(index));
            } else {
                result.add("empty");
            }
        }
        return List.copyOf(result);
    }

    private String locale() {
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

}
