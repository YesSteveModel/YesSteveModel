package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

@BuiltinComponent("ysm")
public final class MovementBuiltins {
    private MovementBuiltins() {}

    @BuiltinFunction("xxa")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float xxa(MolangContextView<LivingEntity> context) {
        return context.subject() instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).xxa() : context.subject().xxa;
    }

    @BuiltinFunction("zza")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float zza(MolangContextView<LivingEntity> context) {
        return context.subject() instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).zza() : context.subject().zza;
    }

    @BuiltinFunction("ground_speed2")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float trackedGroundSpeed(MolangContextView<Entity> context) {
        return context.subjectSample().trackedGroundSpeed();
    }

    @BuiltinFunction("input_horizontal")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float inputHorizontal(MolangContextView<Entity> context) {
        return context.subjectSample().inputHorizontal();
    }

    @BuiltinFunction("input_vertical")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float inputVertical(MolangContextView<Entity> context) {
        return context.subjectSample().inputVertical();
    }

    @BuiltinFunction("on_ladder")
    public static boolean ysmOnLadder(MolangContextView<LivingEntity> context) {
        return context.subject().onClimbable();
    }

    @BuiltinFunction("delta_movement_length")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float ysmDeltaMovementLength(MolangContextView<Entity> context) {
        return (float)context.subject().getDeltaMovement().length();
    }

    @BuiltinFunction("yya")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float yya(MolangContextView<Entity> context) {
        return context.subject() instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).yya()
            : context.subject() instanceof LivingEntity e ? e.yya : 0;
    }

    @BuiltinFunction("ladder_facing")
    public static float ladderFacing(MolangContextView<LivingEntity> context) {
        var entity = context.subject();
        var pos = entity.getLastClimbablePos();
        if (pos.isEmpty())
            return 0;
        return entity.level().getBlockState(pos.get()).getOptionalValue(HorizontalDirectionalBlock.FACING)
            .map(direction -> (float)direction.get2DDataValue()).orElse(0f);
    }
}
