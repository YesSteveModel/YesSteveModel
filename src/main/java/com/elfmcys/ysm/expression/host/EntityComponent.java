package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import net.minecraft.world.entity.Entity;

/**
 * Stores one owner's component state for a single component lifetime.
 *
 * <p>Execution: hooks run synchronously in the owner's serial execution window, which can be a worker
 * or the exact GUI task. Hooks must not reenter public lifecycle operations or assume that live host inputs are snapshots.
 *
 * <p>Ownership: components borrow subjects, resources, and evaluation inputs. The component must not
 * retain an event or call-local input after endEval(). Scope invalidation supplies no resource-destructor hook.
 *
 * <p>Seam: implements the author hook obligations in [component lifecycle](docs/architecture/animation/molang-declarations.md#组件生命周期).
 */
public interface EntityComponent<E extends Entity> {
    /**
     * Rebinds borrowed resources without resetting semantic history or taking closing ownership.
     *
     * <p>Failure: a throwing hook can leave partial component mutation. The enclosing activation owns failure isolation.
     */
    default void setRenderTarget(ModelRenderTarget target) {}

    /**
     * Advances tick-domain state from the owner's previous sample to its current sample.
     *
     * <p>Execution: the owner decides whether a new sample exists. A component must not add a separate
     * wall-clock or frame gate that skips an interval the owner supplied.
     *
     * @param previousTick the previous entity tick count
     * @param currentTick the current entity tick count
     */
    default void tick(int previousTick, int currentTick) {}

    /**
     * Advances update-domain state over the owner's supplied animation interval.
     *
     * @param previousTime the previous animation time in ticks
     * @param currentTime the current animation time in ticks
     * @param partialTicks the host interpolation fraction for the current update
     */
    default void update(float previousTime, float currentTime, float partialTicks) {}

    /**
     * Borrows read-only call inputs until the matching endEval().
     *
     * <p>Failure: if this method throws, this component receives no endEval() for that attempt.
     * The throwing method must clear its own unfinished borrow before propagating failure.
     */
    default void beginEval(AnimationEvent<?> event) {}

    /**
     * Releases a successful beginEval() borrow, including after cancellation or body failure.
     *
     * <p>Execution: cleanup remains inside the enclosing transaction and must not commit external effects.
     * A cleanup failure fails that transaction while later component cleanup still runs.
     */
    default void endEval() {}
}
