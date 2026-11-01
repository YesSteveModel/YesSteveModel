package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import dev.semaweave.ProviderInstanceTable;
import dev.semaweave.compilation.BindingPlan;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;

/**
 * Binds one owner's component lifetime and exact construction-time provider directory.
 *
 * <p>Execution: the owner serializes construction, hooks, provider binding, and invalidation in its
 * execution or installation window. Components are not shared mutable state between owners.
 * The current predicate tracks changing installation eligibility; it is not a replacement for that window.
 *
 * <p>Ownership: subject, target, event, and provider instances are borrowed. Invalidating the scope
 * removes access without taking closing ownership of the target or subject.
 *
 * <p>Seam: implements [component lifecycle](docs/architecture/animation/molang-declarations.md#组件生命周期) and depends on [invocations and effects](docs/architecture/animation/molang-runtime.md#invocation-与效果).
 */
public interface ComponentScope {
    AnimatableEntity<?> owner();

    Entity subject();

    /**
     * Rebinds borrowed render resources without resetting component history.
     *
     * <p>Failure: a hook can fail after earlier hooks changed their bindings. The caller must end a failed
     * shared activation; this operation supplies no rollback of component mutations.
     */
    void setRenderTarget(ModelRenderTarget target, BooleanSupplier current);

    /**
     * Brackets the body with component input borrows, inside the caller's effect transaction.
     *
     * <p>Ensures: only components whose begin hook returned normally receive an end hook, in reverse order.
     * Cleanup runs after body failure or lost eligibility. Cleanup does not commit effects.
     *
     * <p>Failure: the first failure remains primary and later cleanup failures are suppressed.
     * The caller must let a failed hook invalidate the enclosing effect batch.
     */
    void evaluate(AnimationEvent<?> event, BooleanSupplier current, Runnable body);

    void tick(int previousTick, int currentTick, BooleanSupplier current);

    void update(float previousTime, float currentTime, float partialTicks, BooleanSupplier current);

    /**
     * Permanently revokes directory access for this component lifetime.
     *
     * <p>Execution: an active evaluation still finishes its successfully entered hook prefix.
     * Invalidation does not reset hooks or free borrowed resources.
     */
    void invalidate();

    /**
     * Returns a borrowed instance only for an exact declaration class in this owner scope.
     *
     * @return the registered instance, or empty when absent or invalidated; no subtype search occurs
     */
    <T> Optional<T> exact(Class<T> type);

    /**
     * Selects borrowed providers for Root construction from the host registry and binding plan.
     *
     * <p>Requires: the scope is active. Generated calls use the constructed bindings rather than this directory.
     * The caller must not outlive the component scope when using a selected provider.
     */
    ProviderInstanceTable providers(ExpressionHost host, BindingPlan plan);
}
