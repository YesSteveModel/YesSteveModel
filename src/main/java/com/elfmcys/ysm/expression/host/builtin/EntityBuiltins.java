package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.capability.PlayerStateTracker;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/** Core entity, world and render-input reads. */
@BuiltinComponent(value = "query")
public final class EntityBuiltins implements EntityComponent<Entity> {
    public EntityBuiltins(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner) {}

    @BuiltinFunction(value = "o", namespace = "global", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float globalO(MolangContextView<Entity> context) {

        return 0;
    }

    @BuiltinFunction(value = "food_level", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float foodLevel(MolangContextView<Player> context) {
        return context.foodLevel();
    }

    @BuiltinFunction(value = "is_close_eyes", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isCloseEyes(MolangContextView<Entity> context) {

        var living = living(context.subject());
        if (living == null) {
            return false;
        }
        var renderTicks = context.renderTicks();
        var remainder = (renderTicks + Math.abs(living.getUUID().getLeastSignificantBits()) % 10) % 90;
        return living.isSleeping() || 85 < remainder && remainder < 90;
    }

    @BuiltinFunction(value = "is_fishing", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isFishing(MolangContextView<Entity> context) {

        Entity entity = context.subject();
        return entity instanceof Player player && player.fishing != null;
    }

    @BuiltinFunction(value = "is_open_air", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isOpenAir(MolangContextView<Entity> context) {

        var entity = context.subject();
        var position = entity.blockPosition();
        return entity.level().canSeeSky(position)
            && entity.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, position).getY() <= position.getY();
    }

    @BuiltinFunction(value = "weather", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float weather(MolangContextView<Entity> context) {

        var level = context.subject().level();
        return level.isThundering() ? 2 : level.isRaining() ? 1 : 0;
    }

    @BuiltinFunction(value = "body_y_rotation", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float bodyYRotation(MolangContextView<Entity> context) {
        return Mth.wrapDegrees(context.bodyYaw());
    }

    @BuiltinFunction(value = "cardinal_facing_2d", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float cardinalFacing2d(MolangContextView<Entity> context) {

        return context.subject().getDirection().get3DDataValue();
    }

    @BuiltinFunction(value = "eye_target_y_rotation", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float eyeTargetYRotation(MolangContextView<Entity> context) {
        return context.subject().getViewYRot(context.partialTick());
    }

    @BuiltinFunction(value = "ground_speed", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float groundSpeed(MolangContextView<Entity> context) {

        var velocity = context.subject().getDeltaMovement();
        return 20 * Mth.sqrt((float)(velocity.x * velocity.x + velocity.z * velocity.z));
    }

    @BuiltinFunction(value = "head_x_rotation", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float headXRotation(MolangContextView<Entity> context) {

        return context.headYaw();
    }

    @BuiltinFunction(value = "head_y_rotation", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float headYRotation(MolangContextView<Entity> context) {

        return context.headPitch();
    }

    @BuiltinFunction(value = "health", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float health(MolangContextView<Entity> context) {
        return context.health(false);
    }

    @BuiltinFunction(value = "is_eating", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isEating(MolangContextView<Entity> context) {

        var living = living(context.subject());
        return living != null && living.getUseItem().getUseAnimation() == UseAnim.EAT;
    }

    @BuiltinFunction(value = "is_in_water", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isInWater(MolangContextView<Entity> context) {

        return context.subject().isInWater();
    }

    @BuiltinFunction(value = "is_jumping", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isJumping(MolangContextView<Entity> context) {

        if (!(context.subject() instanceof Player player)) {
            return false;
        }
        return !player.getAbilities().flying && !player.isPassenger() && !player.onGround() && !player.isInWater();
    }

    @BuiltinFunction(value = "is_on_ground", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isOnGround(MolangContextView<Entity> context) {

        return context.subject().onGround();
    }

    @BuiltinFunction(value = "is_sneaking", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isSneaking(MolangContextView<Entity> context) {

        var entity = context.subject();
        return entity.onGround() && entity.getPose() == Pose.CROUCHING;
    }

    @BuiltinFunction(value = "is_swimming", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isSwimming(MolangContextView<Entity> context) {

        return context.subject().isSwimming();
    }

    @BuiltinFunction(value = "life_time", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float lifeTime(MolangContextView<Entity> context) {

        return context.lifeTime();
    }

    @BuiltinFunction(value = "max_health", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float maxHealth(MolangContextView<Entity> context) {
        return context.health(true);
    }

    @BuiltinFunction(value = "time_stamp", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float timeStamp(MolangContextView<Entity> context) {

        return context.subject().level().getDayTime();
    }

    @BuiltinFunction(value = "yaw_speed", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float yawSpeed(MolangContextView<Entity> context) {

        var entity = context.subject();
        return entity instanceof LocalPlayer ? PlayerStateTracker.getLocalPlayerYawSpeed()
            : 20 * (entity.getYRot() - entity.yRotO);
    }

    @BuiltinFunction(value = "elytra_rot_z", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float elytraRotZ(MolangContextView<AbstractClientPlayer> context) {

        return (float)Math.toDegrees(context.subject().elytraRotZ);
    }

    @BuiltinFunction(value = "fps", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float fps(MolangContextView<Entity> context) {

        return Minecraft.getInstance().getFps();
    }

    @BuiltinFunction(value = "head_pitch", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float headPitch(MolangContextView<Entity> context) {

        return context.headPitch();
    }

    @BuiltinFunction(value = "head_yaw", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float headYaw(MolangContextView<Entity> context) {

        return context.headYaw();
    }

    @BuiltinFunction(value = "rendering_in_inventory", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean renderingInInventory(MolangContextView<Entity> context) {

        return context.renderingInInventory();
    }

    @BuiltinFunction(value = "texture_name", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static String textureName(MolangContextView<Entity> context) {
        return context.textureName();
    }

    @BuiltinFunction(value = "yya", namespace = "ysm", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static float yya(MolangContextView<Entity> context) {
        return context.forwardInput();
    }

    @Nullable
    private static LivingEntity living(Entity entity) {
        return entity instanceof LivingEntity value ? value : null;
    }
}
