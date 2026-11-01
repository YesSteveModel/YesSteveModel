package com.elfmcys.ysm.expression.host.builtin;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.compat.parcool.ParCoolCompat;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import com.elfmcys.ysm.accessor.IArrowExtraInfo;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.util.EquipmentUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Built-in READ declarations for generic control state, controller/animation history and equipped-item predicates.
 */
@BuiltinComponent(value = "ctrl")
public final class ControlReadBuiltins implements EntityComponent<Entity> {
    private static final float MIN_CONTROL_SPEED = 0.05f;
    private static final int CTRL_STATE_CONTINUE_VALUE = 2;
    private static final int CTRL_STATE_STOP_VALUE = 3;
    private static final int CTRL_STATE_BYPASS_VALUE = 5;

    private final com.elfmcys.ysm.client.entity.AnimatableEntity<?> owner;
    public ControlReadBuiltins(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner) { this.owner = owner; }

    @BuiltinFunction(value = "attacked", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean attacked(MolangContextView<Entity> context) {
        return control(context, "attacked");
    }

    @BuiltinFunction(value = "death", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean death(MolangContextView<Entity> context) {
        return control(context, "death");
    }

    @BuiltinFunction(value = "elytra_fly", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean elytraFly(MolangContextView<Entity> context) {
        return control(context, "elytra_fly");
    }

    @BuiltinFunction(value = "fly", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean fly(MolangContextView<Entity> context) {
        return control(context, "fly");
    }

    @BuiltinFunction(value = "idle", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean idle(MolangContextView<Entity> context) {
        return control(context, "idle");
    }

    @BuiltinFunction(value = "jump", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean jump(MolangContextView<Entity> context) {
        return control(context, "jump");
    }

    @BuiltinFunction(value = "ladder_down", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean ladderDown(MolangContextView<Entity> context) {
        return control(context, "ladder_down");
    }

    @BuiltinFunction(value = "ladder_stillness", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean ladderStillness(MolangContextView<Entity> context) {
        return control(context, "ladder_stillness");
    }

    @BuiltinFunction(value = "ladder_up", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean ladderUp(MolangContextView<Entity> context) {
        return control(context, "ladder_up");
    }

    @BuiltinFunction(value = "playing_extra_animation", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean playingExtraAnimation(MolangContextView<Entity> context) {
        return owner instanceof CustomPlayerEntity p && p.isPlayingExtraAnimation()
            && p.getCodedAnimationStates(com.elfmcys.ysm.client.controller.collections.PlayerControllerCollection.CAP_CONTROLLER) != AnimationState.IDLE;
    }

    @BuiltinFunction(value = "run", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean run(MolangContextView<Entity> context) {
        return control(context, "run");
    }

    @BuiltinFunction(value = "sleep", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean sleep(MolangContextView<Entity> context) {
        return control(context, "sleep");
    }

    @BuiltinFunction(value = "sneak", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean sneak(MolangContextView<Entity> context) {
        return control(context, "sneak");
    }

    @BuiltinFunction(value = "sneaking", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean sneaking(MolangContextView<Entity> context) {
        return control(context, "sneaking");
    }

    @BuiltinFunction(value = "state_bypass", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float stateBypass(MolangContextView<Entity> context) {

        return CTRL_STATE_BYPASS_VALUE;
    }

    @BuiltinFunction(value = "state_continue", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float stateContinue(MolangContextView<Entity> context) {

        return CTRL_STATE_CONTINUE_VALUE;
    }

    @BuiltinFunction(value = "state_stop", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float stateStop(MolangContextView<Entity> context) {

        return CTRL_STATE_STOP_VALUE;
    }

    @BuiltinFunction(value = "swim", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean swim(MolangContextView<Entity> context) {
        return control(context, "swim");
    }

    @BuiltinFunction(value = "swim_stand", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean swimStand(MolangContextView<Entity> context) {
        return control(context, "swim_stand");
    }

    @BuiltinFunction(value = "walk", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean walk(MolangContextView<Entity> context) {
        return control(context, "walk");
    }

    @BuiltinFunction(value = "all_animations_finished", namespace = "query",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean allAnimationsFinished(MolangContextView<Entity> context) {
        var input = context.inputs().controller();
        return input != null && input.allAnimationsFinished();
    }

    @BuiltinFunction(value = "anim_time", namespace = "query", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float animTime(MolangContextView<Entity> context) {
        var input = context.inputs().animation();
        return input == null ? 0 : input.time();
    }

    @BuiltinFunction(value = "any_animation_finished", namespace = "query",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean anyAnimationFinished(MolangContextView<Entity> context) {
        var input = context.inputs().controller();
        return input != null && input.anyAnimationFinished();
    }

    @BuiltinFunction(value = "armor_value", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float armorValue(MolangContextView<Entity> context) {

        var living = living(context.subject());
        return living == null ? 0 : living.getArmorValue();
    }

    @BuiltinFunction(value = "has_boots", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean hasBoots(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.FEET);
    }

    @BuiltinFunction(value = "has_chest_plate", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean hasChestPlate(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.CHEST);
    }

    @BuiltinFunction(value = "has_elytra", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean hasElytra(MolangContextView<Entity> context) {

        var living = living(context.subject());
        return living != null && !EquipmentUtil.getEquippedElytraItem(living).isEmpty();
    }

    @BuiltinFunction(value = "has_helmet", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean hasHelmet(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.HEAD);
    }

    @BuiltinFunction(value = "has_leggings", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean hasLeggings(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.LEGS);
    }

    @BuiltinFunction(value = "has_mainhand", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean hasMainhand(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.MAINHAND);
    }

    @BuiltinFunction(value = "has_offhand", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean hasOffhand(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.OFFHAND);
    }

    @BuiltinFunction(value = "in_shield_block_cooldown", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public boolean inShieldBlockCooldown(MolangContextView<Entity> context) {
        return owner instanceof PlayerAnimatableCapability p && p.getStateTracker().inShieldBlockCooldown();
    }

    @BuiltinFunction(value = "mainhand_charged_crossbow", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean mainhandChargedCrossbow(MolangContextView<Entity> context) {
        return chargedCrossbow(context, InteractionHand.MAIN_HAND);
    }

    @BuiltinFunction(value = "offhand_charged_crossbow", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean offhandChargedCrossbow(MolangContextView<Entity> context) {
        return chargedCrossbow(context, InteractionHand.OFF_HAND);
    }

    @BuiltinFunction(value = "shoot_item_id", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static String shootItemId(MolangContextView<Entity> context) {

        var entity = context.subject();
        return entity instanceof AbstractArrow && entity instanceof IArrowExtraInfo extra ? extra.getShootItemId() : "";
    }

    private boolean control(MolangContextView<Entity> context, String name) {

        var living = living(context.subject());
        return living != null && name.equals(selectedControl(context, living));
    }

    private String selectedControl(MolangContextView<Entity> context, LivingEntity entity) {
        var animatable = owner;
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
            else if (entity.getPose() == Pose.SWIMMING && isMoving(context, entity)) {
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
            else if (entity.onGround() && entity.getPose() == Pose.CROUCHING && isMoving(context, entity))
                selected = "sneak";
            else if (entity.onGround() && entity.getPose() == Pose.CROUCHING) {
                selected = "sneaking";
            } else if (entity.onGround() && entity.isSprinting())
                selected = "run";
            else if (entity.onGround() && isMoving(context, entity))
                selected = "walk";
            else
                selected = "idle";
        }
        state.setMainAnimationCache(selected);
        return selected;
    }

    private static boolean equipped(MolangContextView<Entity> context, EquipmentSlot slot) {

        var living = living(context.subject());
        return living != null && !EquipmentUtil.getEquippedItem(living, slot).isEmpty();
    }

    private static boolean chargedCrossbow(MolangContextView<Entity> context, InteractionHand hand) {

        var living = living(context.subject());
        if (living == null) {
            return false;
        }
        var item = living.getItemInHand(hand);
        return item.is(Items.CROSSBOW) && CrossbowItem.isCharged(item);
    }

    @Nullable
    private static LivingEntity living(Entity entity) {
        return entity instanceof LivingEntity value ? value : null;
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

    private static float verticalEntitySpeed(LivingEntity entity) {
        return 20 * (float)(entity.position().y - entity.yo);
    }
    private static boolean isMoving(MolangContextView<Entity> context, LivingEntity entity) {
        return Math.abs(entity.walkAnimation.speed(context.inputs().partialTick())) > MIN_CONTROL_SPEED;
    }
}
