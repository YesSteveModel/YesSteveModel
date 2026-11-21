package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.util.EquipmentUtil;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

@BuiltinComponent("ysm")
public final class LivingBuiltins implements EntityComponent<Player> {
    private AnimationEvent<?> event;

    public LivingBuiltins(AnimatableEntity<Player> owner) {}

    @Override
    public void beginEval(AnimationEvent<?> event) {
        this.event = event;
    }

    @Override
    public void endEval() {
        event = null;
    }

    @BuiltinFunction("food_level")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float foodLevel(MolangContextView<Player> context) {
        return context.subject() instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).foodLevel()
            : context.subject().getFoodData().getFoodLevel();
    }

    @BuiltinFunction(value = "player_level", namespace = "query")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float level(MolangContextView<Player> context) {
        return context.subject() instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).experienceLevel()
            : context.subject().experienceLevel;
    }

    @BuiltinFunction(value = "first_person_mod_hide", alwaysOrigin = true)
    public boolean firstPersonModHide(MolangContextView<Player> context) {
        var render = event.getRenderContext();
        return !render.level() && render.firstPersonMod()
            && RenderBuiltins.personView(context.subject(), render) == CameraType.FIRST_PERSON.ordinal();
    }

    @BuiltinFunction(value = "cape_flap_amount", namespace = "query")
    public static float capeFlapAmount(MolangContextView<Player> context) {
        var player = context.subject();
        float partial = context.inputs().partialTick();
        float x =
            (float)(Mth.lerp(partial, player.xCloakO, player.xCloak) - Mth.lerp(partial, player.xo, player.getX()));
        float y =
            (float)(Mth.lerp(partial, player.yCloakO, player.yCloak) - Mth.lerp(partial, player.yo, player.getY()));
        float z =
            (float)(Mth.lerp(partial, player.zCloakO, player.zCloak) - Mth.lerp(partial, player.zo, player.getZ()));
        float rotation = (player.yBodyRotO + (player.yBodyRot - player.yBodyRotO)) * ((float)Math.PI / 180f);
        float vertical = Mth.clamp(y * 10, -6, 32);
        float forward = Mth.clamp((x * Mth.sin(rotation) - z * Mth.cos(rotation)) * 100, 0, 150);
        float bob = Mth.lerp(partial, player.oBob, player.bob);
        vertical += Mth.sin(Mth.lerp(partial, player.walkDistO, player.walkDist) * 6) * 32 * bob;
        if (player.isCrouching())
            vertical += 25;
        float result = Mth.clamp((6 + forward / 2 + vertical) / 108, 0, 1);
        return Float.isFinite(result) ? result : 0;
    }

    @BuiltinFunction(value = "has_cape", namespace = "query")
    public static boolean hasCape(MolangContextView<net.minecraft.client.player.AbstractClientPlayer> context) {
        var player = context.subject();
        return player.isCapeLoaded() && !player.isInvisible()
            && player.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.CAPE)
            && player.getCloakTextureLocation() != null;
    }

    @BuiltinFunction("attack_damage")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float attackDamage(MolangContextView<Player> context) {
        return (float)context.subject()
            .getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
    }

    @BuiltinFunction("attack_speed")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float attackSpeed(MolangContextView<Player> context) {
        return (float)context.subject()
            .getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
    }

    @BuiltinFunction("attack_knockback")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float attackKnockback(MolangContextView<Player> context) {
        return (float)context.subject()
            .getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK);
    }

    @BuiltinFunction("movement_speed")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float movementSpeed(MolangContextView<Player> context) {
        return (float)context.subject()
            .getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
    }

    @BuiltinFunction("knockback_resistance")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float knockbackResistance(MolangContextView<Player> context) {
        return (float)context.subject()
            .getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
    }

    @BuiltinFunction("luck")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float luck(MolangContextView<Player> context) {
        return (float)context.subject().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.LUCK);
    }

    @BuiltinFunction("block_reach")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float blockReach(MolangContextView<Player> context) {
        return (float)context.subject().getAttributeValue(net.minecraftforge.common.ForgeMod.BLOCK_REACH.get());
    }

    @BuiltinFunction("entity_reach")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float entityReach(MolangContextView<Player> context) {
        return (float)context.subject().getAttributeValue(net.minecraftforge.common.ForgeMod.ENTITY_REACH.get());
    }

    @BuiltinFunction("swim_speed")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float swimSpeed(MolangContextView<Player> context) {
        return (float)context.subject().getAttributeValue(net.minecraftforge.common.ForgeMod.SWIM_SPEED.get());
    }

    @BuiltinFunction("entity_gravity")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float entityGravity(MolangContextView<Player> context) {
        return (float)context.subject().getAttributeValue(net.minecraftforge.common.ForgeMod.ENTITY_GRAVITY.get());
    }

    @BuiltinFunction("step_height_addition")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float stepHeightAddition(MolangContextView<Player> context) {
        return (float)context.subject()
            .getAttributeValue(net.minecraftforge.common.ForgeMod.STEP_HEIGHT_ADDITION.get());
    }

    @BuiltinFunction("nametag_distance")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float nametagDistance(MolangContextView<Player> context) {
        return (float)context.subject().getAttributeValue(net.minecraftforge.common.ForgeMod.NAMETAG_DISTANCE.get());
    }

    @BuiltinFunction("elytra_rot_x")
    public static float elytraRotX(MolangContextView<net.minecraft.client.player.AbstractClientPlayer> context) {
        float result = (float)Math.toDegrees(context.subject().elytraRotX);
        return Float.isFinite(result) ? result : 0;
    }

    @BuiltinFunction("elytra_rot_y")
    public static float elytraRotY(MolangContextView<net.minecraft.client.player.AbstractClientPlayer> context) {
        float result = (float)Math.toDegrees(context.subject().elytraRotY);
        return Float.isFinite(result) ? result : 0;
    }

    @BuiltinFunction("elytra_rot_z")
    public static float elytraRotZ(MolangContextView<AbstractClientPlayer> context) {

        return (float)Math.toDegrees(context.subject().elytraRotZ);
    }

    @BuiltinFunction("is_fishing")
    public static boolean isFishing(MolangContextView<Entity> context) {

        Entity entity = context.subject();
        return entity instanceof Player player && player.fishing != null;
    }

    @BuiltinFunction("has_left_shoulder_parrot")
    public static boolean hasLeftParrot(MolangContextView<Player> context) {
        return !context.subject().getShoulderEntityLeft().isEmpty();
    }

    @BuiltinFunction("has_right_shoulder_parrot")
    public static boolean hasRightParrot(MolangContextView<Player> context) {
        return !context.subject().getShoulderEntityRight().isEmpty();
    }

    @BuiltinFunction("left_shoulder_parrot_variant")
    public static String leftParrotVariant(MolangContextView<Player> context) {
        return parrotVariant(context.subject().getShoulderEntityLeft());
    }

    @BuiltinFunction("right_shoulder_parrot_variant")
    public static String rightParrotVariant(MolangContextView<Player> context) {
        return parrotVariant(context.subject().getShoulderEntityRight());
    }

    private static String parrotVariant(CompoundTag tag) {
        return EntityType.byString(tag.getString("id")).filter(type -> type == EntityType.PARROT)
            .map(type -> Parrot.Variant.byId(tag.getInt("Variant")).name().toLowerCase(Locale.ENGLISH)).orElse("empty");
    }

    @BuiltinFunction("armor_value")
    public static float armorValue(MolangContextView<Entity> context) {

        var living = living(context.subject());
        return living == null ? 0 : living.getArmorValue();
    }

    @BuiltinFunction("has_boots")
    public static boolean hasBoots(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.FEET);
    }

    @BuiltinFunction("has_chest_plate")
    public static boolean hasChestPlate(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.CHEST);
    }

    @BuiltinFunction("has_elytra")
    public static boolean hasElytra(MolangContextView<Entity> context) {

        var living = living(context.subject());
        return living != null && !EquipmentUtil.getEquippedElytraItem(living).isEmpty();
    }

    @BuiltinFunction("has_helmet")
    public static boolean hasHelmet(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.HEAD);
    }

    @BuiltinFunction("has_leggings")
    public static boolean hasLeggings(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.LEGS);
    }

    @BuiltinFunction("has_mainhand")
    public static boolean hasMainhand(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.MAINHAND);
    }

    @BuiltinFunction("has_offhand")
    public static boolean hasOffhand(MolangContextView<Entity> context) {
        return equipped(context, EquipmentSlot.OFFHAND);
    }

    @BuiltinFunction("in_shield_block_cooldown")
    public static Boolean inShieldBlockCooldown(MolangContextView<Entity> context) {
        return context.subject() instanceof net.minecraft.client.player.RemotePlayer
            ? com.elfmcys.ysm.expression.host.MolangContext.projection(context).shieldCooldown() : Boolean.valueOf(
                context.subject() instanceof Player player && player.getCooldowns().isOnCooldown(Items.SHIELD));
    }

    @BuiltinFunction("mainhand_charged_crossbow")
    public static boolean mainhandChargedCrossbow(MolangContextView<Entity> context) {
        return chargedCrossbow(context, InteractionHand.MAIN_HAND);
    }

    @BuiltinFunction("offhand_charged_crossbow")
    public static boolean offhandChargedCrossbow(MolangContextView<Entity> context) {
        return chargedCrossbow(context, InteractionHand.OFF_HAND);
    }

    private static boolean equipped(MolangContextView<Entity> context, EquipmentSlot slot) {

        var living = living(context.subject());
        return living != null && !EquipmentUtil.getEquippedItem(living, slot).isEmpty();
    }

    private static boolean chargedCrossbow(MolangContextView<Entity> context, InteractionHand hand) {

        var living = living(context.subject());
        if (living == null) {
            return false;
        }
        var item = living.getItemInHand(hand);
        return item.is(Items.CROSSBOW) && CrossbowItem.isCharged(item);
    }

    @Nullable
    private static LivingEntity living(Entity entity) {
        return entity instanceof LivingEntity value ? value : null;
    }

    @BuiltinFunction(value = "equipped_enchantment_level", minArgs = 2)
    public static Float equippedEnchantmentLevel(MolangContextView<LivingEntity> context, int argc, String slotName,
        String... names) {
        var slot = switch (slotName.toLowerCase(Locale.ENGLISH)) {
            case "chest" -> EquipmentSlot.CHEST;
            case "feet" -> EquipmentSlot.FEET;
            case "head" -> EquipmentSlot.HEAD;
            case "legs" -> EquipmentSlot.LEGS;
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            default -> null;
        };
        if (slot == null)
            return null;
        var stack = EquipmentUtil.getEquippedItem(context.subject(), slot);
        int sum = 0;
        for (String name : names) {
            var id = ResourceLocation.tryParse(name);
            var enchantment = id == null ? null : ForgeRegistries.ENCHANTMENTS.getValue(id);
            if (enchantment != null)
                sum += stack.getEnchantmentLevel(enchantment);
        }
        return (float)sum;
    }

    @BuiltinFunction(value = "is_eating", namespace = "query")
    public static boolean isEating(MolangContextView<Entity> context) {

        return context.subject() instanceof LivingEntity living && living.getUseItem().getUseAnimation() == UseAnim.EAT;
    }

    @BuiltinFunction(value = "is_item_name_any", namespace = "query", minArgs = 2)
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

    @BuiltinFunction(value = "equipped_item_all_tags", namespace = "query", minArgs = 2)
    public static Boolean equippedItemAllTags(MolangContextView<Entity> context, int argc, String slotName,
        String... names) {
        return equippedTags(context, slotName, names, true);
    }

    @BuiltinFunction(value = "equipped_item_any_tag", namespace = "query", minArgs = 2)
    public static Boolean equippedItemAnyTag(MolangContextView<Entity> context, int argc, String slotName,
        String... names) {
        return equippedTags(context, slotName, names, false);
    }

    private static Boolean equippedTags(MolangContextView<Entity> context, String slotName, String[] names,
        boolean all) {
        if (!(context.subject() instanceof LivingEntity living))
            return null;
        var slot = slot(slotName);
        var tags = ForgeRegistries.ITEMS.tags();
        if (slot == null || tags == null)
            return null;
        var stack = EquipmentUtil.getEquippedItem(living, slot);
        if (stack.isEmpty())
            return false;
        for (String name : names) {
            var id = ResourceLocation.tryParse(name);
            if (id == null)
                return null;
            if (stack.is(tags.createTagKey(id)) != all)
                return !all;
        }
        return all;
    }

    @BuiltinFunction(value = "max_durability", namespace = "query")
    public static Float maxDurability(MolangContextView<Entity> context, String slotName) {
        return durability(context, slotName, false);
    }

    @BuiltinFunction(value = "remaining_durability", namespace = "query")
    public static Float remainingDurability(MolangContextView<Entity> context, String slotName) {
        return durability(context, slotName, true);
    }

    private static Float durability(MolangContextView<Entity> context, String slotName, boolean remaining) {
        if (!(context.subject() instanceof LivingEntity living))
            return null;
        var slot = slot(slotName);
        if (slot == null)
            return null;
        var stack = EquipmentUtil.getEquippedItem(living, slot);
        return (float)(stack.getMaxDamage() - (remaining ? stack.getDamageValue() : 0));
    }

    @Nullable
    static EquipmentSlot slot(String value) {
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

    @BuiltinFunction(value = "is_using_item", namespace = "query")
    public static boolean queryIsUsingItem(MolangContextView<LivingEntity> context) {
        return context.subject().isUsingItem();
    }

    @BuiltinFunction(value = "item_in_use_duration", namespace = "query")
    public static float queryItemInUseDuration(MolangContextView<LivingEntity> context) {
        return context.subject().getTicksUsingItem() / 20.0f;
    }

    @BuiltinFunction(value = "item_max_use_duration", namespace = "query")
    public static float queryItemMaxUseDuration(MolangContextView<LivingEntity> context) {
        return context.subject().getUseItem().isEmpty() ? 0 : context.subject().getUseItem().getUseDuration() / 20.0f;
    }

    @BuiltinFunction(value = "item_remaining_use_duration", namespace = "query")
    public static float queryItemRemainingUseDuration(MolangContextView<LivingEntity> context) {
        return context.subject().getUseItemRemainingTicks() / 20.0f;
    }

    @BuiltinFunction(value = "equipment_count", namespace = "query")
    public static float equipmentCount(MolangContextView<LivingEntity> context) {
        int count = 0;
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (slot.isArmor()
                && !com.elfmcys.ysm.util.EquipmentUtil.getEquippedItem(context.subject(), slot).isEmpty())
                count++;
        }
        return count;
    }
}
