package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;

import net.minecraft.world.entity.Entity;

/**
 * Invocation-scoped production input source. Implementations capture the exact entity and render inputs; they
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

    @Override
    default void prevalidateEffect(PreparedEffect effect, MolangContext<?> scope) {
        effect.validate();
    }
}
