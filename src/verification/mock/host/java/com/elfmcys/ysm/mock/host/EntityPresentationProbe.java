package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.util.RenderUtil;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Observes the host's final cancellation point without replacing render or binding code. */
public final class EntityPresentationProbe {
    private static final Map<String, int[]> COUNTS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> FIRST = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> FIRST_REQUESTED = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> JOINT = new LinkedHashMap<>();
    private static PlayerAnimatableCapability previousOwner;
    private static String hook = "none";
    private static int hookCalls;
    private static float[] preparedAttributes;
    private static com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel preparedModel;
    private static Map<String, Object> preparationFailure = Map.of();
    private static com.elfmcys.ysm.client.entity.FirstPersonPart renderingPart;
    private static com.elfmcys.ysm.natives.render.NativeModelState invalidatedArm;
    private static Object invalidatedView;
    private static int eventsBeforeReextract;
    private static Map<String, Object> armReextract = Map.of();
    private final Minecraft minecraft;
    private final HostAction action;
    private final Map<String, int[]> before = new LinkedHashMap<>();
    private int ticks;
    private float seekBefore;
    private int inputTick;
    private int originalTick;
    private final java.util.List<EntityUseProbe> uses = new java.util.ArrayList<>();

    EntityPresentationProbe(Minecraft minecraft, HostAction action) {
        this.minecraft = minecraft;
        this.action = action;
    }

    static void register() {
        MinecraftForge.EVENT_BUS.register(EntityPresentationProbe.class);
        MinecraftForge.EVENT_BUS.register(EntityDemandProbe.class);
    }

    static void beginObservation() throws Exception {
        FIRST.clear();
        FIRST_REQUESTED.clear();
        JOINT.clear();
        observeRenderer();
    }

    static Map<String, Object> observations() {
        return Map.of("firstHostOpportunities", Map.copyOf(FIRST), "firstRequestedOpportunities",
            Map.copyOf(FIRST_REQUESTED), "jointDraws", java.util.List.copyOf(JOINT.values()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void body(RenderPlayerEvent.Pre event) {
        var context = RenderUtil.extractRenderContext();
        var scope = context.level() ? "world"
            : context.paperDoll() ? "paper-doll" : context.inventory() ? "inventory" : "other";
        var key = "body:" + scope + ":" + event.getEntity().getScoreboardName();
        count(key, event.isCanceled());
        var observed = facts(event.getEntity().getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .orElseThrow(IllegalStateException::new));
        FIRST.putIfAbsent(key, observed);
        joint(key, event.isCanceled(), observed);
        if (preparedModel != null && event.getEntity() == Minecraft.getInstance().player) {
            try {
                var owner = event.getEntity().getCapability(PlayerAnimatableCapabilityProvider.CAP)
                    .orElseThrow(IllegalStateException::new);
                var output = com.elfmcys.ysm.client.entity.AnimatableEntity.class.getDeclaredField("mainRenderData");
                output.setAccessible(true);
                var data = (com.elfmcys.ysm.geckolib3.geo.GeoRenderData)output.get(owner);
                preparationFailure = Map.of("installed", String.valueOf(owner.getInstalledModelId()),
                    "originalAttributeCount", preparedAttributes.length, "suppliedAttributeCount", 0, "preparedValid",
                    data != null && data.modelState.isValid(), "hostCancelled", event.isCanceled());
                var attributes = preparedModel.getClass().getDeclaredField("boneAttributes");
                attributes.setAccessible(true);
                attributes.set(preparedModel, preparedAttributes);
                preparedModel = null;
                preparedAttributes = null;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeBody(RenderPlayerEvent.Pre event) {
        if (!hook.equals("prepare-failure") || event.getEntity() != Minecraft.getInstance().player)
            return;
        var owner = event.getEntity().getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .orElseThrow(IllegalStateException::new);
        if (!owner.hasMatchingInstallation())
            return;
        try {
            owner.waitForAsyncUpdate();
            preparedModel = owner.getLoadedGeoModel();
            preparedAttributes = preparedModel.getBoneAttributes();
            var attributes = preparedModel.getClass().getDeclaredField("boneAttributes");
            attributes.setAccessible(true);
            attributes.set(preparedModel, new float[0]);
            var extracted =
                com.elfmcys.ysm.client.entity.AnimatableEntity.class.getDeclaredField("currentFrameExtracted");
            extracted.setAccessible(true);
            extracted.setBoolean(owner, false);
            hookCalls++;
            hook = "none";
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void livingHook(net.minecraftforge.client.event.RenderLivingEvent.Pre<?, ?> event) {
        if (hook.equals("living-hide") && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanceled(true);
            hookCalls++;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void arm(RenderArmEvent event) {
        var key = "arm:" + event.getArm();
        count(key, event.isCanceled());
        FIRST.computeIfAbsent(key, ignored -> facts(event.getPlayer()
            .getCapability(PlayerAnimatableCapabilityProvider.CAP).orElseThrow(IllegalStateException::new)));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void hook(com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent event) {
        if (event.getPlayer() != Minecraft.getInstance().player || hook.equals("none"))
            return;
        hookCalls++;
        if (hook.equals("invalidate-arm")) {
            if (renderingPart == null || renderingPart == com.elfmcys.ysm.client.entity.FirstPersonPart.BACKGROUND)
                return;
            try {
                var renderer = com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
                var ownerField =
                    com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer.class.getDeclaredField("armEntity");
                ownerField.setAccessible(true);
                var arm = ownerField.get(renderer);
                var stateField =
                    com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity.class.getDeclaredField("firstPersonState");
                stateField.setAccessible(true);
                var state = stateField.get(arm);
                var outputsField = state.getClass().getDeclaredField("outputs");
                outputsField.setAccessible(true);
                var output = ((com.elfmcys.ysm.natives.render.NativeModelState[])outputsField.get(state))[renderingPart
                    .ordinal()];
                if (output == null || !output.isValid())
                    return;
                invalidatedArm = output;
                invalidatedView = output.getBonePoses();
                eventsBeforeReextract = EntityLifecycleProbe.events().size();
                output.invalidate();
                hook = "none";
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        } else if (hook.equals("hide"))
            event.setCanceled(true);
        else if (hook.equals("retire")) {
            event.getCustomPlayer().retire();
            hook = "none";
        } else if (hook.equals("invalidate")) {
            try {
                var type = com.elfmcys.ysm.client.entity.AnimatableEntity.class;
                for (var name : new String[] {"mainRenderData", "renderDataPool"}) {
                    var field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    var value = field.get(event.getCustomPlayer());
                    if (value instanceof com.elfmcys.ysm.geckolib3.geo.GeoRenderData data)
                        data.modelState.getNativeState().invalidate();
                    else if (value instanceof Map<?, ?> pool)
                        for (var item : pool.values())
                            ((com.elfmcys.ysm.geckolib3.geo.GeoRenderData)item).modelState.getNativeState()
                                .invalidate();
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            hook = "none";
        } else if (hook.equals("select")) {
            var id =
                com.elfmcys.ysm.model.service.ClientModelService.instance().resolvePath("entity/other").orElseThrow();
            event.getCustomPlayer().updateModelAndTexture(id, "gsl");
            hook = "none";
        }
    }

    private static void count(String key, boolean canceled) {
        COUNTS.computeIfAbsent(key, ignored -> new int[2])[canceled ? 1 : 0]++;
    }

    private static void joint(String channel, boolean consumed, Map<String, Object> facts) {
        var first = new LinkedHashMap<String, Object>(facts);
        first.put("consumed", consumed);
        FIRST_REQUESTED.putIfAbsent(channel + ":" + facts.get("requested") + ":" + facts.get("requestedTexture"),
            first);
        var key = channel + ":" + consumed + ":" + facts;
        var existing = JOINT.get(key);
        if (existing == null) {
            existing = new LinkedHashMap<>(facts);
            existing.put("channel", channel);
            existing.put("consumed", consumed);
            existing.put("draws", 0);
            JOINT.put(key, existing);
        }
        existing.put("draws", (int)existing.get("draws") + 1);
    }

    private static void observeRenderer() throws Exception {
        var type = com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer.class;
        var current = com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
        if (current instanceof ObservedArmRenderer)
            return;
        var ownerField = type.getDeclaredField("armEntity");
        ownerField.setAccessible(true);
        var observer = new ObservedArmRenderer();
        ownerField.set(observer, ownerField.get(current));
        ownerField.set(current, null);
        var renderer = com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent.class
            .getDeclaredField("CUSTOM_FIRST_PERSON_RENDERER");
        renderer.setAccessible(true);
        renderer.set(null, observer);
    }

    private static final class ObservedArmRenderer
        extends com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer {
        @Override
        public boolean render(net.minecraft.client.player.LocalPlayer player, PlayerAnimatableCapability cap,
            com.elfmcys.ysm.client.entity.FirstPersonPart part, com.mojang.blaze3d.vertex.PoseStack poses,
            net.minecraft.client.renderer.MultiBufferSource buffers, int light, int overlay, float partial) {
            renderingPart = part;
            boolean consumed;
            try {
                consumed = super.render(player, cap, part, poses, buffers, light, overlay, partial);
            } finally {
                renderingPart = null;
            }
            if (invalidatedArm != null) {
                armReextract = Map.of("part", part.name(), "consumed", consumed, "validAfter", invalidatedArm.isValid(),
                    "viewReplaced", invalidatedArm.getBonePoses() != invalidatedView, "eventsBefore",
                    eventsBeforeReextract, "eventsAfter", EntityLifecycleProbe.events().size());
                invalidatedArm = null;
                invalidatedView = null;
            }
            try {
                var observed = new LinkedHashMap<String, Object>();
                var field =
                    com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer.class.getDeclaredField("armEntity");
                field.setAccessible(true);
                var owner = (com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity)field.get(this);
                observed.put("installed", owner == null ? "none" : String.valueOf(owner.getInstalledModelId()));
                observed.put("requested", owner == null ? "none" : String.valueOf(owner.getModelHash()));
                observed.put("requestedTexture", owner == null ? "none" : owner.getRequestedTextureName());
                observed.put("root", owner != null && owner.hasPart(part));
                observed.put("output", armOutput().getOrDefault(part.name(), Map.of()));
                observed.put("inputTick", player.tickCount);
                joint("part:" + part, consumed, observed);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            return consumed;
        }
    }

    Map<String, ?> poll() throws Exception {
        if (minecraft.player == null || minecraft.level == null)
            return null;
        var owner = minecraft.player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .orElseThrow(IllegalStateException::new);
        if (ticks++ == 0) {
            FIRST.clear();
            FIRST_REQUESTED.clear();
            JOINT.clear();
            observeRenderer();
            hook = action.arguments().getOrDefault("hook", "none");
            hookCalls = 0;
            preparationFailure = Map.of();
            armReextract = Map.of();
            for (var player : minecraft.level.players()) {
                if (player != minecraft.player) {
                    var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                        .orElseThrow(IllegalStateException::new);
                    var use = EntityUseProbe.watch(cap);
                    if (use != null)
                        uses.add(use);
                    if (Boolean.parseBoolean(action.arguments().getOrDefault("resetRemote", "false")))
                        cap.reset();
                }
            }
            minecraft.options.pauseOnLostFocus = false;
            minecraft.setScreen(null);
            minecraft.options.setCameraType(CameraType.valueOf(action.require("camera")));
            ClientConfig.DISABLE_SELF_MODEL.set(Boolean.parseBoolean(action.require("self")));
            ClientConfig.DISABLE_OTHER_MODEL.set(Boolean.parseBoolean(action.require("other")));
            ClientConfig.DISABLE_SELF_HANDS.set(Boolean.parseBoolean(action.require("hands")));
            minecraft.player.setMainArm(
                net.minecraft.world.entity.HumanoidArm.valueOf(action.arguments().getOrDefault("arm", "RIGHT")));
            if (action.arguments().containsKey("subjectDisabled")) {
                for (var player : minecraft.level.players()) {
                    if (player != minecraft.player)
                        player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                            .ifPresent(cap -> cap.setDisabled(Boolean.parseBoolean(action.require("subjectDisabled"))));
                }
            }
            if (Boolean.parseBoolean(action.arguments().getOrDefault("reset", "false"))) {
                owner.reset();
                com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent.releaseFirstPersonArm();
            }
            originalTick = minecraft.player.tickCount;
            if (Boolean.parseBoolean(action.arguments().getOrDefault("tickZero", "false")))
                minecraft.player.tickCount = 0;
            inputTick = minecraft.player.tickCount;
            COUNTS.forEach((key, value) -> before.put(key, value.clone()));
            seekBefore = owner.getSeekTime();
            if (Boolean.parseBoolean(action.arguments().getOrDefault("trace", "false"))) {
                EntityLifecycleProbe.clearEvents();
                owner.playExtraAnimation("trace");
            }
            return null;
        }
        if (ticks < Integer.parseInt(action.arguments().getOrDefault("ticks", "25")))
            return null;
        var result = new LinkedHashMap<String, Object>();
        var counts = new LinkedHashMap<String, Object>();
        COUNTS.forEach((key, value) -> {
            var start = before.getOrDefault(key, new int[2]);
            counts.put(key, Map.of("host", value[0] - start[0], "replaced", value[1] - start[1]));
        });
        var players = new LinkedHashMap<String, Object>();
        for (var player : minecraft.level.players()) {
            player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                .ifPresent(cap -> players.put(player.getScoreboardName(), facts(cap)));
        }
        try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(Path.of(action.require("screenshot")));
        }
        result.put("players", players);
        result.put("draws", counts);
        result.put("firstHostOpportunities", FIRST);
        result.put("seekBefore", seekBefore);
        result.put("seekAfter", owner.getSeekTime());
        result.put("previousOwnerAssigned", previousOwner == null ? "none" : previousOwner.isAssigned());
        result.put("sameOwner", previousOwner == owner);
        result.put("screenshot", action.require("screenshot"));
        result.put("camera", minecraft.options.getCameraType().name());
        result.put("ticks", ticks);
        result.put("inputTick", inputTick);
        result.put("inputs", action.arguments());
        result.put("hookCalls", hookCalls);
        result.put("preparationFailure", preparationFailure);
        result.put("armReextract", armReextract);
        result.put("armOutput", armOutput());
        result.put("jointDraws", java.util.List.copyOf(JOINT.values()));
        result.put("observedUses", uses.stream().map(EntityUseProbe::snapshot).toList());
        if (Boolean.parseBoolean(action.arguments().getOrDefault("trace", "false"))) {
            result.put("timelineEvents", EntityLifecycleProbe.events());
        }
        hook = "none";
        result.put("boundary",
            "Actual Forge cancellation events, world owners and framebuffer during the recorded client-tick window. Does not count producer Roaming actions, in-flight demand release, background vertices or GPU pixels per owner.");
        previousOwner = owner;
        if (Boolean.parseBoolean(action.arguments().getOrDefault("tickZero", "false")))
            minecraft.player.tickCount = originalTick;
        return result;
    }

    private static Map<String, Object> armOutput() throws ReflectiveOperationException {
        var renderer = com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
        var armField = com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer.class.getDeclaredField("armEntity");
        armField.setAccessible(true);
        var arm = (com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity)armField.get(renderer);
        if (arm == null)
            return Map.of("owner", "none");
        var stateField = arm.getClass().getDeclaredField("firstPersonState");
        stateField.setAccessible(true);
        var state = stateField.get(arm);
        var outputsField = state.getClass().getDeclaredField("outputs");
        outputsField.setAccessible(true);
        var outputs = (com.elfmcys.ysm.natives.render.NativeModelState[])outputsField.get(state);
        var result = new LinkedHashMap<String, Object>();
        for (var part : com.elfmcys.ysm.client.entity.FirstPersonPart.values()) {
            var output = outputs[part.ordinal()];
            result.put(part.name(), Map.of("root", arm.hasPart(part), "extracted", output != null && output.isValid(),
                "vertices", output == null || !output.isValid() ? -1 : output.getTotalVertexCount()));
        }
        result.put("requestedTexture", arm.getRequestedTextureName());
        result.put("effectiveTexture", arm.hasInstalledModel() ? arm.getTextureLocation().toString() : "none");
        result.put("assigned", arm.isAssigned());
        return result;
    }

    private static Map<String, Object> facts(PlayerAnimatableCapability owner) {
        var result = new LinkedHashMap<String, Object>();
        result.put("owner", Integer.toUnsignedString(System.identityHashCode(owner)));
        result.put("assigned", owner.isAssigned());
        result.put("requested", String.valueOf(owner.getModelHash()));
        result.put("installed", String.valueOf(owner.getInstalledModelId()));
        result.put("relation", owner.getInstallationRelation().name());
        result.put("runs", owner.isRunRequired());
        result.put("unfinished", owner.hasUnfinishedWork());
        result.put("seek", owner.getSeekTime());
        result.put("assigned", owner.isAssigned());
        result.put("tick", owner.getEntity().tickCount);
        result.put("requestedTexture", owner.getRequestedTextureName());
        result.put("effectiveTexture", owner.hasInstalledModel() ? String.valueOf(owner.getTextureLocation()) : "none");
        result.put("subjectDisabled", owner.isDisabled());
        result.put("resourceAccessCalls", EntityDemandProbe.snapshot(owner));
        return result;
    }
}
