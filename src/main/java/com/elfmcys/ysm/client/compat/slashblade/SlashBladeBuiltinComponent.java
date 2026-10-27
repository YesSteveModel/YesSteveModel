package com.elfmcys.ysm.client.compat.slashblade;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.builtin.BuiltinComponent;
import com.elfmcys.ysm.expression.host.builtin.BuiltinFunction;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "slashblade"))
public final class SlashBladeBuiltinComponent implements EntityComponent<LivingEntity> {
    public SlashBladeBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction(value = "slashblade_animation", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static String animation(MolangContextView<LivingEntity> context) {
        return SlashBladeCompat.getAnimationName(context.subject());
    }
}
