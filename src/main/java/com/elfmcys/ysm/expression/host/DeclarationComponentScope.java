package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import dev.semaweave.ProviderInstanceTable;
import dev.semaweave.compilation.BindingPlan;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;

/**
 * Constructs one instance for each applicable declaration and binds its exact provider directory.
 *
 * <p>Invariant: provider lookup happens at Root construction, never during generated calls.
 * Successful begin hooks form the cleanup prefix, so a hook that throws before returning must release its own unfinished borrow.
 *
 * <p>Failure: hook failure does not undo prior component mutations. Invalidation during evaluation
 * revokes directory access immediately and retains the entered lifecycle prefix only for cleanup.
 *
 * <p>Seam: implements [component lifecycle](docs/architecture/animation/molang-declarations.md#组件生命周期) through the ComponentScope contract.
 */
public final class DeclarationComponentScope implements ComponentScope {
    private final AnimatableEntity<?> owner;
    private final Entity subject;
    private final Map<Class<?>, Object> instances = new LinkedHashMap<>();
    private final Map<String, Object> providers = new LinkedHashMap<>();
    private Entry[] lifecycle;
    private ModelRenderTarget target;
    private boolean invalidated;
    private boolean evaluating;

    private record Entry(Class<?> declaration, EntityComponent<?> component) {
    }

    public DeclarationComponentScope(AnimatableEntity<?> owner, List<LinkedDeclaration> declarations) {
        this.owner = Objects.requireNonNull(owner);
        this.subject = Objects.requireNonNull(owner.getEntity());
        var entries = new ArrayList<Entry>();
        for (var declaration : declarations) {
            try {
                if (!declaration.hasMembers() || !declaration.componentSubject().isInstance(subject))
                    continue;
                Object helper = declaration.helper().getConstructor(AnimatableEntity.class).newInstance(owner);
                providers.put(LinkedDeclaration.providerId(declaration.schema()), helper);
                instances.put(declaration.helper(), helper);
                instances.put(declaration.owner(), declaration.helper().getMethod("provider").invoke(helper));
                entries.add(new Entry(declaration.owner(), (EntityComponent<?>)helper));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                invalidate();
                Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
                throwFailure(failure(declaration.owner(), ComponentLifecycleException.Phase.CONSTRUCT, cause));
            }
        }
        lifecycle = entries.toArray(Entry[]::new);
    }

    public AnimatableEntity<?> owner() {
        requireActive();
        return owner;
    }

    public Entity subject() {
        requireActive();
        return subject;
    }

    public void setRenderTarget(ModelRenderTarget next, BooleanSupplier current) {
        requireCurrent(current);
        Objects.requireNonNull(next);
        if (target == next)
            return;
        for (var entry : lifecycle) {
            invoke(entry, ComponentLifecycleException.Phase.SET_RENDER_TARGET,
                () -> entry.component().setRenderTarget(next));
            requireCurrent(current);
        }
        target = next;
    }

    public void evaluate(AnimationEvent<?> event, BooleanSupplier current, Runnable body) {
        requireCurrent(current);
        Objects.requireNonNull(event);
        if (evaluating)
            throw new IllegalStateException("component evaluation is already active");
        evaluating = true;
        Entry[] captured = lifecycle;
        int begun = 0;
        Throwable primary = null;
        try {
            for (var entry : captured) {
                invoke(entry, ComponentLifecycleException.Phase.BEGIN_EVAL, () -> entry.component().beginEval(event));
                begun++;
                requireCurrent(current);
            }
            body.run();
            requireCurrent(current);
        } catch (Throwable failure) {
            primary = failure;
        } finally {
            while (begun > 0) {
                var entry = captured[--begun];
                try {
                    invoke(entry, ComponentLifecycleException.Phase.END_EVAL, () -> entry.component().endEval());
                } catch (Throwable cleanupFailure) {
                    if (primary == null)
                        primary = cleanupFailure;
                    else if (primary != cleanupFailure)
                        primary.addSuppressed(cleanupFailure);
                }
            }
            evaluating = false;
            if (invalidated)
                clearReferences();
        }
        if (primary != null)
            throwFailure(primary);
    }

    public void tick(int previousTick, int currentTick, BooleanSupplier current) {
        requireCurrent(current);
        for (var entry : lifecycle) {
            invoke(entry, ComponentLifecycleException.Phase.TICK,
                () -> entry.component().tick(previousTick, currentTick));
            requireCurrent(current);
        }
    }

    public void update(float previousTime, float currentTime, float partialTicks, BooleanSupplier current) {
        requireCurrent(current);
        for (var entry : lifecycle) {
            invoke(entry, ComponentLifecycleException.Phase.UPDATE,
                () -> entry.component().update(previousTime, currentTime, partialTicks));
            requireCurrent(current);
        }
    }

    public void invalidate() {
        invalidated = true;
        instances.clear();
        providers.clear();
        target = null;
        if (!evaluating)
            clearReferences();
    }

    private void clearReferences() {
        lifecycle = null;
    }

    public <T> Optional<T> exact(Class<T> type) {
        return Optional.ofNullable(type.cast(instances.get(type)));
    }

    public ProviderInstanceTable providers(ExpressionHost host, BindingPlan plan) {
        requireActive();
        var selected = new LinkedHashMap<String, Object>();
        for (String id : plan.providerIds(host.registry()))
            if (providers.containsKey(id))
                selected.put(id, providers.get(id));
        return ProviderInstanceTable.create(host.registry(), plan, selected);
    }

    private void requireActive() {
        if (invalidated)
            throw new CancellationException("component holder invalidated");
    }

    private void requireCurrent(BooleanSupplier current) {
        requireActive();
        if (!current.getAsBoolean())
            throw new CancellationException("component installation is no longer current");
    }

    private static void invoke(Entry entry, ComponentLifecycleException.Phase phase, Runnable hook) {
        try {
            hook.run();
        } catch (Throwable cause) {
            throwFailure(failure(entry.declaration(), phase, cause));
        }
    }

    private static Throwable failure(Class<?> declaration, ComponentLifecycleException.Phase phase, Throwable cause) {
        return fatal(cause) ? cause : new ComponentLifecycleException(declaration, phase, cause);
    }

    private static boolean fatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
        throw new AssertionError(failure);
    }
}
