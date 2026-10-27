package com.elfmcys.ysm.expression.host;

import net.minecraft.world.entity.Entity;

/**
 * Invocation-scoped production read/action source. Implementations capture the exact entity and render inputs; they
 * must not consult a process-global current entity.
 */
public interface InvocationInputs<E extends Entity> extends EntityView, RenderView, EffectValidator {
    @Override
    default E entity() {
        throw new IllegalStateException("production input has no Minecraft subject");
    }

    default float partialTick() {
        return 0;
    }

    default float renderTickDelta() {
        return 0;
    }

    @Override
    default InvocationInputs<E> productionInputs() {
        return this;
    }

    /** Releases invocation-local prevalidation scratch after commit or discard. */
    default void clearPreparedEffects() {}
}
