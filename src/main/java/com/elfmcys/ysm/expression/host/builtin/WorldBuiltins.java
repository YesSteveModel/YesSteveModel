package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.registries.ForgeRegistries;

@BuiltinComponent("ysm")
public final class WorldBuiltins {
    private static final int MAX_RELATIVE_BLOCK_POS = 5;

    private WorldBuiltins() {}

    @BuiltinFunction("dimension_name")
    public static String ysmDimensionName(MolangContextView<Entity> context) {
        return context.subject().level().dimension().location().toString();
    }

    @BuiltinFunction("block_light")
    public static float ysmBlockLight(MolangContextView<Entity> context) {
        return context.subject().level().getBrightness(LightLayer.BLOCK, context.subject().blockPosition());
    }

    @BuiltinFunction("sky_light")
    public static float ysmSkyLight(MolangContextView<Entity> context) {
        return context.subject().level().getBrightness(LightLayer.SKY, context.subject().blockPosition());
    }

    @BuiltinFunction("relative_block_name")
    public static String relativeBlockName(MolangContextView<Entity> context, float x, float y, float z) {
        var id = relativeBlockId(context.subject(), x, y, z);
        return id == null ? "" : id.toString();
    }

    @BuiltinFunction(value = "relative_block_name_any", minArgs = 4)
    public static Boolean relativeBlockNameAny(MolangContextView<Entity> context, int argc, float x, float y, float z,
        String... names) {
        var id = relativeBlockId(context.subject(), x, y, z);
        if (id == null)
            return null;
        for (String name : names) {
            if (id.equals(ResourceLocation.tryParse(name)))
                return true;
        }
        return false;
    }

    private static ResourceLocation relativeBlockId(Entity entity, float x, float y, float z) {
        if (Math.abs(x) > 5 || Math.abs(y) > 5 || Math.abs(z) > 5)
            return null;
        var pos = new BlockPos((int)Math.round(entity.getX() + x - 0.5d), (int)Math.round(entity.getY() + y - 0.5d),
            (int)Math.round(entity.getZ() + z - 0.5d));
        return ForgeRegistries.BLOCKS.getKey(entity.level().getBlockState(pos).getBlock());
    }

    @BuiltinFunction("is_open_air")
    public static boolean isOpenAir(MolangContextView<Entity> context) {

        var entity = context.subject();
        var position = entity.blockPosition();
        return entity.level().canSeeSky(position)
            && entity.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, position).getY() <= position.getY();
    }

    @BuiltinFunction("weather")
    public static float weather(MolangContextView<Entity> context) {

        var level = context.subject().level();
        return level.isThundering() ? 2 : level.isRaining() ? 1 : 0;
    }

    @BuiltinFunction("biome_category")
    public static void biomeCategory(MolangContextView<Entity> context) {}

    @BuiltinFunction("hit_target_type")
    public static String hitTargetType(MolangContextView<LocalPlayer> context) {
        var hit = Minecraft.getInstance().hitResult;
        if (hit == null)
            return "";
        return switch (hit.getType()) {
            case BLOCK -> "block";
            case ENTITY -> "entity";
            default -> "";
        };
    }

    @BuiltinFunction("hit_target_id")
    public static String hitTargetId(MolangContextView<LocalPlayer> context) {
        var minecraft = Minecraft.getInstance();
        var hit = minecraft.hitResult;
        if (hit instanceof BlockHitResult block && hit.getType() != HitResult.Type.MISS && minecraft.level != null) {
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(minecraft.level.getBlockState(block.getBlockPos()).getBlock());
            return id == null ? "" : id.toString();
        }
        if (hit instanceof EntityHitResult entity) {
            var id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType());
            return id == null ? "" : id.toString();
        }
        return "";
    }

    @BuiltinFunction(value = "relative_block_has_any_tag", namespace = "query", minArgs = 4)
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

    @BuiltinFunction(value = "relative_block_has_all_tags", namespace = "query", minArgs = 4)
    public static Boolean relativeBlockHasAllTags(MolangContextView<Entity> context, int argc, float x, float y,
        float z, String... names) {
        if (Math.abs(x) > MAX_RELATIVE_BLOCK_POS || Math.abs(y) > MAX_RELATIVE_BLOCK_POS
            || Math.abs(z) > MAX_RELATIVE_BLOCK_POS)
            return null;
        var entity = context.subject();
        var pos = new BlockPos((int)Math.round(entity.getX() + x - 0.5d), (int)Math.round(entity.getY() + y - 0.5d),
            (int)Math.round(entity.getZ() + z - 0.5d));
        var block = entity.level().getBlockState(pos);
        var tags = ForgeRegistries.BLOCKS.tags();
        if (tags == null)
            return null;
        for (String name : names) {
            var id = ResourceLocation.tryParse(name);
            if (id == null)
                return null;
            if (!block.is(tags.createTagKey(id)))
                return false;
        }
        return true;
    }

    @BuiltinFunction(value = "biome_has_all_tags", namespace = "query", minArgs = 1)
    public static Boolean biomeHasAllTags(MolangContextView<Entity> context, int argc, String... names) {
        return biomeTags(context, names, true);
    }

    @BuiltinFunction(value = "biome_has_any_tag", namespace = "query", minArgs = 1)
    public static Boolean biomeHasAnyTag(MolangContextView<Entity> context, int argc, String... names) {
        return biomeTags(context, names, false);
    }

    private static Boolean biomeTags(MolangContextView<Entity> context, String[] names, boolean all) {
        var tags = ForgeRegistries.BIOMES.tags();
        if (tags == null)
            return null;
        var entity = context.subject();
        var biome = entity.level().getBiome(entity.blockPosition());
        for (String name : names) {
            var id = ResourceLocation.tryParse(name);
            if (id == null)
                return null;
            if (biome.is(tags.createTagKey(id)) != all)
                return !all;
        }
        return all;
    }

    @BuiltinFunction(value = "actor_count", namespace = "query")
    public static float queryActorCount(MolangContextView<Entity> context) {
        return context.subject().level() instanceof ClientLevel level ? level.getEntityCount() : 0;
    }

    @BuiltinFunction(value = "moon_phase", namespace = "query")
    public static float queryMoonPhase(MolangContextView<Entity> context) {
        return context.subject().level().getMoonPhase();
    }

    @BuiltinFunction(value = "time_of_day", namespace = "query")
    public static float queryTimeOfDay(MolangContextView<Entity> context) {
        return ((float)(context.subject().level().getDayTime() + 6000L) / 24000) % 1;
    }

    @BuiltinFunction(value = "time_stamp", namespace = "query")
    public static float timeStamp(MolangContextView<Entity> context) {

        return context.subject().level().getDayTime();
    }
}
