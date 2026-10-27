package com.elfmcys.ysm.client.compat.tacz;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.builtin.BuiltinComponent;
import com.elfmcys.ysm.expression.host.builtin.BuiltinFunction;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "tacz"))
public final class TaczBuiltinComponent implements EntityComponent<LivingEntity> {
    public TaczBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction(value = "tac_hold_gun", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean holdsGun(MolangContextView<LivingEntity> context) {
        return TACZCompat.isMainhandHoldingGun(context.subject());
    }
}
