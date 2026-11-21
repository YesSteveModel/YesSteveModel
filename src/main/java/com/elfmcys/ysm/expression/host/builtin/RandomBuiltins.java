package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import net.minecraft.world.entity.Entity;

@BuiltinComponent("math")
public final class RandomBuiltins implements EntityComponent<Entity> {
    private final AnimatableEntity<?> owner;

    public RandomBuiltins(AnimatableEntity<Entity> owner) {
        this.owner = owner;
    }

    @BuiltinFunction(value = "random_integer", alwaysOrigin = true)
    public float randomInteger(MolangContextView<Entity> context, float minimum, float maximum) {

        var lower = (int)minimum;
        var upper = (int)maximum;
        if (lower > upper) {
            var swap = lower;
            lower = upper;
            upper = swap;
        }
        long width = (long)upper - lower;
        if (width == 0)
            return lower;
        var random = owner.getAnimationProcessor().randomSource();
        if (width <= Integer.MAX_VALUE)
            return lower + random.nextInt((int)width);
        // Rejection keeps the full signed-int interval uniform without overflowing its width.
        long bits, offset;
        do {
            bits = random.nextLong() >>> 1;
            offset = bits % width;
        } while (bits - offset + width - 1 < 0);
        return (float)(lower + offset);
    }

    @BuiltinFunction(value = "randomi", alwaysOrigin = true)
    public float randomi(MolangContextView<Entity> context, float minimum, float maximum) {
        return randomInteger(context, minimum, maximum);
    }

    @BuiltinFunction(value = "random", alwaysOrigin = true)
    public float random(MolangContextView<Entity> context, int argc, float minimum, float maximum,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float unused) {

        if (minimum > maximum) {
            var swap = minimum;
            minimum = maximum;
            maximum = swap;
        }
        if (minimum == maximum)
            return minimum;
        float draw = owner.getAnimationProcessor().randomSource().nextFloat();
        float width = maximum - minimum;
        return Float.isFinite(width) ? minimum + draw * width
            : (float)(minimum + (double)draw * ((double)maximum - minimum));
    }

    @BuiltinFunction(value = "die_roll", alwaysOrigin = true)
    public float dieRoll(MolangContextView<Entity> context, float count, float minimum, float maximum) {
        int rolls = (int)count;
        if (rolls <= 0)
            return 0;
        if (minimum > maximum) {
            float swap = minimum;
            minimum = maximum;
            maximum = swap;
        }
        float width = maximum - minimum;
        float total = 0;
        var random = owner.getAnimationProcessor().randomSource();
        for (int i = 0; i < rolls; i++)
            total += minimum + random.nextFloat() * width;
        return Float.isFinite(total) ? total : 0;
    }

    @BuiltinFunction(value = "roll", alwaysOrigin = true)
    public float roll(MolangContextView<Entity> context, float count, float minimum, float maximum) {
        return dieRoll(context, count, minimum, maximum);
    }

    @BuiltinFunction(value = "die_roll_integer", alwaysOrigin = true)
    public float dieRollInteger(MolangContextView<Entity> context, float count, float minimum, float maximum) {
        int rolls = Math.round(count);
        if (rolls <= 0)
            return 0;
        int lower = (int)minimum;
        int upper = (int)maximum;
        if (lower > upper) {
            int swap = lower;
            lower = upper;
            upper = swap;
        }
        int width = upper - lower;
        if (width <= 0)
            return 0;
        int total = 0;
        var random = owner.getAnimationProcessor().randomSource();
        for (int i = 0; i < rolls; i++)
            total += lower + random.nextInt(width);
        return total;
    }

    @BuiltinFunction(value = "rolli", alwaysOrigin = true)
    public float rolli(MolangContextView<Entity> context, float count, float minimum, float maximum) {
        return dieRollInteger(context, count, minimum, maximum);
    }
}
