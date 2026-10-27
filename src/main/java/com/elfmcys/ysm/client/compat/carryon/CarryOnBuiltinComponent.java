package com.elfmcys.ysm.client.compat.carryon;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.builtin.BuiltinComponent;
import com.elfmcys.ysm.expression.host.builtin.BuiltinFunction;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "carryon"))
public final class CarryOnBuiltinComponent implements EntityComponent<LivingEntity> {
    public CarryOnBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction(value = "carryon_is_princess", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean isPrincess(MolangContextView<LivingEntity> context) {
        return CarryOnCompat.isCarryOnPrincess(context.subject());
    }
}
