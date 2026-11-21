package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import dev.semaweave.ProviderInstanceTable;
import dev.semaweave.compilation.BindingPlan;

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

    // Storage tests need provider linkage without an entity lifecycle.
    public static ComponentScope linkageScope() {
        return new ComponentScope() {
            public AnimatableEntity<?> owner() {
                return null;
            }

            public Entity subject() {
                return null;
            }

            public void setRenderTarget(ModelRenderTarget target, BooleanSupplier current) {}

            public void evaluate(AnimationEvent<?> event, BooleanSupplier current, Runnable body) {
                body.run();
            }

            public void tick(int previous, int next, BooleanSupplier current) {}

            public void update(float previous, float next, float partial, BooleanSupplier current) {}

            public void invalidate() {}

            public <T> Optional<T> exact(Class<T> type) {
                return Optional.empty();
            }

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
            initializeLifecycle(owner);
            @SuppressWarnings("unchecked")
            var typed = (AnimatableEntity<E>)(Object)owner;
            return typed;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void initializeLifecycle(AnimatableEntity<?> owner) throws ReflectiveOperationException {
        var field = AnimatableEntity.class.getDeclaredField("lifecycle");
        field.setAccessible(true);
        if (field.get(owner) != null)
            return;
        var runtime = new com.elfmcys.ysm.expression.aot.EntityExpressionRuntime();
        setField(owner, AnimatableEntity.class, "subjectSampleDraft", new SubjectSample.Draft());
        var lifecycleClass = Class.forName("com.elfmcys.ysm.client.entity.EntityExecutionLifecycle");
        var constructor =
            lifecycleClass.getDeclaredConstructor(com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.class,
                Runnable.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        java.util.function.Consumer<Throwable> failure = cause -> {
            if (cause instanceof RuntimeException exception)
                throw exception;
            if (cause instanceof Error error)
                throw error;
            throw new AssertionError(cause);
        };
        var lifecycle = constructor.newInstance(runtime, (Runnable)() -> {
        }, failure);
        setField(owner, AnimatableEntity.class, "expressionRuntime", runtime);
        setField(owner, AnimatableEntity.class, "lifecycle", lifecycle);
    }

    public static void setBone(AnimatableEntity<?> owner, com.elfmcys.ysm.geckolib3.core.processor.BoneView bone) {
        ((Owner)owner).bone = bone;
    }

    public static void setEntity(AnimatableEntity<?> owner, Entity subject) {
        setField(owner, AnimatableEntity.class, "entity", subject);
    }

    public static void installProcessor(AnimatableEntity<?> owner, net.minecraft.util.RandomSource random,
        com.elfmcys.ysm.client.sound.instance.SoundInstanceManager sounds) {
        try {
            initializeLifecycle(owner);
            var processor = new com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor(owner);
            setField(processor, processor.getClass(), "random", random);
            setField(processor, processor.getClass(), "globalSoundManager", sounds);
            var bindingClass = Class.forName("com.elfmcys.ysm.client.entity.EntityModelBinding");
            var binding = com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(bindingClass);
            var holderClass = Class.forName("com.elfmcys.ysm.client.entity.AnimatableEntity$ResourceHolder");
            var holder = com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(holderClass);
            setField(binding, bindingClass, "resourceHolder", holder);
            setField(owner, AnimatableEntity.class, "modelBinding", binding);
            var sessionClass = Class.forName("com.elfmcys.ysm.client.entity.AnimatableEntity$AnimationSession");
            var sessionConstructor =
                sessionClass.getDeclaredConstructor(com.elfmcys.ysm.model.resource.client.AnimationStore.class);
            sessionConstructor.setAccessible(true);
            var session = sessionConstructor.newInstance(new com.elfmcys.ysm.model.resource.client.AnimationStore());
            setField(session, sessionClass, "processor", processor);
            setField(owner, AnimatableEntity.class, "animationSession", session);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setField(Object object, Class<?> owner, String name, Object value) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(object, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            return type.getConstructor(AnimatableEntity.class).newInstance(owner(null));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot create test component " + type.getName(), exception);
        }
    }

    private static final class Owner extends AnimatableEntity<Entity> {
        private com.elfmcys.ysm.geckolib3.core.processor.BoneView bone;

        public com.elfmcys.ysm.geckolib3.core.processor.BoneView getBone(int id) {
            return bone;
        }

        private Owner() {
            super(null);
        }

        public net.minecraft.resources.ResourceLocation getTextureLocation() {
            return null;
        }

        public float getWidthScale() {
            return 1;
        }

        public float getHeightScale() {
            return 1;
        }

        public com.elfmcys.ysm.geckolib3.core.builder.Animation getAnimation(String name) {
            return null;
        }

        protected void prepareAnimationControllers(ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {}

        protected com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel resolveGeoModel(ModelRenderTarget target,
            String texture) {
            throw new AssertionError();
        }
    }
}
