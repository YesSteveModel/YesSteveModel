package com.elfmcys.ysm.client.compat.ironsspellbooks;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import net.minecraft.client.player.AbstractClientPlayer;

@BuiltinComponent(value = "ctrl",
    dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "irons_spellbooks"))
public final class IronsSpellBooksBuiltinComponent implements EntityComponent<AbstractClientPlayer> {
    public IronsSpellBooksBuiltinComponent(
        com.elfmcys.ysm.client.entity.AnimatableEntity<AbstractClientPlayer> owner) {}

    @BuiltinFunction("iss_animation")
    public static String animation(MolangContextView<AbstractClientPlayer> context) {
        return IronsSpellBooksCompatInner.getAnimation(context.subject(), null);
    }
}
