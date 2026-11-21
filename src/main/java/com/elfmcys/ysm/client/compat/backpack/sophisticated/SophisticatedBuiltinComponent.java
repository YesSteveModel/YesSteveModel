package com.elfmcys.ysm.client.compat.backpack.sophisticated;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl",
    dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "sophisticatedbackpacks"))
public final class SophisticatedBuiltinComponent implements EntityComponent<LivingEntity> {
    public SophisticatedBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction("has_sophisticated_backpack")
    public static boolean hasBackpack(MolangContextView<LivingEntity> context) {
        return SophisticatedCompat.isInstalled() && SophisticatedCompat.getBackpackItemStack(context.subject()) != null;
    }
}
