package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.annotation.MolangNullable;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.mclib.utils.Interpolations;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.lwjgl.stb.STBPerlin;

@BuiltinComponent("math")
public final class AdditionalMathBuiltins {
    private AdditionalMathBuiltins() {}

    @BuiltinFunction(value = "ceil", alwaysOrigin = true)
    public static float ceil(float value) {
        return Mth.ceil(value);
    }

    @BuiltinFunction(value = "trunc", alwaysOrigin = true)
    public static float trunc(float value) {
        return value < 0 ? Mth.ceil(value) : Mth.floor(value);
    }

    @BuiltinFunction(value = "ln", alwaysOrigin = true)
    public static float ln(float value) {
        return finite(Math.log(value));
    }

    @BuiltinFunction(value = "acos", alwaysOrigin = true)
    public static float acos(float value) {
        return finite(Math.acos(value));
    }

    @BuiltinFunction(value = "asin", alwaysOrigin = true)
    public static float asin(float value) {
        return finite(Math.asin(value));
    }

    @BuiltinFunction(value = "atan", alwaysOrigin = true)
    public static float atan(float value) {
        return finite(Math.atan(value));
    }

    @BuiltinFunction(value = "atan2", alwaysOrigin = true)
    public static float atan2(float y, float x) {
        return finite(Mth.atan2(y, x));
    }

    @BuiltinFunction(value = "lerprotate", alwaysOrigin = true)
    public static float lerpRotate(float from, float to, float position) {
        return finite(Interpolations.lerpYaw(from, to, position));
    }

    @BuiltinFunction(value = "hermite_blend", alwaysOrigin = true)
    public static float hermiteBlend(float value) {
        double rounded = Mth.ceil(value);
        return Mth.floor(3.0d * rounded * rounded - 2.0d * rounded * rounded * rounded);
    }

    @BuiltinFunction(value = "hermite", alwaysOrigin = true)
    public static float hermite(float value) {
        return hermiteBlend(value);
    }

    @BuiltinFunction(value = "perlin_noise", alwaysOrigin = true, namespace = "ysm")
    @MolangNullable
    public static float perlinNoise(MolangContextView<Entity> context, int argc, int seed, float x, float... extra) {
        float y = extra.length > 0 ? extra[0] : 0;
        float z = extra.length > 1 ? extra[1] : 0;
        return STBPerlin.stb_perlin_noise3_seed(x, y, z, 0, 0, 0, seed);
    }

    private static float finite(double value) {
        float result = (float)value;
        return Float.isFinite(result) ? result : 0;
    }
}
