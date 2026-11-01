package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangEffect;
import com.elfmcys.ysm.api.molang.MolangContextView;
import net.minecraft.world.entity.Entity;

/** Source math retains callable identity so invalid calls use ordinary local recovery. */
@BuiltinComponent(value = "math")
public final class MathBuiltins {
    private MathBuiltins() {}

    @BuiltinFunction(value = "abs", effect = MolangEffect.CONTEXT, contextFreeTarget = "abs")
    public static float abs(MolangContextView<Entity> context, float a) {
        return abs(a);
    }

    @BuiltinFunction(value = "clamp", effect = MolangEffect.CONTEXT, contextFreeTarget = "clamp")
    public static float clamp(MolangContextView<Entity> context, float a, float b, float c) {
        return clamp(a, b, c);
    }

    @BuiltinFunction(value = "cos", effect = MolangEffect.CONTEXT, contextFreeTarget = "cos")
    public static float cos(MolangContextView<Entity> context, float a) {
        return cos(a);
    }

    @BuiltinFunction(value = "exp", effect = MolangEffect.CONTEXT, contextFreeTarget = "exp")
    public static float exp(MolangContextView<Entity> context, float a) {
        return exp(a);
    }

    @BuiltinFunction(value = "floor", effect = MolangEffect.CONTEXT, contextFreeTarget = "floor")
    public static float floor(MolangContextView<Entity> context, float a) {
        return floor(a);
    }

    @BuiltinFunction(value = "lerp", effect = MolangEffect.CONTEXT, contextFreeTarget = "lerp")
    public static float lerp(MolangContextView<Entity> context, float a, float b, float t) {
        return lerp(a, b, t);
    }

    @BuiltinFunction(value = "max", effect = MolangEffect.CONTEXT, contextFreeTarget = "max")
    public static float max(MolangContextView<Entity> context, float a, float b) {
        return max(a, b);
    }

    @BuiltinFunction(value = "min", effect = MolangEffect.CONTEXT, contextFreeTarget = "min")
    public static float min(MolangContextView<Entity> context, float a, float b) {
        return min(a, b);
    }

    @BuiltinFunction(value = "mod", effect = MolangEffect.CONTEXT, contextFreeTarget = "mod")
    public static float mod(MolangContextView<Entity> context, float a, float b) {
        return mod(a, b);
    }

    @BuiltinFunction(value = "pow", effect = MolangEffect.CONTEXT, contextFreeTarget = "pow")
    public static float pow(MolangContextView<Entity> context, float a, float b) {
        return pow(a, b);
    }

    @BuiltinFunction(value = "round", effect = MolangEffect.CONTEXT, contextFreeTarget = "round")
    public static float round(MolangContextView<Entity> context, float a) {
        return round(a);
    }

    @BuiltinFunction(value = "sin", effect = MolangEffect.CONTEXT, contextFreeTarget = "sin")
    public static float sin(MolangContextView<Entity> context, float a) {
        return sin(a);
    }

    @BuiltinFunction(value = "sqrt", effect = MolangEffect.CONTEXT, contextFreeTarget = "sqrt")
    public static float sqrt(MolangContextView<Entity> context, float a) {
        return sqrt(a);
    }

    @BuiltinFunction(value = "min_angle", effect = MolangEffect.CONTEXT, contextFreeTarget = "minAngle")
    public static float minAngle(MolangContextView<Entity> context, float value) {
        return minAngle(value);
    }

    // Both declaration adapters and trusted direct calls use these exact scalar implementations.
    public static float abs(float a) {
        return Math.abs(a);
    }

    public static float clamp(float a, float b, float c) {
        return Math.min(Math.max(a, b), c);
    }

    public static float cos(float a) {
        return (float)Math.cos(Math.toRadians(a));
    }

    public static float exp(float a) {
        return finite((float)Math.exp(a));
    }

    public static float floor(float a) {
        return (float)Math.floor(a);
    }

    public static float lerp(float a, float b, float t) {
        return finite(a + (b - a) * t);
    }

    public static float max(float a, float b) {
        return Math.max(a, b);
    }

    public static float min(float a, float b) {
        return Math.min(a, b);
    }

    public static float mod(float a, float b) {
        return finite(a % b);
    }

    public static float pow(float a, float b) {
        return finite((float)Math.pow(a, b));
    }

    public static float round(float a) {
        return (float)Math.round(a);
    }

    public static float sin(float a) {
        return (float)Math.sin(Math.toRadians(a));
    }

    public static float sqrt(float a) {
        return finite((float)Math.sqrt(a));
    }

    public static float minAngle(float value) {
        float wrapped = value % 360;
        return wrapped >= 180 ? wrapped - 360 : wrapped < -180 ? wrapped + 360 : wrapped;
    }

    static float finite(float value) {
        // Finite operands can overflow or leave these operations' mathematical domain.
        if (!Float.isFinite(value))
            throw new ArithmeticException("non-finite math result");
        return value;
    }
}
