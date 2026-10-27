package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import net.minecraft.world.entity.Entity;

/** Owner-local state; all hooks run synchronously inside the owner's serial window. */
public interface EntityComponent<E extends Entity> {
    /** Rebinds borrowed resources without resetting semantic history or taking closing ownership. */
    default void setRenderTarget(ModelRenderTarget target) {}

    default void tick(int previousTick, int currentTick) {}

    default void update(float previousTime, float currentTime, float partialTicks) {}

    /** Borrows read-only inputs until endEval; a throwing begin must clear its own unfinished borrow. */
    default void beginEval(AnimationEvent<?> event) {}

    /** Releases the successful begin's borrow even after cancellation or failure; never commits effects. */
    default void endEval() {}
}
