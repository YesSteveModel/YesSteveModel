package com.elfmcys.ysm.expression.host.builtin;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.expression.host.EntityComponent;
import net.minecraft.world.entity.player.Player;

@BuiltinComponent("ysm")
public final class PlayerBuiltins implements EntityComponent<Player> {
    private final AnimatableEntity<Player> owner;
    public PlayerBuiltins(AnimatableEntity<Player> owner) { this.owner = owner; }

    @BuiltinFunction("food_level")
    public float foodLevel(MolangContextView<Player> context) {
        if (owner instanceof PlayerAnimatableCapability p && !p.isLocalPlayer())
            return p.getStateTracker().foodLevel();
        return context.subject().getFoodData().getFoodLevel();
    }
}
