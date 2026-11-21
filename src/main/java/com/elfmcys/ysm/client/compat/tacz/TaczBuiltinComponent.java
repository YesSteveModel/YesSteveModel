package com.elfmcys.ysm.client.compat.tacz;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "tacz"))
public final class TaczBuiltinComponent implements EntityComponent<LivingEntity> {
    public TaczBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction("tac_hold_gun")
    public static boolean holdsGun(MolangContextView<LivingEntity> context) {
        return TACZCompat.isMainhandHoldingGun(context.subject());
    }

    @BuiltinFunction("tac_gun_type")
    public static String gunType(MolangContextView<LivingEntity> context) {
        var stack = context.subject().getMainHandItem();
        var gun = IGun.getIGunOrNull(stack);
        return gun == null ? ""
            : TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).map(index -> index.getType()).orElse("");
    }

    @BuiltinFunction("tac_gun_id")
    public static String gunId(MolangContextView<LivingEntity> context) {
        var stack = context.subject().getMainHandItem();
        var gun = IGun.getIGunOrNull(stack);
        return gun == null ? "" : gun.getGunId(stack).toString();
    }

    @BuiltinFunction("tac_is_fire")
    public static boolean isFire(MolangContextView<LivingEntity> context) {
        return IGunOperator.fromLivingEntity(context.subject()).getSynShootCoolDown() > 0;
    }

    @BuiltinFunction("tac_is_aim")
    public static boolean isAim(MolangContextView<LivingEntity> context) {
        return IGunOperator.fromLivingEntity(context.subject()).getSynAimingProgress() > 0;
    }

    @BuiltinFunction("tac_is_reload")
    public static boolean isReload(MolangContextView<LivingEntity> context) {
        return IGunOperator.fromLivingEntity(context.subject()).getSynReloadState().getCountDown() > 0;
    }

    @BuiltinFunction("tac_is_melee")
    public static boolean isMelee(MolangContextView<LivingEntity> context) {
        return IGunOperator.fromLivingEntity(context.subject()).getSynMeleeCoolDown() > 0;
    }

    @BuiltinFunction("tac_is_draw")
    public static boolean isDraw(MolangContextView<LivingEntity> context) {
        return IGunOperator.fromLivingEntity(context.subject()).getSynDrawCoolDown() > 0;
    }

    @BuiltinFunction("tac_fire_mode")
    public static String fireMode(MolangContextView<LivingEntity> context) {
        var mode = IGun.getMainHandFireMode(context.subject());
        return mode == FireMode.UNKNOWN ? "" : mode.name();
    }
}
