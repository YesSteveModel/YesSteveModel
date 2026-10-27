package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.compat.parcool.ParCoolCompat;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;

import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

public final class ContextReads {
    private ContextReads() {}

    private static final float MIN_CONTROL_SPEED = 0.05f;

    public static String selectedControl(MolangContext<?> context) {
        if (!(context.subject() instanceof LivingEntity entity))
            return "";
        var inputs = inputs(context);
        var animatable = inputs.animatable();
        var state = animatable.getStateTracker();
        var cached = state.getMainAnimationCache();
        if (cached != null) {
            return cached;
        }
        String selected = "";
        if (!(animatable instanceof IPreviewEntity) && !hasParcoolAnimation(entity)
            && (entity.getVehicle() == null || !entity.getVehicle().isAlive())) {
            if (entity.isDeadOrDying())
                selected = "death";
            else if (entity.isAutoSpinAttack())
                selected = "riptide";
            else if (entity.getPose() == Pose.SLEEPING)
                selected = "sleep";
            else if (entity.isSwimming())
                selected = "swim";
            else if (entity.getPose() == Pose.SWIMMING && isMoving(inputs, entity)) {
                selected = "climb";
            } else if (entity.getPose() == Pose.SWIMMING)
                selected = "climbing";
            else if (entity.onClimbable() && verticalEntitySpeed(entity) > 0) {
                selected = "ladder_up";
            } else if (entity.onClimbable() && verticalEntitySpeed(entity) == 0) {
                selected = "ladder_stillness";
            } else if (entity.onClimbable())
                selected = "ladder_down";
            else if (isFlying(animatable, entity))
                selected = "fly";
            else if (entity.getPose() == Pose.FALL_FLYING && entity.isFallFlying()) {
                selected = "elytra_fly";
            } else if (entity.isInWater() && !entity.onGround())
                selected = "swim_stand";
            else if (entity.hurtTime > 0)
                selected = "attacked";
            else if (!entity.onGround() && !entity.isInWater())
                selected = "jump";
            else if (entity.onGround() && entity.getPose() == Pose.CROUCHING && isMoving(inputs, entity))
                selected = "sneak";
            else if (entity.onGround() && entity.getPose() == Pose.CROUCHING) {
                selected = "sneaking";
            } else if (entity.onGround() && entity.isSprinting())
                selected = "run";
            else if (entity.onGround() && isMoving(inputs, entity))
                selected = "walk";
            else
                selected = "idle";
        }
        state.setMainAnimationCache(selected);
        return selected;
    }

    private static boolean isFlying(com.elfmcys.ysm.client.entity.AnimatableEntity<?> animatable, LivingEntity entity) {
        if (animatable instanceof PlayerAnimatableCapability capability && !capability.isLocalPlayer()) {
            return capability.getStateTracker().isFlying();
        }
        return entity instanceof Player player && player.getAbilities().flying;
    }

    private static boolean hasParcoolAnimation(LivingEntity entity) {
        return entity instanceof Player player && ParCoolCompat.hasAnimation(player);
    }

    private static boolean isMoving(MinecraftRuntimeInputs<?> inputs, LivingEntity entity) {
        return Math.abs(entity.walkAnimation.speed(inputs.animationEvent().getPartialTick())) > MIN_CONTROL_SPEED;
    }

    private static float verticalEntitySpeed(LivingEntity entity) {
        return 20 * (float)(entity.position().y - entity.yo);
    }

    public static MinecraftRuntimeInputs<?> inputs(MolangContext<?> context) {
        if (context.productionInputs() instanceof MinecraftRuntimeInputs<?> inputs)
            return inputs;
        throw new IllegalStateException("invocation has no Minecraft inputs");
    }

    public static boolean keyboard(float[] keys) {
        if (!com.elfmcys.ysm.util.InputCheckUtil.isInGame())
            return false;
        for (float key : keys) {
            int index = (int)key;
            if (org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE <= index && index <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST
                && com.elfmcys.ysm.client.event.ModInputEvent.KEY_STATES[index])
                return true;
        }
        return false;
    }

    public static String modVersion(String id) {
        var mods = net.minecraftforge.fml.ModList.get();
        var container = mods == null ? null : mods.getModContainerById(id).orElse(null);
        return container == null ? "" : net.minecraftforge.common.util.MavenVersionStringHelper
            .artifactVersionToString(container.getModInfo().getVersion());
    }

    public static float foodLevel(MolangContext<?> context) {
        var a = inputs(context).animatable();
        if (a instanceof PlayerAnimatableCapability p && !p.isLocalPlayer())
            return p.getStateTracker().foodLevel();
        return context.subject() instanceof Player p ? p.getFoodData().getFoodLevel() : 20;
    }

    public static float health(MolangContext<?> context, boolean maximum) {
        var a = inputs(context).animatable();
        if (a instanceof PlayerAnimatableCapability p && !p.isLocalPlayer())
            return maximum ? p.getStateTracker().maxHealth() : p.getStateTracker().health();
        return context.subject() instanceof LivingEntity e ? (maximum ? e.getMaxHealth() : e.getHealth()) : 0;
    }

    public static float forwardInput(MolangContext<?> context) {
        var a = inputs(context).animatable();
        if (a instanceof PlayerAnimatableCapability p && !p.isLocalPlayer())
            return p.getStateTracker().yya();
        return context.subject() instanceof LivingEntity e ? e.yya : 0;
    }

    public static String textureName(MolangContext<?> context) {
        var a = inputs(context).animatable();
        return a instanceof com.elfmcys.ysm.client.entity.CustomHumanoidEntity<?> h ? h.getTextureName() : "";
    }

    public static boolean shieldCooldown(MolangContext<?> context) {
        return inputs(context).animatable() instanceof PlayerAnimatableCapability p
            && p.getStateTracker().inShieldBlockCooldown();
    }

    public static boolean playingExtraAnimation(MolangContext<?> context) {
        return inputs(context).animatable() instanceof CustomPlayerEntity p && p.isPlayingExtraAnimation()
            && p.getCodedAnimationStates(
                com.elfmcys.ysm.client.controller.collections.PlayerControllerCollection.CAP_CONTROLLER) != AnimationState.IDLE;
    }

    public static MolangVector boneRotation(MolangContext<?> context, String name) {
        var bone = inputs(context).animatable().getBone(com.elfmcys.ysm.util.StringPool.computeIfAbsent(name));
        return bone == null ? new MolangVector(0, 0, 0) : new MolangVector(-(float)Math.toDegrees(bone.getRotationX()),
            -(float)Math.toDegrees(bone.getRotationY()), (float)Math.toDegrees(bone.getRotationZ()));
    }

    public static float physics(MolangContext<?> context, String name, float value, boolean first, float[] args) {
        if (name.isEmpty())
            return 0;
        int key = com.elfmcys.ysm.util.StringPool.computeIfAbsent(name);
        var input = inputs(context);
        var manager = input.animatable().getPhysicsManager(input.animationEvent());
        var current = manager.get(key);
        float a = args.length > 0 ? args[0] : 1, b = args.length > 1 ? args[1] : 1, c = args.length > 2 ? args[2] : 1;
        if (current == null) {
            current = first ? new com.elfmcys.ysm.client.animation.physics.FirstOrder(value, a)
                : new com.elfmcys.ysm.client.animation.physics.SecondOrder(value, a, b, c);
            manager.put(key, current);
            return value;
        }
        current.setArgs(value, a, b, c);
        return current.getValue();
    }

}
