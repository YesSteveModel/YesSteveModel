package com.elfmcys.ysm.verification;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.core.controller.AnimationPlayer;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.model.ModelSystem;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.UUID;

/** Functional overlay for a separate Forge run; absent from production and timing runs. */
public final class StartupConsumption {
    private static ClientModelService service;
    private static boolean requestedWorld;
    private static boolean finished;
    private static ExpressionEngineMetrics.Snapshot prepared;

    private StartupConsumption() {}

    public static void begin(ClientModelService value) {
        service = value;
        try {
            prepared = snapshot();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        MinecraftForge.EVENT_BUS.addListener(StartupConsumption::tick);
    }

    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished)
            return;
        var minecraft = Minecraft.getInstance();
        if (!requestedWorld && minecraft.getOverlay() == null && minecraft.screen != null) {
            requestedWorld = true;
            YesSteveModel.LOGGER.info("[startup-consumption] creating-world initialScreen={}",
                minecraft.screen.getClass().getName());
            minecraft.createWorldOpenFlows().createFreshLevel("ysm-consumption",
                new LevelSettings("YSM consumption", GameType.CREATIVE, false, Difficulty.PEACEFUL, false,
                    new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(42L, false, false), access -> access.registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
        }
        if (minecraft.level == null || minecraft.player == null)
            return;
        finished = true;
        try {
            consume();
            YesSteveModel.LOGGER.info("[startup-consumption] status=passed");
        } catch (Throwable failure) {
            YesSteveModel.LOGGER.error("[startup-consumption] status=failed", failure);
        } finally {
            StartupObservation.boundary("NORMAL_STOP_REQUESTED");
            minecraft.stop();
        }
    }

    private static ExpressionEngineMetrics.Snapshot snapshot() throws ReflectiveOperationException {
        var field = StartupObservation.class.getDeclaredField("metrics");
        field.setAccessible(true);
        return ((ExpressionEngineMetrics)field.get(null)).snapshot();
    }

    private static void consume() throws Exception {
        var minecraft = Minecraft.getInstance();
        var entry = service.catalog().models().values().stream()
            .filter(value -> value.builtin()
                && (value.displayPath().equals("default") || value.displayPath().equals("default.mxc")))
            .findFirst().orElseThrow();
        var request = service.resourceRequest(entry.modelHash(), "player", "default");
        try (var lease = service.findReady(request).orElseThrow()) {
            require(lease.poll() instanceof AcquireResult.Ready, "required resource acquisition");
            var target = ((AcquireResult.Ready)lease.poll()).target();
            var before = snapshot();
            require(
                prepared.portableProgramDecodes() == before.portableProgramDecodes()
                    && Arrays.equals(prepared.stageCalls(), before.stageCalls()),
                "no default preparation moved into world entry");
            var secondPlayer = new RemotePlayer(minecraft.level,
                new GameProfile(UUID.fromString("67cd10b6-5c94-46db-86f0-9c05d4ef5071"), "YsmSecond"));
            secondPlayer.setPos(minecraft.player.getX() + 2, minecraft.player.getY(), minecraft.player.getZ());
            var first = new PlayerAnimatableCapability(minecraft.player);
            var second = new PlayerAnimatableCapability(secondPlayer);
            try {
                first.updateModelAndTexture(entry.modelHash(), "default");
                second.updateModelAndTexture(entry.modelHash(), "default");
                var a = first.getExpressionRuntime();
                var b = second.getExpressionRuntime();
                require(a.module().orElseThrow() == target.expressions(), "first required module identity");
                require(b.module().orElseThrow() == target.expressions(), "second required module identity");
                var route = new ConfigActionRoute("extra_config", 0);
                var ai = first.getAnimationProcessor().invocationInputs(first.createIngressAnimationEvent());
                var bi = second.getAnimationProcessor().invocationInputs(second.createIngressAnimationEvent());
                var ag = a.generation();
                var bg = b.generation();
                a.writeConfigBoolean(ai, ag, route, true);
                require(a.readConfigBoolean(ai, ag, route), "first persistent value=true");
                require(!b.readConfigBoolean(bi, bg, route), "second persistent value=false");
                var ap = new AnimationPlayer(first, 0);
                var bp = new AnimationPlayer(second, 0);
                ap.setAnimation("idle");
                bp.setAnimation("idle");
                var rawProgram = target.expressions().requireFloatProgram(
                    new com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation("player",
                        "animation/main/idle/bone/molang/rotation/0/pre/0",
                        com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_SAMPLE, ""));
                var rotation = first.getAnimation("idle").boneAnimations.stream()
                    .filter(bone -> bone.boneName.equals("molang")).findFirst().orElseThrow().rotationKeyFrames.get(0);
                a.update(ai, evaluation -> {
                    ap.process(0, evaluation, false);
                    equal(evaluation.evaluate(rawProgram), 2, "first bound expression");
                    equal(rotation.getLerpPoint(evaluation, 0).x(), -2 * (float)Math.PI / 180, "first rotation");
                    require(evaluation.failure().isEmpty(), "first expression eligibility");
                    return null;
                });
                b.update(bi, evaluation -> {
                    bp.process(0, evaluation, false);
                    bp.process(30, evaluation, false);
                    equal(evaluation.evaluate(rawProgram), -2, "second bound expression");
                    equal(rotation.getLerpPoint(evaluation, 0).x(), 2 * (float)Math.PI / 180, "second rotation");
                    require(evaluation.failure().isEmpty(), "second expression eligibility");
                    return null;
                });
                require(ap.getState() == AnimationState.RUNNING && bp.getState() == AnimationState.RUNNING,
                    "animation state progression");
                equal(bp.getAnimationContext().animTime(), 1.5f, "second animation time");
                first.reset();
                a.close();
                require(a.generation() == 0 && b.generation() == bg, "independent generation after reset/close");
                require(!b.readConfigBoolean(bi, bg, route), "second state after first reset/close");
                b.observe(bi, evaluation -> {
                    evaluation.setAnimationContext(bp.getAnimationContext());
                    equal(evaluation.evaluate(rawProgram), -2, "second result after first reset/close");
                    require(evaluation.failure().isEmpty(), "second eligibility after first reset/close");
                    return null;
                });
                require(lease.poll() instanceof AcquireResult.Ready && lease.isCurrent(request),
                    "required lease after reset");
                var after = snapshot();
                require(
                    before.portableProgramDecodes() == after.portableProgramDecodes()
                        && Arrays.equals(before.stageCalls(), after.stageCalls()),
                    "no preparation moved into first consumption");
                YesSteveModel.LOGGER.info(
                    "[startup-consumption] world={} first={} second={} inputs=0,1.5 values=2,-2 rotations={},{} states=RUNNING,RUNNING config=true,false secondGeneration={} requiredCurrent=true firstUseCompileDecode=0",
                    minecraft.level.dimension().location(), minecraft.player.getClass().getName(),
                    secondPlayer.getClass().getName(), -2 * (float)Math.PI / 180, 2 * (float)Math.PI / 180, bg);
            } finally {
                first.retire();
                second.retire();
            }
        }
    }

    public static void requiredFailure(ModelSystem system, HostAvailability availability) {
        try {
            ClientModelService.start(system, availability);
            throw new AssertionError("required failure unexpectedly published service");
        } catch (Throwable failure) {
            var cause = failure;
            while (cause.getCause() != null)
                cause = cause.getCause();
            require(cause instanceof NoSuchFileException, "original required I/O failure category");
            require(ClientModelService.current().isEmpty(), "no partial service");
            YesSteveModel.LOGGER.info(
                "[startup-consumption] requiredFailure=passed category={} source={} partialService=false",
                cause.getClass().getName(), cause.getMessage());
        }
        Minecraft.getInstance().tell(() -> Minecraft.getInstance().stop());
    }

    private static void equal(float actual, float expected, String label) {
        require(Float.isFinite(actual) && Math.abs(actual - expected) < 0.00001f,
            label + " actual=" + actual + " expected=" + expected);
    }

    private static void require(boolean condition, String label) {
        if (!condition)
            throw new AssertionError(label);
    }
}
