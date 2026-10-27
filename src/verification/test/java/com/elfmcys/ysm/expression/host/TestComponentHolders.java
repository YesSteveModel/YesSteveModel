package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import dev.semaweave.ProviderInstanceTable;
import dev.semaweave.compilation.BindingPlan;
import dev.semaweave.registry.JavaMemberBinding;

import java.util.HashMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;

/** Test-only construction of instance-local generated component linkage. */
public final class TestComponentHolders {
    private TestComponentHolders() {}

    public static ProviderInstanceTable providers(ExpressionHost host) {
        return providers(host, host.expressionBinding().bindingPlan());
    }

    public static ProviderInstanceTable providers(ExpressionHost host, BindingPlan plan) {
        var required = plan.providerIds(host.registry());
        var instancesByClass = new HashMap<Class<?>, Object>();
        var instances = new TreeMap<String, Object>();
        for (var descriptor : host.builtins()) {
            if (descriptor.providerId().isEmpty() || !required.contains(descriptor.providerId().orElseThrow())) {
                continue;
            }
            var component =
                instancesByClass.computeIfAbsent(descriptor.componentClass(), TestComponentHolders::instantiate);
            instances.put(descriptor.providerId().orElseThrow(), component);
        }
        return ProviderInstanceTable.create(host.registry(), plan, instances);
    }

    public static Object provider(ExpressionHost host, JavaMemberBinding binding) {
        var providers = providers(host);
        var providerId = binding.providerId().orElseThrow();
        var index = providers.providerIds().indexOf(providerId);
        if (index < 0 || providers.provider(index) == null) {
            throw new AssertionError("missing generated provider " + providerId);
        }
        return providers.provider(index);
    }

    // Storage tests need provider linkage without an entity lifecycle.
    public static ComponentScope linkageScope() {
        return new ComponentScope() {
            public AnimatableEntity<?> owner() { return null; }
            public Entity subject() { return null; }
            public void setRenderTarget(ModelRenderTarget target, BooleanSupplier current) {}
            public void evaluate(AnimationEvent<?> event, BooleanSupplier current, Runnable body) { body.run(); }
            public void tick(int previous, int next, BooleanSupplier current) {}
            public void update(float previous, float next, float partial, BooleanSupplier current) {}
            public void invalidate() {}
            public <T> Optional<T> exact(Class<T> type) { return Optional.empty(); }
            public ProviderInstanceTable providers(ExpressionHost host, BindingPlan plan) {
                return TestComponentHolders.providers(host, plan);
            }
        };
    }

    public static <E extends Entity> AnimatableEntity<E> owner(E entity) {
        try {
            var owner = (Owner)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(Owner.class);
            var field = AnimatableEntity.class.getDeclaredField("entity");
            field.setAccessible(true);
            field.set(owner, entity);
            @SuppressWarnings("unchecked")
            var typed = (AnimatableEntity<E>)(Object)owner;
            return typed;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            return type.getConstructor(AnimatableEntity.class)
                .newInstance(owner(null));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot create test component " + type.getName(), exception);
        }
    }

    private static final class Owner extends AnimatableEntity<Entity> {
        private Owner() { super(null); }
        public net.minecraft.resources.ResourceLocation getTextureLocation() { return null; }
        public float getWidthScale() { return 1; }
        public float getHeightScale() { return 1; }
        public com.elfmcys.ysm.geckolib3.core.builder.Animation getAnimation(String name) { return null; }
        protected void prepareAnimationControllers(ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {}
        protected com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel resolveGeoModel(
            ModelRenderTarget target, String texture) { throw new AssertionError(); }
    }
}
