package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.MavenVersionStringHelper;
import net.minecraftforge.fml.ModList;

@BuiltinComponent("ysm")
public final class ModBuiltins implements EntityComponent<Entity> {
    public ModBuiltins(AnimatableEntity<Entity> owner) {}

    @BuiltinFunction(value = "mod_version", alwaysOrigin = true)
    public String modVersion(MolangContextView<Entity> context, String modId) {
        var mods = ModList.get();
        var container = mods == null ? null : mods.getModContainerById(modId).orElse(null);
        return container == null ? ""
            : MavenVersionStringHelper.artifactVersionToString(container.getModInfo().getVersion());
    }
}
