package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.client.entity.HumanoidStateTracker;
import com.elfmcys.ysm.proto.network.EffectStateSetProto;
import com.elfmcys.ysm.proto.network.GameplayStateProto;
import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;

public class PlayerStateTracker extends HumanoidStateTracker<Player> {
    private final boolean localPlayer;
    private final Object2ByteOpenHashMap<MobEffect> effects;

    private boolean remoteFlying;
    private int expLevel;
    private int health;
    private int maxHealth;
    private int foodLevel;

    private float xxa;
    private float yya;
    private float zza;

    private boolean inShieldBlockCooldown;

    private static float YAW_SPEED;
    private static float LAST_YAW;

    public PlayerStateTracker(Player player, boolean localPlayer) {
        super(player);
        this.localPlayer = localPlayer;
        this.effects = new Object2ByteOpenHashMap<>(8);
    }

    @Override
    public void reset() {
        super.reset();

        effects.clear();

        remoteFlying = false;
        expLevel = 0;
        health = 0;
        maxHealth = 0;
        foodLevel = 0;

        xxa = 0;
        yya = 0;
        zza = 0;

        inShieldBlockCooldown = false;
    }

    public void updateProtocolState(GameplayStateProto gameplay, EffectStateSetProto effectState, boolean full) {
        if (gameplay != null) {
            if (gameplay.hasFlying())
                remoteFlying = gameplay.flying().orElseThrow();
            if (gameplay.hasExperienceLevel())
                expLevel = gameplay.experienceLevel().orElseThrow();
            if (gameplay.hasFoodLevel())
                foodLevel = gameplay.foodLevel().orElseThrow();
            if (gameplay.hasHealth())
                health = gameplay.health().orElseThrow();
            if (gameplay.hasMaxHealth())
                maxHealth = gameplay.maxHealth().orElseThrow();
            if (gameplay.hasMoveXQ7())
                xxa = gameplay.moveXQ7().orElseThrow() / 127f;
            if (gameplay.hasMoveYQ7())
                yya = gameplay.moveYQ7().orElseThrow() / 127f;
            if (gameplay.hasMoveZQ7())
                zza = gameplay.moveZQ7().orElseThrow() / 127f;
            if (gameplay.hasShieldCooldown()) {
                inShieldBlockCooldown = gameplay.shieldCooldown().orElseThrow();
            }
        }
        if (effectState != null) {
            if (full)
                effects.clear();
            for (var value : effectState.effects()) {
                var key = ResourceLocation.tryParse(value.effectId());
                var effect = key == null ? null : BuiltInRegistries.MOB_EFFECT.get(key);
                if (effect != null) {
                    if (value.level() == 0)
                        effects.removeByte(effect);
                    else
                        effects.put(effect, (byte)value.level());
                }
            }
        }
    }

    public boolean isFlying() {
        if (localPlayer) {
            return entity.getAbilities().flying;
        }
        return remoteFlying;
    }

    public int expLevel() {
        return expLevel;
    }

    public int health() {
        return health;
    }

    public int maxHealth() {
        return maxHealth;
    }

    public int foodLevel() {
        return foodLevel;
    }

    public float xxa() {
        return xxa;
    }

    public float yya() {
        return yya;
    }

    public float zza() {
        return zza;
    }

    public boolean inShieldBlockCooldown() {
        return inShieldBlockCooldown;
    }

    public byte getEffectLevel(MobEffect effect) {
        if (localPlayer) {
            var instance = entity.getEffect(effect);
            return instance != null ? (byte)(instance.getAmplifier() + 1) : 0;
        } else {
            return effects.getOrDefault(effect, (byte)0);
        }
    }

    @Override
    protected void updateEntityTickData(int previousTick, int currentTick) {
        if (localPlayer) {
            updateLocalPlayerYawSpeed(entity, previousTick, currentTick);
        }
        super.updateEntityTickData(previousTick, currentTick);
    }

    private static void updateLocalPlayerYawSpeed(Player entity, int previousTick, int currentTick) {
        float yaw = entity.getYRot(); // local player 的 yRot 每帧都更新，不需要处理 partialTicks
        YAW_SPEED = currentTick > previousTick ? (yaw - LAST_YAW) * 20 / (currentTick - previousTick) : 0;
        LAST_YAW = yaw;
    }

    public static float getLocalPlayerYawSpeed() {
        return YAW_SPEED;
    }
}
