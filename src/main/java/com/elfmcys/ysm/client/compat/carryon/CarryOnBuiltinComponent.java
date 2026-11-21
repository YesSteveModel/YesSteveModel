package com.elfmcys.ysm.client.compat.carryon;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import java.util.Locale;

@BuiltinComponent(value = "ctrl", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "carryon"))
public final class CarryOnBuiltinComponent implements EntityComponent<LivingEntity> {
    public CarryOnBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction("carryon_is_princess")
    public static boolean isPrincess(MolangContextView<LivingEntity> context) {
        return CarryOnCompat.isCarryOnPrincess(context.subject());
    }

    @BuiltinFunction("carryon_type")
    public static String type(MolangContextView<LivingEntity> context) {
        if (!(context.subject() instanceof Player player))
            return "";
        var type = CarryOnInnerCompat.getCarryOnType(player);
        return type == CarryOnInnerCompat.Type.NONE ? "" : type.name().toLowerCase(Locale.ENGLISH);
    }
}
