package com.elfmcys.ysm.client.compat.parcool;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.builtin.BuiltinComponent;
import com.elfmcys.ysm.expression.host.builtin.BuiltinFunction;
import net.minecraft.world.entity.player.Player;

@BuiltinComponent(value = "ctrl", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "parcool"))
public final class ParCoolBuiltinComponent implements EntityComponent<Player> {
    public ParCoolBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<Player> owner) {}

    @BuiltinFunction(value = "parcool_state", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static String state(MolangContextView<Player> context) {
        String animation = ParCoolCompat.getAnimation(context.subject());
        if (animation == null) {
            animation = "";
        } else if (animation.startsWith("parcool:")) {
            animation = animation.substring("parcool:".length());
        }
        return animation;
    }
}
