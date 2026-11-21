package com.elfmcys.ysm.expression.host;

import java.util.Map;
import net.minecraft.world.effect.MobEffect;
import org.jetbrains.annotations.Nullable;

/** Accepted network query values. No mutable input map is retained. */
public record PlayerQueryProjection(float health, float maxHealth, float foodLevel, float experienceLevel, float xxa,
    float yya, float zza, @Nullable Boolean flying, @Nullable Boolean shieldCooldown,
    @Nullable Map<MobEffect, Byte> effects) {

    public static final PlayerQueryProjection MISSING = new PlayerQueryProjection(Float.NaN, Float.NaN, Float.NaN,
        Float.NaN, Float.NaN, Float.NaN, Float.NaN, null, null, null);

    public PlayerQueryProjection {
        if (effects != null)
            effects = Map.copyOf(effects);
    }

    public float effectLevel(MobEffect effect) {
        return effects == null ? Float.NaN : effects.getOrDefault(effect, (byte)0);
    }
}
