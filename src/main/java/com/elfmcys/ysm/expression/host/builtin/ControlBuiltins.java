package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.client.animation.condition.InnerClassify;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.api.molang.MolangActionView;
import com.elfmcys.ysm.util.EquipmentUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Core control, equipment, random and world-query built-ins. */
@BuiltinComponent(value = "ctrl")
public final class ControlBuiltins implements EntityComponent<Entity> {
    private static final int MAX_RELATIVE_BLOCK_POS = 5;

    public ControlBuiltins(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner) {}

    @BuiltinFunction(value = "armor", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2,
        maxArgs = 3)
    public static Boolean armor(MolangContextView<Entity> context, int argc, String slot, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean useTag) {
        return itemCheck(context, slot, filter, ItemCheck.ARMOR);
    }

    @BuiltinFunction(value = "hold", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2, maxArgs = 3)
    public static Boolean hold(MolangContextView<Entity> context, int argc, String slot, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean useTag) {
        return itemCheck(context, slot, filter, ItemCheck.HOLD);
    }

    @BuiltinFunction(value = "swing", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2,
        maxArgs = 3)
    public static Boolean swing(MolangContextView<Entity> context, int argc, String slot, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean useTag) {
        return itemCheck(context, slot, filter, ItemCheck.SWING);
    }

    @BuiltinFunction(value = "use", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2, maxArgs = 3)
    public static Boolean use(MolangContextView<Entity> context, int argc, String slot, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean useTag) {
        return itemCheck(context, slot, filter, ItemCheck.USE);
    }

    @BuiltinFunction(value = "indicate_reload", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 0,
        maxArgs = 0)
    public static void indicateReload(MolangActionView<Entity> context) {
        context.indicateReload();
    }

    @BuiltinFunction(value = "reset", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 0, maxArgs = 0)
    public static void reset(MolangActionView<Entity> context) {
        context.resetController();
    }

    @BuiltinFunction(value = "set_animation", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 2)
    public static void setAnimation(MolangActionView<Entity> context, int argc, String name,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float loop) {
        context.setAnimation(name, loop);
    }

    @BuiltinFunction(value = "set_beginning_transition_length", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION,
        minArgs = 1, maxArgs = 1)
    public static void setBeginningTransitionLength(MolangActionView<Entity> context, float length) {
        context.setBeginningTransitionLength(length);
    }

    @BuiltinFunction(value = "random_integer", namespace = "math",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2, maxArgs = 2)
    public static float randomInteger(MolangContextView<Entity> context, float minimum, float maximum) {

        var lower = (int)minimum;
        var upper = (int)maximum;
        if (lower > upper) {
            var swap = lower;
            lower = upper;
            upper = swap;
        }
        return lower + context.randomInt(upper - lower);
    }

    @BuiltinFunction(value = "random", namespace = "math", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT,
        minArgs = 2, maxArgs = 3)
    public static float random(MolangContextView<Entity> context, int argc, float minimum, float maximum,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float unused) {

        if (minimum > maximum) {
            var swap = minimum;
            minimum = maximum;
            maximum = swap;
        }
        return MathBuiltins.finite(minimum + context.random() * (maximum - minimum));
    }

    @BuiltinFunction(value = "is_item_name_any", namespace = "query",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2, maxArgs = 255)
    public static Boolean isItemNameAny(MolangContextView<Entity> context, int argc, String slotName, String... names) {
        if (!(context.subject() instanceof LivingEntity living))
            return null;
        var slot = slot(slotName);
        if (slot == null)
            return null;
        var item = EquipmentUtil.getEquippedItem(living, slot);
        var actual = ForgeRegistries.ITEMS.getKey(item.getItem());
        if (item.isEmpty() || actual == null)
            return false;
        for (String name : names) {
            var expected = ResourceLocation.tryParse(name);
            if (expected == null)
                return null;
            if (actual.equals(expected))
                return true;
        }
        return false;
    }

    @BuiltinFunction(value = "relative_block_has_any_tag", namespace = "query",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 4, maxArgs = 255)
    public static Boolean relativeBlockHasAnyTag(MolangContextView<Entity> context, int argc, float offsetX,
        float offsetY, float offsetZ, String... names) {
        if (Math.abs(offsetX) > MAX_RELATIVE_BLOCK_POS || Math.abs(offsetY) > MAX_RELATIVE_BLOCK_POS
            || Math.abs(offsetZ) > MAX_RELATIVE_BLOCK_POS)
            return null;
        var entity = context.subject();
        var pos = new BlockPos((int)Math.round(entity.getX() + offsetX - 0.5d),
            (int)Math.round(entity.getY() + offsetY - 0.5d), (int)Math.round(entity.getZ() + offsetZ - 0.5d));
        var block = entity.level().getBlockState(pos);
        var tags = ForgeRegistries.BLOCKS.tags();
        if (tags == null)
            return null;
        for (String name : names) {
            var id = ResourceLocation.tryParse(name);
            if (id == null)
                return null;
            if (block.is(tags.createTagKey(id)))
                return true;
        }
        return false;
    }

    private static Boolean itemCheck(MolangContextView<Entity> context, String slotName, String input,
        ItemCheck operation) {

        if (!(context.subject() instanceof LivingEntity living)) {
            return null;
        }
        var slot = slot(slotName);
        if (slot == null || operation == ItemCheck.ARMOR != slot.isArmor()) {
            return null;
        }
        if (StringUtils.isBlank(input)) {
            return false;
        }
        if (operation == ItemCheck.SWING && (!living.swinging || living.isSleeping())
            || operation == ItemCheck.USE && (!living.isUsingItem() || living.isSleeping())) {
            return false;
        }
        var item = EquipmentUtil.getEquippedItem(living, slot);
        if (item.isEmpty() && input.equals("empty")) {
            return true;
        }
        if (input.length() < 2) {
            return false;
        }
        var value = input.substring(1);
        if (input.startsWith("$")) {
            var id = ForgeRegistries.ITEMS.getKey(item.getItem());
            return id != null && value.equals(id.toString());
        }
        if (input.startsWith("#")) {
            var id = ResourceLocation.tryParse(value);
            var tags = ForgeRegistries.ITEMS.tags();
            return id != null && tags != null && item.is(tags.createTagKey(id));
        }
        if (operation != ItemCheck.ARMOR && input.startsWith(":")) {
            var classify = InnerClassify.getClassify(item);
            return StringUtils.isNotBlank(classify) && classify.equals(value)
                || item.getUseAnimation().name().toLowerCase(Locale.ENGLISH).equals(value);
        }
        return false;
    }

    @Nullable
    private static EquipmentSlot slot(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ENGLISH)) {
            case "chest" -> EquipmentSlot.CHEST;
            case "feet" -> EquipmentSlot.FEET;
            case "head" -> EquipmentSlot.HEAD;
            case "legs" -> EquipmentSlot.LEGS;
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }

    private enum ItemCheck {
        ARMOR, HOLD, SWING, USE
    }
}
