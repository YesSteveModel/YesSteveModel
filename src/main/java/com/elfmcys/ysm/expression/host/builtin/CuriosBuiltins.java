package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.compat.curios.CuriosCompat;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;

/** Curios-owned callable surface, loaded only after the Curios group is admitted. */
@BuiltinComponent(value = "ysm", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "curios"))
public final class CuriosBuiltins implements EntityComponent<LivingEntity> {
    public CuriosBuiltins(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction(value = "has_any_curios", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 255)
    public static Boolean hasAnyCurios(MolangContextView<LivingEntity> context, int argc, String slotType,
        String... names) {
        if (StringUtils.isEmpty(slotType))
            return null;
        var items = new ReferenceOpenHashSet<Item>();
        for (String name : names) {
            var id = ResourceLocation.tryParse(name);
            if (id == null)
                return null;
            var item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null)
                items.add(item);
        }
        return CuriosCompat.hasAnyItemEquipped(context.subject(), slotType, items);
    }
}
