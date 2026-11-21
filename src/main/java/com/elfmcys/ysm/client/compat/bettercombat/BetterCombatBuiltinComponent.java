package com.elfmcys.ysm.client.compat.bettercombat;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import net.minecraft.client.player.AbstractClientPlayer;

@BuiltinComponent(value = "ctrl",
    dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "bettercombat"))
public final class BetterCombatBuiltinComponent implements EntityComponent<AbstractClientPlayer> {
    public BetterCombatBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<AbstractClientPlayer> owner) {}

    @BuiltinFunction("bcombat_attack_animation")
    public static String attackAnimation(MolangContextView<AbstractClientPlayer> context) {
        return BetterCombatCompat.getAttackAnimation(context.subject());
    }
}
