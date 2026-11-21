package com.elfmcys.ysm.client.compat.curios;

import com.elfmcys.ysm.api.annotation.MolangDependency;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.animation.debug.DebugSource;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;
import top.theillusivec4.curios.api.CuriosApi;

/** Curios-owned callable surface, loaded only after the Curios group is admitted. */
@BuiltinComponent(value = "ysm", dependencies = @MolangDependency(modId = "curios"))
public final class CuriosBuiltins implements EntityComponent<LivingEntity> {
    private DebugSource debug;

    public CuriosBuiltins(AnimatableEntity<LivingEntity> owner) {}

    @Override
    public void beginEval(AnimationEvent<?> event) {
        debug = event.getDebugSource();
    }

    @Override
    public void endEval() {
        debug = null;
    }

    @BuiltinFunction("has_any_curios")
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

    @BuiltinFunction(value = "has_any_curios_with_all_tags", minArgs = 2)
    public static Boolean hasAnyCuriosWithAllTags(MolangContextView<LivingEntity> context, int argc, String slotType,
        String... names) {
        return hasAnyCuriosWithTags(context, slotType, names, true);
    }

    @BuiltinFunction(value = "has_any_curios_with_any_tag", minArgs = 2)
    public static Boolean hasAnyCuriosWithAnyTag(MolangContextView<LivingEntity> context, int argc, String slotType,
        String... names) {
        return hasAnyCuriosWithTags(context, slotType, names, false);
    }

    private static Boolean hasAnyCuriosWithTags(MolangContextView<LivingEntity> context, String slotType,
        String[] names, boolean all) {
        var registry = ForgeRegistries.ITEMS.tags();
        if (StringUtils.isEmpty(slotType) || registry == null)
            return null;
        var tags = new ArrayList<net.minecraft.tags.TagKey<Item>>(names.length);
        for (String name : names) {
            var id = ResourceLocation.tryParse(name);
            if (id == null)
                return null;
            tags.add(registry.createTagKey(id));
        }
        return all ? CuriosCompat.hasAnyItemEquippedWithAllTag(context.subject(), slotType, tags)
            : CuriosCompat.hasAnyItemEquippedWithAnyTag(context.subject(), slotType, tags);
    }

    @BuiltinFunction(value = "dump_curios", alwaysOrigin = true, action = true)
    public void dumpCurios(MolangContextView<LivingEntity> context) {
        if (debug == null)
            return;
        CuriosApi.getCuriosInventory(context.subject()).ifPresent(inventory -> {
            for (var entry : inventory.getCurios().entrySet()) {
                debug.print(Component.literal("-------- Type ").append(ComponentUtils.copyOnClickText(entry.getKey()))
                    .append(" --------"));
                debug.print("");
                CuriosCompatInner.searchCuriosHandler(entry.getValue(), stack -> {
                    print("Display ", stack.getHoverName().getString(99));
                    var holder = stack.getItemHolder();
                    holder.unwrapKey().ifPresent(key -> print("Name ", key.location().toString()));
                    holder.tags().forEach(tag -> print("Tag ", tag.location().toString()));
                    debug.print("");
                    return false;
                });
            }
        });
    }

    private void print(String prefix, String value) {
        debug.print(Component.literal(prefix).append(ComponentUtils.copyOnClickText(value)));
    }
}
