package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.client.entity.HumanoidStateTracker;
import com.elfmcys.ysm.proto.network.EffectStateSetProto;
import com.elfmcys.ysm.proto.network.GameplayStateProto;
import com.elfmcys.ysm.expression.host.PlayerQueryAccess;
import com.elfmcys.ysm.expression.host.PlayerQueryProjection;
import java.util.HashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;

public class PlayerStateTracker extends HumanoidStateTracker<Player> {
    private final boolean localPlayer;
    private final PlayerQueryAccess query;

    private static float YAW_SPEED;
    private static float LAST_YAW;

    public PlayerStateTracker(Player player, boolean localPlayer) {
        super(player);
        this.localPlayer = localPlayer;
        this.query = (PlayerQueryAccess)player;
    }

    @Override
    public void reset() {
        super.reset();

        query.ysm$playerQueryProjection(PlayerQueryProjection.MISSING);
    }

    public void updateProtocolState(GameplayStateProto gameplay, EffectStateSetProto effectState, boolean full) {
        var previous = query.ysm$playerQueryProjection();
        float health = previous.health();
        float maxHealth = previous.maxHealth();
        float foodLevel = previous.foodLevel();
        float expLevel = previous.experienceLevel();
        float xxa = previous.xxa();
        float yya = previous.yya();
        float zza = previous.zza();
        Boolean remoteFlying = previous.flying();
        Boolean inShieldBlockCooldown = previous.shieldCooldown();
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
        var effects = previous.effects();
        if (effectState != null) {
            effects = full || effects == null ? new HashMap<>() : new HashMap<>(effects);
            for (var value : effectState.effects()) {
                var key = ResourceLocation.tryParse(value.effectId());
                var effect = key == null ? null : BuiltInRegistries.MOB_EFFECT.get(key);
                if (effect != null) {
                    if (value.level() == 0)
                        effects.remove(effect);
                    else
                        effects.put(effect, (byte)value.level());
                }
            }
        }
        query.ysm$playerQueryProjection(new PlayerQueryProjection(health, maxHealth, foodLevel, expLevel, xxa, yya, zza,
            remoteFlying, inShieldBlockCooldown, effects));
    }

    public boolean isFlying() {
        if (localPlayer) {
            return entity.getAbilities().flying;
        }
        return Boolean.TRUE.equals(query.ysm$playerQueryProjection().flying());
    }

    public int expLevel() {
        return (int)query.ysm$playerQueryProjection().experienceLevel();
    }

    public int health() {
        return (int)query.ysm$playerQueryProjection().health();
    }

    public int maxHealth() {
        return (int)query.ysm$playerQueryProjection().maxHealth();
    }

    public int foodLevel() {
        return (int)query.ysm$playerQueryProjection().foodLevel();
    }

    public float xxa() {
        return query.ysm$playerQueryProjection().xxa();
    }

    public float yya() {
        return query.ysm$playerQueryProjection().yya();
    }

    public float zza() {
        return query.ysm$playerQueryProjection().zza();
    }

    public boolean inShieldBlockCooldown() {
        return Boolean.TRUE.equals(query.ysm$playerQueryProjection().shieldCooldown());
    }

    public byte getEffectLevel(MobEffect effect) {
        if (localPlayer) {
            var instance = entity.getEffect(effect);
            return instance != null ? (byte)(instance.getAmplifier() + 1) : 0;
        } else {
            return (byte)query.ysm$playerQueryProjection().effectLevel(effect);
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
