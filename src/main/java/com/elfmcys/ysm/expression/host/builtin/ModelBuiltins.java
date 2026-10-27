package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.api.molang.MolangActionView;
import com.elfmcys.ysm.expression.host.MolangVector;
import net.minecraft.world.entity.Entity;

/** Core bone, physics, sound, particle, synchronization and input built-ins. */
@BuiltinComponent(value = "ysm")
public final class ModelBuiltins implements EntityComponent<Entity> {
    public ModelBuiltins(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner) {}

    @BuiltinFunction(value = "bone_rot", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public static MolangVector boneRotation(MolangContextView<Entity> context, String name) {
        return context.boneRotation(name);
    }

    @BuiltinFunction(value = "bone_pivot_abs", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public static MolangVector bonePivotAbsolute(MolangContextView<Entity> context, String name) {
        return context.bonePivotAbsolute(name);
    }

    @BuiltinFunction(value = "bone_glow", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 2,
        maxArgs = 2)
    public static void boneGlow(MolangActionView<Entity> context, String name, float glow) {
        context.boneGlow(name, glow);
    }

    @BuiltinFunction(value = "bone_transparency", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 2,
        maxArgs = 2)
    public static void boneTransparency(MolangActionView<Entity> context, String name, float alpha) {
        context.boneTransparency(name, alpha);
    }

    @BuiltinFunction(value = "bone_color", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 4,
        maxArgs = 4)
    public static void boneColor(MolangActionView<Entity> context, String name, float red, float green, float blue) {
        context.boneColor(name, red, green, blue);
    }

    @BuiltinFunction(value = "first_order", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2,
        maxArgs = 255)
    public static float firstOrder(MolangContextView<Entity> context, int argc, String name, float input,
        float... coefficients) {
        return context.firstOrder(name, input, coefficients);
    }

    @BuiltinFunction(value = "second_order", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2,
        maxArgs = 255)
    public static float secondOrder(MolangContextView<Entity> context, int argc, String name, float input,
        float... coefficients) {
        return context.secondOrder(name, input, coefficients);
    }

    @BuiltinFunction(value = "play_sound", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 2,
        maxArgs = 5)
    public static void playSound(MolangActionView<Entity> context, int argc, int id, String name,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float mode,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float volume,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float pitch) {
        context.playSound(id, name, (int)mode, volume, pitch);
    }

    @BuiltinFunction(value = "play_sound", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 2,
        maxArgs = 5)
    public static void playSound(MolangActionView<Entity> context, int argc, String id, String name,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float mode,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float volume,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float pitch) {
        context.playSound(id, name, (int)mode, volume, pitch);
    }

    @BuiltinFunction(value = "stop_sound", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 2)
    public static void stopSound(MolangActionView<Entity> context, int argc, int id,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        context.stopSound(id, global);
    }

    @BuiltinFunction(value = "stop_sound", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 2)
    public static void stopSound(MolangActionView<Entity> context, int argc, String id,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        context.stopSound(id, global);
    }

    @BuiltinFunction(value = "stop_all_sounds", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 0,
        maxArgs = 1)
    public static void stopAllSounds(MolangActionView<Entity> context, int argc,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        context.stopAllSounds(global);
    }

    @BuiltinFunction(value = "particle", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 255)
    public static void particle(MolangActionView<Entity> context, int argc, String name, float... values) {
        context.particle(name, values);
    }

    @BuiltinFunction(value = "abs_particle", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 255)
    public static void absoluteParticle(MolangActionView<Entity> context, int argc, String name, float... values) {
        context.absoluteParticle(name, values);
    }

    @BuiltinFunction(value = "sync", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 0, maxArgs = 16)
    public static void sync(MolangActionView<Entity> context, int argc, float... values) {
        context.sync(values);
    }

    @BuiltinFunction(value = "defer", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 255)
    public static void defer(MolangActionView<Entity> context, int argc, float delay, float... values) {
        context.defer(delay, values);
    }

    @BuiltinFunction(value = "keyboard", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 255)
    public static boolean keyboard(MolangContextView<Entity> context, int argc, float... keys) {
        return context.keyboard(keys);
    }

    @BuiltinFunction(value = "mod_version", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public static String modVersion(MolangContextView<Entity> context, String modId) {
        return context.modVersion(modId);
    }

}
