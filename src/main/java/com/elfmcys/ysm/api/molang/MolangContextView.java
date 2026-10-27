package com.elfmcys.ysm.api.molang;

import com.elfmcys.ysm.expression.host.MolangVector;
import net.minecraft.world.entity.Entity;

/** Borrowed only for the synchronous provider call. */
public interface MolangContextView<E extends Entity> {
    E subject();

    float partialTick();

    float renderTickDelta();

    float renderTicks();

    float headPitch();

    float headYaw();

    float bodyYaw();

    float lifeTime();

    float foodLevel();

    float forwardInput();

    float health(boolean maximum);

    boolean renderingInInventory();

    boolean keyboard(float... keys);

    String modVersion(String id);

    float random();

    int randomInt(int bound);

    String textureName();

    boolean playingExtraAnimation();

    boolean shieldCooldown();

    String selectedControl();

    float animationTime();

    boolean allAnimationsFinished();

    boolean anyAnimationFinished();

    MolangVector boneRotation(String name);

    MolangVector bonePivotAbsolute(String name);

    float firstOrder(String key, float value, float... coefficients);

    float secondOrder(String key, float value, float... coefficients);
}
