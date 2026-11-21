package com.elfmcys.ysm.client.compat.swem;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "swem"))
public final class SwemBuiltinComponent implements EntityComponent<LivingEntity> {
    public SwemBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction("swem_is_ride")
    public static boolean isRiding(MolangContextView<LivingEntity> context) {
        return SwemCompatInner.isRiding(context.subject());
    }

    @BuiltinFunction("swem_state")
    public static String state(MolangContextView<LivingEntity> context) {
        var animation = SwemCompatInner.getAnimation(context.subject());
        return animation == null ? "" : animation.substring("swem:".length());
    }
}
