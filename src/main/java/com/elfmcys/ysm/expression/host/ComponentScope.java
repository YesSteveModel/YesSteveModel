package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import dev.semaweave.ProviderInstanceTable;
import dev.semaweave.compilation.BindingPlan;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;

/** Owner-local lifecycle and exact construction-time provider directory. */
public interface ComponentScope {
    AnimatableEntity<?> owner();

    Entity subject();

    void setRenderTarget(ModelRenderTarget target, BooleanSupplier current);

    void evaluate(AnimationEvent<?> event, BooleanSupplier current, Runnable body);

    void tick(int previousTick, int currentTick, BooleanSupplier current);

    void update(float previousTime, float currentTime, float partialTicks, BooleanSupplier current);

    void invalidate();

    <T> Optional<T> exact(Class<T> type);

    ProviderInstanceTable providers(ExpressionHost host, BindingPlan plan);
}
