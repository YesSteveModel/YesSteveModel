package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.capability.PlayerStateTracker;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.expression.host.SubjectSample;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.mixin.client.ArrowEntityAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

@BuiltinComponent("query")
public final class EntityBuiltins implements EntityComponent<Entity> {
    private final SubjectSample.Draft sample;
    private final Entity subject;

    private AnimationEvent<?> event;

    public EntityBuiltins(AnimatableEntity<Entity> owner) {
        sample = owner.subjectSampleDraft();
        subject = owner.getEntity();
    }

    @Override
    public void beginEval(AnimationEvent<?> event) {
        writeOrientation(event);
        this.event = event;
    }

    @Override
    public void update(float previousTime, float currentTime, float partialTicks) {
        writeOrientation(event);
    }

    private void writeOrientation(AnimationEvent<?> event) {
        var data = event.getExtraData();
        var living = living(subject);
        var remainder =
            living == null ? 0 : (event.renderTicks + Math.abs(living.getUUID().getLeastSignificantBits()) % 10) % 90;
        sample.closeEyes(living != null && (living.isSleeping() || 85 < remainder && remainder < 90));
        float yaw = subject instanceof LocalPlayer ? PlayerStateTracker.getLocalPlayerYawSpeed()
            : 20 * (subject.getYRot() - subject.yRotO);
        sample.pitch(Mth.lerp(event.getPartialTick(), subject.xRotO, subject.getXRot()),
            subject.getViewXRot(event.getPartialTick()));
        sample.orientation(Mth.wrapDegrees(data.lerpBodyRot), data.netHeadYaw, data.headPitch,
            subject.getViewYRot(event.getPartialTick()), yaw);
    }

    @Override
    public void endEval() {
        event = null;
    }

    @BuiltinFunction(value = "o", namespace = "global")
    public static float globalO(MolangContextView<Entity> context) {

        return 0;
    }

    @BuiltinFunction("body_y_rotation")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float bodyYRotation(MolangContextView<Entity> context) {
        return context.subjectSample().bodyYaw();
    }

    @BuiltinFunction("cardinal_facing_2d")
    public static float cardinalFacing2d(MolangContextView<Entity> context) {

        return context.subject().getDirection().get3DDataValue();
    }

    @BuiltinFunction("eye_target_y_rotation")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float eyeTargetYRotation(MolangContextView<Entity> context) {
        return context.subjectSample().eyeYaw();
    }

    @BuiltinFunction("ground_speed")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float groundSpeed(MolangContextView<Entity> context) {

        var velocity = context.subject().getDeltaMovement();
        return 20 * Mth.sqrt((float)(velocity.x * velocity.x + velocity.z * velocity.z));
    }

    @BuiltinFunction("head_x_rotation")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float headXRotation(MolangContextView<Entity> context) {
        return context.subjectSample().headYaw();
    }

    @BuiltinFunction("head_y_rotation")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float headYRotation(MolangContextView<Entity> context) {
        return context.subjectSample().headPitch();
    }

    @BuiltinFunction("health")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float health(MolangContextView<Entity> context) {
        return context.subject() instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).health()
            : context.subject() instanceof LivingEntity e ? e.getHealth() : 0;
    }

    @BuiltinFunction("is_in_water")
    public static boolean isInWater(MolangContextView<Entity> context) {

        return context.subject().isInWater();
    }

    @BuiltinFunction("is_jumping")
    public static Boolean isJumping(MolangContextView<Entity> context) {

        if (!(context.subject() instanceof Player player)) {
            return false;
        }
        Boolean flying = player instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).flying()
            : Boolean.valueOf(player.getAbilities().flying);
        return flying == null ? null : !flying && !player.isPassenger() && !player.onGround() && !player.isInWater();
    }

    @BuiltinFunction("is_on_ground")
    public static boolean isOnGround(MolangContextView<Entity> context) {

        return context.subject().onGround();
    }

    @BuiltinFunction("is_sneaking")
    public static boolean isSneaking(MolangContextView<Entity> context) {

        var entity = context.subject();
        return entity.onGround() && entity.getPose() == Pose.CROUCHING;
    }

    @BuiltinFunction("is_swimming")
    public static boolean isSwimming(MolangContextView<Entity> context) {

        return context.subject().isSwimming();
    }

    @BuiltinFunction("max_health")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float maxHealth(MolangContextView<Entity> context) {
        return context.subject() instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).maxHealth()
            : context.subject() instanceof LivingEntity e ? e.getMaxHealth() : 0;
    }

    @BuiltinFunction("yaw_speed")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float yawSpeed(MolangContextView<Entity> context) {
        return context.subjectSample().yawSpeed();
    }

    @BuiltinFunction("eye_target_x_rotation")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float queryEyeTargetXRotation(MolangContextView<Entity> context) {
        return context.subjectSample().eyePitch();
    }

    @BuiltinFunction("modified_distance_moved")
    public static float queryModifiedDistanceMoved(MolangContextView<Entity> context) {
        return context.subject().walkDist;
    }

    @BuiltinFunction("walk_distance")
    public static float queryWalkDistance(MolangContextView<Entity> context) {
        return context.subject().moveDist;
    }

    @BuiltinFunction("has_rider")
    public static boolean queryHasRider(MolangContextView<Entity> context) {
        return context.subject().isVehicle();
    }

    @BuiltinFunction("is_in_water_or_rain")
    public static boolean queryIsInWaterOrRain(MolangContextView<Entity> context) {
        return context.subject().isInWaterRainOrBubble();
    }

    @BuiltinFunction("is_on_fire")
    public static boolean queryIsOnFire(MolangContextView<Entity> context) {
        return context.subject().isOnFire();
    }

    @BuiltinFunction("is_riding")
    public static boolean queryIsRiding(MolangContextView<Entity> context) {
        return context.subject().isPassenger();
    }

    @BuiltinFunction("is_spectator")
    public static boolean queryIsSpectator(MolangContextView<Entity> context) {
        return context.subject().isSpectator();
    }

    @BuiltinFunction("is_sprinting")
    public static boolean queryIsSprinting(MolangContextView<Entity> context) {
        return context.subject().isSprinting();
    }

    @BuiltinFunction("hurt_time")
    public static float queryHurtTime(MolangContextView<LivingEntity> context) {
        return context.subject().hurtTime;
    }

    @BuiltinFunction("is_playing_dead")
    public static boolean queryIsPlayingDead(MolangContextView<LivingEntity> context) {
        return context.subject().isDeadOrDying();
    }

    @BuiltinFunction("is_sleeping")
    public static boolean queryIsSleeping(MolangContextView<LivingEntity> context) {
        return context.subject().isSleeping();
    }

    @BuiltinFunction("delta_time")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float queryDeltaTime(MolangContextView<Entity> context) {
        return context.subjectSample().sampleSpan() / 20.0f;
    }

    @BuiltinFunction("body_x_rotation")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float queryBodyXRotation(MolangContextView<LivingEntity> context) {
        return context.subjectSample().bodyPitch();
    }

    @Nullable
    private static LivingEntity living(Entity entity) {
        return entity instanceof LivingEntity value ? value : null;
    }

    @BuiltinFunction(value = "is_close_eyes", namespace = "ysm")
    public static Boolean isCloseEyes(MolangContextView<Entity> context) {
        return context.subjectSample().closeEyes();
    }

    @BuiltinFunction(value = "head_pitch", namespace = "ysm")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float headPitch(MolangContextView<Entity> context) {
        return context.subjectSample().headPitch();
    }

    @BuiltinFunction(value = "head_yaw", namespace = "ysm")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float headYaw(MolangContextView<Entity> context) {
        return context.subjectSample().headYaw();
    }

    @BuiltinFunction(value = "air_supply", namespace = "ysm")
    public static float ysmAirSupply(MolangContextView<Entity> context) {
        return context.subject().getAirSupply();
    }

    @BuiltinFunction(value = "frozen_ticks", namespace = "ysm")
    public static float ysmFrozenTicks(MolangContextView<Entity> context) {
        return context.subject().getTicksFrozen();
    }

    @BuiltinFunction(value = "eye_in_water", namespace = "ysm")
    public static boolean ysmEyeInWater(MolangContextView<Entity> context) {
        return context.subject().isUnderWater();
    }

    @BuiltinFunction(value = "is_passenger", namespace = "ysm")
    public static boolean ysmIsPassenger(MolangContextView<Entity> context) {
        return context.subject().isPassenger();
    }

    @BuiltinFunction(value = "is_sleep", namespace = "ysm")
    public static boolean ysmIsSleep(MolangContextView<Entity> context) {
        return context.subject().getPose() == Pose.SLEEPING;
    }

    @BuiltinFunction(value = "is_sneak", namespace = "ysm")
    public static boolean ysmIsSneak(MolangContextView<Entity> context) {
        return context.subject().onGround() && context.subject().getPose() == Pose.CROUCHING;
    }

    @BuiltinFunction(value = "arrow_count", namespace = "ysm")
    public static float ysmArrowCount(MolangContextView<LivingEntity> context) {
        return context.subject().getArrowCount();
    }

    @BuiltinFunction(value = "stinger_count", namespace = "ysm")
    public static float ysmStingerCount(MolangContextView<LivingEntity> context) {
        return context.subject().getStingerCount();
    }

    @BuiltinFunction(value = "hurt_time", namespace = "ysm")
    public static float ysmHurtTime(MolangContextView<LivingEntity> context) {
        return context.subject().hurtTime;
    }

    @BuiltinFunction(value = "is_riptide", namespace = "ysm")
    public static boolean ysmIsRiptide(MolangContextView<LivingEntity> context) {
        return context.subject().isAutoSpinAttack();
    }

    @BuiltinFunction(value = "is_player", namespace = "ysm")
    public static boolean ysmIsPlayer(MolangContextView<LivingEntity> context) {
        return context.subject() instanceof Player;
    }

    @BuiltinFunction(value = "swinging", namespace = "ysm")
    public static boolean ysmSwinging(MolangContextView<LivingEntity> context) {
        return context.subject().swinging;
    }

    @BuiltinFunction(value = "swing_time", namespace = "ysm")
    public static float ysmSwingTime(MolangContextView<LivingEntity> context) {
        return context.subject().swingTime;
    }

    @BuiltinFunction(value = "swinging_arm", namespace = "ysm")
    public static float ysmSwingingArm(MolangContextView<LivingEntity> context) {
        return context.subject().swingingArm == net.minecraft.world.InteractionHand.MAIN_HAND ? 0 : 1;
    }

    @BuiltinFunction(value = "attack_time", namespace = "ysm")
    public static float ysmAttackTime(MolangContextView<LivingEntity> context) {
        return context.subject().getAttackAnim(context.inputs().partialTick());
    }

    @BuiltinFunction(value = "time_delta", namespace = "ysm")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float ysmTimeDelta(MolangContextView<Entity> context) {
        return context.subjectSample().sampleSpan() / 20.0f;
    }

    @BuiltinFunction(value = "entity_type", namespace = "ysm")
    public static String ysmEntityType(MolangContextView<LivingEntity> context) {
        if (context.subject() instanceof Player)
            return "player";
        var id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(context.subject().getType());
        return id == null ? "" : id.toString();
    }

    @BuiltinFunction(value = "effect_level", namespace = "ysm", minArgs = 1)
    public static Float effectLevel(MolangContextView<Entity> context, int argc, String... names) {
        var subject = context.subject();
        if (!(subject instanceof LivingEntity) && !(subject instanceof Arrow))
            return null;
        if (subject instanceof net.minecraft.client.player.RemotePlayer
            && com.elfmcys.ysm.expression.host.MolangContext.projection(context).effects() == null)
            return null;
        int sum = 0;
        for (String name : names) {
            var id = ResourceLocation.tryParse(name);
            var effect = id == null ? null : ForgeRegistries.MOB_EFFECTS.getValue(id);
            if (effect == null)
                continue;
            if (subject instanceof net.minecraft.client.player.RemotePlayer) {
                sum += (int)com.elfmcys.ysm.expression.host.MolangContext.projection(context).effectLevel(effect);
            } else if (subject instanceof LivingEntity living) {
                var instance = living.getEffect(effect);
                if (instance != null)
                    sum += instance.getAmplifier() + 1;
            } else {
                for (var instance : ((ArrowEntityAccessor)subject).getEffects()) {
                    if (instance.getEffect() == effect) {
                        sum += instance.getAmplifier() + 1;
                        break;
                    }
                }
            }
        }
        return (float)sum;
    }
}
