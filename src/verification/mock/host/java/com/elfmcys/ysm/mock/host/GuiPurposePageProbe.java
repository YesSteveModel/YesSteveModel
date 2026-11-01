package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.gui.PlayerModelScreen;
import com.elfmcys.ysm.client.gui.PlayerTextureScreen;
import com.elfmcys.ysm.client.gui.button.CatalogModelButton;
import com.elfmcys.ysm.client.gui.button.CatalogTextureButton;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.service.ClientModelService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;

/** Drives normal screen input and observes production installation results. */
final class GuiPurposePageProbe {
    private final Minecraft minecraft;
    private final HostAction action;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final List<Map<String, Object>> installations = new ArrayList<>();
    private final List<Map<String, Object>> rejectedEntries = new ArrayList<>();
    private final List<Map<String, Object>> animationFrames = new ArrayList<>();
    private PlayerModelScreen models;
    private PlayerTextureScreen textures;
    private CatalogModelButton card;
    private CustomGuiPlayerEntity center;
    private int stage;
    private int ticks;
    private int stageAt;
    private boolean waitingObserved;
    private boolean waitingCaptured;
    private boolean waitingClickIgnored;
    private boolean privateSetQueued;
    private boolean centerReadQueued;
    private boolean privateMutationQueued;
    private volatile String playerPrivate;
    private volatile String centerInitialPrivate;
    private volatile String centerMutatedPrivate;
    private volatile String playerUnchangedPrivate;
    private float rootBefore;
    private int rejectionsAt;
    private long lastFrameNanos;

    GuiPurposePageProbe(Minecraft minecraft, HostAction action) {
        this.minecraft = minecraft;
        this.action = action;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    Map<String, ?> poll() throws Exception {
        if (++ticks > 1200) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this);
            GuiEntryObservation.finish();
            throw new IllegalStateException("GUI purpose observation timed out at stage " + stage);
        }
        var service = ClientModelService.instance();
        if (service.resolvePath(action.require("path")).isEmpty())
            return null;
        var player = minecraft.player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .orElseThrow(IllegalStateException::new);
        if (stage > 0 && stage < 5) {
            var feedback = service.demands().feedback();
            installations.add(Map.of("tick", ticks, "waiting", feedback.waiting(), "relation",
                player.getInstallationRelation().name(), "installed", String.valueOf(player.getInstalledModelId())));
            waitingObserved |= feedback.waiting();
            if (ticks % 20 == 0)
                java.nio.file.Files.writeString(Path.of(action.require("output")).resolve("installations.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(installations));
        }
        if (stage == 0) {
            models = new PlayerModelScreen();
            minecraft.setScreen(models);
            stage = 1;
            stageAt = ticks;
            return null;
        }
        if (stage == 1) {
            if (ticks - stageAt < 20)
                return null;
            var search = (EditBox)field(models, "textField");
            search.setFocused(true);
            for (var character : action.require("path").toCharArray())
                models.charTyped(character, 0);
            stage = 2;
            stageAt = ticks;
            return null;
        }
        if (stage == 2) {
            if (ticks - stageAt < 5)
                return null;
            var cards = (List<?>)field(models, "modelButtons");
            if (cards.size() != 1)
                throw new IllegalStateException("Expected one filtered fixture card, got " + cards.size());
            card = (CatalogModelButton)cards.get(0);
            rejectEntry("missing");
            result.put("missingClickIgnored", true);
            click(card, 0);
            pointerAt(5, 5);
            stage = 3;
            stageAt = ticks;
            return null;
        }
        if (stage == 3) {
            if (service.demands().feedback().waiting() && !waitingClickIgnored) {
                rejectEntry("selection-pending");
                waitingClickIgnored = true;
                pointerAt(5, 5);
            }
            if (service.demands().feedback().waiting() && !waitingCaptured && ticks - stageAt >= 2
                && (net.minecraft.Util.getMillis() / 250L) % 4L != 0) {
                screenshot("selection-waiting");
                waitingCaptured = true;
            }
            if (!player.hasMatchingInstallation() || !card.modelHash().equals(player.getModelRenderTarget().modelHash())
                || service.demands().feedback().waiting())
                return null;
            result.put("matchingTick", ticks);
            require(minecraft.screen == models, "A rejected click retained a late navigation intent");
            screenshot("selection-matching");
            // Keep the pointer on the card until its independent GUI owner has installed.
            pointer(card);
            if (card.renderTarget() == null)
                return null;
            var gui = (CustomGuiPlayerEntity)field(card, "entity");
            require(gui.assignment().request().purpose() == ModelResourcePurpose.GUI, "Card did not consume GUI");
            require(gui.displayedModel().expressions().identity().purpose() == ModelResourcePurpose.GUI,
                "Card expressions were restored from FULL");
            result.put("cardPurpose", gui.assignment().request().purpose());
            result.put("cardAnimations", java.util.Set.copyOf(gui.displayedModel().playerResources().animations().keySet()));
            if (rejectionsAt == 0) {
                var state = field(card, "state");
                var original = field(state, "target");
                try {
                    GuiEntryObservation.set(state, "target", new com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Target(
                        card.modelHash(), "alternate"));
                    rejectEntry("texture-mismatch");
                } finally {
                    GuiEntryObservation.set(state, "target", original);
                }
                rejectEntry("permission");
                rejectionsAt = ticks;
                pointerAt(5, 5);
                return null;
            }
            if (ticks - rejectionsAt < 5)
                return null;
            require(minecraft.screen == models, "A rejected receiver retained a late navigation intent");
            result.put("rejectedEntries", rejectedEntries);
            result.put("noLateNavigationAfterRejections", true);
            if (!privateSetQueued) {
                privateSetQueued = true;
                player.executeTransient("v.gui_probe=123;return v.gui_probe;",
                    com.elfmcys.ysm.expression.aot.TransientExpressionSession.Privilege.LOCAL_EXECUTE,
                    true, value -> playerPrivate = value);
                return null;
            }
            if (playerPrivate == null)
                return null;
            require(Float.parseFloat(playerPrivate) == 123f, "Player private variable input failed: " + playerPrivate);
            click(card, 1);
            require(minecraft.screen instanceof PlayerTextureScreen, "FULL Ready click did not open texture page");
            textures = (PlayerTextureScreen)minecraft.screen;
            center = (CustomGuiPlayerEntity)field(textures, "previewEntity");
            require(center.assignment().request().purpose() == ModelResourcePurpose.FULL,
                "Central owner did not consume FULL");
            require(center.getExpressionRuntime() != player.getExpressionRuntime(), "Expression runtime was shared");
            result.put("centerPurpose", center.assignment().request().purpose());
            result.put("independentRuntime", true);
            stage = 4;
            stageAt = ticks;
            return null;
        }
        if (stage == 4) {
            if (ticks - stageAt < 10)
                return null;
            if (!centerReadQueued) {
                centerReadQueued = true;
                center.executeTransient("v.gui_probe;",
                    com.elfmcys.ysm.expression.aot.TransientExpressionSession.Privilege.LOCAL_EXECUTE,
                    true, value -> centerInitialPrivate = value);
                return null;
            }
            if (centerInitialPrivate == null)
                return null;
            require(Float.parseFloat(centerInitialPrivate) == 0f,
                "Central private state inherited player data: " + centerInitialPrivate);
            var animations = (List<?>)field(textures, "animations");
            require(animations.contains("trace"), "Non-GUI animation is absent from the FULL browser");
            require(!((java.util.Set<?>)result.get("cardAnimations")).contains("trace"),
                "Fixture trace unexpectedly belongs to the GUI closure");
            // The page creates eleven animation widgets at a time.
            int index = animations.indexOf("trace");
            int page = index / 11;
            require(page > 0, "Animation pagination fixture is missing");
            var originalCandidates = List.copyOf((List<?>)field(textures, "textureButtons"));
            var originalScope = field(textures, "pageScope");
            while ((int)field(textures, "animationPage") < page) {
                int x = (int)field(textures, "x");
                int y = (int)field(textures, "y");
                int previousPage = (int)field(textures, "animationPage");
                textures.mouseClicked(x + 72, y + 220, 0);
                if ((int)field(textures, "animationPage") <= previousPage)
                    throw new IllegalStateException("Animation page navigation was rejected");
                require(
                    originalCandidates.equals(field(textures, "textureButtons"))
                        && originalScope == field(textures, "pageScope"),
                    "Animation pagination replaced texture candidates or their resource scope");
            }
            int x = (int)field(textures, "x");
            int y = (int)field(textures, "y");
            textures.mouseScrolled(x + 40, y + 100, 1);
            require((int)field(textures, "animationPage") == page - 1, "Animation scroll did not change page");
            textures.mouseScrolled(x + 40, y + 100, -1);
            require(
                (int)field(textures, "animationPage") == page
                    && originalCandidates.equals(field(textures, "textureButtons"))
                    && originalScope == field(textures, "pageScope"),
                "Animation scrolling replaced texture candidates or their resource scope");
            result.put("animationPaginationPreservedTextureCandidates", true);
            rootBefore = center.getAnimationProcessor().getBone(com.elfmcys.ysm.util.StringPool.computeIfAbsent("Root")).getRotationZ();
            EntityLifecycleProbe.clearEvents();
            textures.mouseClicked(x + 10, y + 30 + 17 * (index % 11), 0);
            require("trace".equals(field(textures, "animation")), "Animation click did not select trace");
            stage = 5;
            stageAt = ticks;
            return null;
        }
        if (stage == 5) {
            if (ticks - stageAt < 30 || animationFrames.size() < 16)
                return null;
            var rootAfter = center.getAnimationProcessor()
                .getBone(com.elfmcys.ysm.util.StringPool.computeIfAbsent("Root")).getRotationZ();
            require(Math.abs(rootAfter - rootBefore) > 0.1f, "Non-GUI animation did not change the computed pose");
            result.put("animationPose", Map.of("rootBefore", rootBefore, "rootAfter", rootAfter));
            result.put("animationEvents", EntityLifecycleProbe.events());
            result.put("animationFrames", animationFrames);
            if (!privateMutationQueued) {
                privateMutationQueued = true;
                center.executeTransient("v.gui_probe=777;return v.gui_probe;",
                    com.elfmcys.ysm.expression.aot.TransientExpressionSession.Privilege.LOCAL_EXECUTE,
                    true, value -> centerMutatedPrivate = value);
                player.executeTransient("v.gui_probe;",
                    com.elfmcys.ysm.expression.aot.TransientExpressionSession.Privilege.LOCAL_EXECUTE,
                    true, value -> playerUnchangedPrivate = value);
                return null;
            }
            if (centerMutatedPrivate == null || playerUnchangedPrivate == null)
                return null;
            require(Float.parseFloat(centerMutatedPrivate) == 777f && Float.parseFloat(playerUnchangedPrivate) == 123f,
                "Central private mutation changed the player");
            result.put("privateVariables", Map.of("playerBeforeEntry", playerPrivate,
                "centerAfterEntry", centerInitialPrivate, "centerAfterMutation", centerMutatedPrivate,
                "playerAfterMutation", playerUnchangedPrivate));
            screenshot("full-animation");
            var animationPage = field(textures, "animationPage");
            int x = (int)field(textures, "x");
            int y = (int)field(textures, "y");
            textures.mouseClicked(x + 390, y + 220, 0);
            require((int)field(textures, "texturePage") == 1, "Texture pagination fixture is missing");
            require(
                animationPage.equals(field(textures, "animationPage")) && "trace".equals(field(textures, "animation"))
                    && center == field(textures, "previewEntity"),
                "Texture pagination changed the animation page or central preview");
            textures.mouseScrolled(x + 340, y + 100, 1);
            require(
                (int)field(textures, "texturePage") == 0 && animationPage.equals(field(textures, "animationPage"))
                    && "trace".equals(field(textures, "animation")) && center == field(textures, "previewEntity"),
                "Texture scrolling changed the animation page or central preview");
            result.put("texturePaginationPreservedAnimation", true);
            var candidates = (List<?>)field(textures, "textureButtons");
            require(candidates.size() > 1, "Texture-switch fixture is missing");
            var alternative = candidates.stream().map(CatalogTextureButton.class::cast)
                .filter(button -> button.target().texture().equals("alternate")).findFirst().orElseThrow();
            pointer(alternative);
            stage = 6;
            stageAt = ticks;
            return null;
        }
        var candidates = ((List<?>)field(textures, "textureButtons")).stream()
            .map(CatalogTextureButton.class::cast).toList();
        var alternative = candidates.stream().filter(button -> button.target().texture().equals("alternate"))
            .findFirst().orElseThrow();
        if (stage == 6) {
            var candidate = (CustomGuiPlayerEntity)field(alternative, "entity");
            if (candidate.displayedModel() == null)
                return null;
            require(candidate.assignment().request().purpose() == ModelResourcePurpose.GUI,
                "Texture candidate did not consume GUI");
            result.put("candidatePurpose", candidate.assignment().request().purpose());
            click(alternative, 0);
            stage = 7;
            stageAt = ticks;
            return null;
        }
        if (!center.hasMatchingInstallation() || !center.assignment().request().requestedTexture().equals("alternate")
            || !player.hasMatchingInstallation() || !player.assignment().request().requestedTexture().equals("alternate"))
            return null;
        require(center.assignment().request().purpose() == ModelResourcePurpose.FULL, "Texture switch changed purpose");
        screenshot("full-alternate");
        result.put("switchedTexture", center.assignment().request().requestedTexture());
        result.put("installationEvents", installations);
        result.put("waitingObserved", waitingObserved);
        result.put("waitingFramebuffer", waitingCaptured);
        result.put("waitingClickIgnored", waitingClickIgnored);
        require(waitingClickIgnored && waitingCaptured, "Selection-pending click or framebuffer was not observed");
        require(GuiEntryObservation.preparations.get() > 0 && GuiEntryObservation.networkFrames.get() > 0,
            "Preparation/wire observers did not see the positive selection control");
        result.put("observerControls", Map.of("preparations", GuiEntryObservation.preparations.get(),
            "networkFrames", GuiEntryObservation.networkFrames.get()));
        require(GuiEntryObservation.acceptedRenders > 0 && GuiEntryObservation.rejectedRenders == 0,
            "Native render batches were rejected");
        result.put("boundary", "Real screen clicks with production missing/mismatched Ready lookup, borrowed receiver barrier and permission revocation after receiving. Preparation/wire counters, late-navigation checks, 16 rendered non-GUI animation frames, independent runtimes and FULL texture switch.");
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this);
        textures.onClose();
        minecraft.setScreen(null);
        return result;
    }

    private void click(AbstractWidget widget, int button) throws Exception {
        pointer(widget);
        minecraft.screen.mouseClicked(widget.getX() + 10, widget.getY() + 10, button);
    }

    private void rejectEntry(String condition) throws Exception {
        var preparations = GuiEntryObservation.preparations.get();
        var network = GuiEntryObservation.networkFrames.get();
        GuiEntryObservation.arm(condition);
        Map<String, ?> owner;
        try {
            click(card, 1);
            require(minecraft.screen == models, condition + " opened a texture page");
        } finally {
            owner = GuiEntryObservation.finish();
        }
        var facts = new LinkedHashMap<String, Object>(owner);
        facts.put("condition", condition);
        facts.put("tick", ticks);
        facts.put("preparationsStarted", GuiEntryObservation.preparations.get() - preparations);
        facts.put("networkFramesStarted", GuiEntryObservation.networkFrames.get() - network);
        facts.put("navigated", minecraft.screen != models);
        require(facts.get("preparationsStarted").equals(0) && facts.get("networkFramesStarted").equals(0),
            "Rejected entry started preparation or network: " + facts);
        if (condition.equals("permission"))
            require(owner.get("permissionRevokedAfterReceive").equals(true), "Permission race did not run");
        rejectedEntries.add(facts);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onRender(net.minecraftforge.event.TickEvent.RenderTickEvent event) throws Exception {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || stage != 5 || animationFrames.size() >= 16)
            return;
        var now = System.nanoTime();
        if (now - lastFrameNanos < 75_000_000)
            return;
        lastFrameNanos = now;
        var name = "animation-frame-%02d".formatted(animationFrames.size());
        screenshot(name);
        animationFrames.add(Map.of("file", name + ".png", "nanos", now, "tick", ticks,
            "rootZ", center.getAnimationProcessor().getBone(com.elfmcys.ysm.util.StringPool.computeIfAbsent("Root")).getRotationZ(),
            "x", field(textures, "x"), "y", field(textures, "y"),
            "guiWidth", minecraft.getWindow().getGuiScaledWidth(), "guiHeight", minecraft.getWindow().getGuiScaledHeight(),
            "native", Map.of("accepted", GuiEntryObservation.acceptedRenders, "rejected", GuiEntryObservation.rejectedRenders,
                "vertices", GuiEntryObservation.vertices)));
    }

    private void pointer(AbstractWidget widget) throws Exception {
        pointerAt(widget.getX() + 10, widget.getY() + 10);
    }

    private void pointerAt(double pointerX, double pointerY) throws Exception {
        var window = minecraft.getWindow();
        var x = minecraft.mouseHandler.getClass().getDeclaredField("xpos");
        var y = minecraft.mouseHandler.getClass().getDeclaredField("ypos");
        x.setAccessible(true);
        y.setAccessible(true);
        x.setDouble(minecraft.mouseHandler, pointerX * window.getScreenWidth()
            / (double)window.getGuiScaledWidth());
        y.setDouble(minecraft.mouseHandler, pointerY * window.getScreenHeight()
            / (double)window.getGuiScaledHeight());
        minecraft.screen.mouseMoved(pointerX, pointerY);
    }

    private void screenshot(String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(Path.of(action.require("output")).resolve(name + ".png"));
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }
}
