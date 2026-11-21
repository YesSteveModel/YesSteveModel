package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.entity.EntityModelDemand;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.model.resource.client.*;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Drives the production tick owner while keeping pure binding installation factories injectable. */
final class DemandFixtures {
    interface ModelAccess extends EntityModelBinding.ModelAccess, ClientDemandCoordinator.Resources {
        @Override
        default com.elfmcys.ysm.model.catalog.content.ModelContent content(PublishedModelId modelId) {
            return EntityModelBinding.ModelAccess.super.content(modelId);
        }

        @Override
        default ResourceRequest resourceRequest(ModelId modelId, String target, String texture,
            ModelResourcePurpose purpose, Class<?> subject) {
            return EntityModelBinding.ModelAccess.super.resourceRequest(modelId, target, texture, purpose, subject);
        }

        @Override
        default ResourceRequest defaultResourceRequest(String target, ModelResourcePurpose purpose, Class<?> subject) {
            return EntityModelBinding.ModelAccess.super.defaultResourceRequest(target, purpose, subject);
        }

    }

    private static final Map<EntityModelBinding, Receiver> RECEIVERS = new IdentityHashMap<>();

    static ClientDemandCoordinator coordinator(ClientDemandCoordinator.Resources resources, LongSupplier clock) {
        return new ClientDemandCoordinator(clock, resources, new ClientDemandCoordinator.Selections() {
            private final Object owner = new Object();

            public ClientDemandCoordinator.SelectionContext context() {
                return new ClientDemandCoordinator.SelectionContext(owner, new java.util.UUID(0, 1), owner, false,
                    true);
            }

            public ClientDemandCoordinator.Target authority() {
                return new ClientDemandCoordinator.Target(null, "");
            }

            public boolean installed(ClientDemandCoordinator.Target target, Object application) {
                return true;
            }

            public ClientDemandCoordinator.InitialRoaming retainedInitial(
                ClientDemandCoordinator.SelectionContext context, ClientDemandCoordinator.Target target) {
                return null;
            }

            public void send(ClientDemandCoordinator.SelectionContext context, ClientDemandCoordinator.Target target,
                long id, com.elfmcys.ysm.model.storage.NamedRoamingSnapshot initial, boolean reinitialize) {
                throw new AssertionError();
            }

            public void reportFailure(Throwable failure) {
                throw new AssertionError(failure);
            }

            public void reportPreferenceReadFailure(Throwable failure) {
                throw new AssertionError(failure);
            }
        });
    }

    static void advance(AnimatableEntity<?> owner) {
        owner.checkModelUpdate();
        try {
            var field = AnimatableEntity.class.getDeclaredField("modelBinding");
            field.setAccessible(true);
            var binding = (EntityModelBinding)field.get(owner);
            var coordinator = binding.coordinator();
            if (coordinator != null)
                coordinator.tick();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    static com.elfmcys.ysm.geckolib3.geo.GeoRenderData produce(AnimatableEntity<?> owner, float partial,
        com.elfmcys.ysm.geckolib3.geo.RenderContext context) {
        advance(owner);
        return owner.update(partial, context);
    }

    static void drive(EntityModelBinding binding, String target, String texture, String fallback,
        EntityModelBinding.ResourceFactory factory) {
        var receiver = RECEIVERS.computeIfAbsent(binding, Receiver::new);
        receiver.target = target;
        receiver.texture = texture;
        receiver.fallback = fallback;
        receiver.factory = factory;
        binding.coordinator().tick();
    }

    static void close(EntityModelBinding binding) {
        var receiver = RECEIVERS.remove(binding);
        if (receiver != null)
            receiver.scope.close();
        binding.close();
    }

    static void clear() {
        for (var binding : java.util.List.copyOf(RECEIVERS.keySet()))
            close(binding);
    }

    private static final class Receiver implements ClientDemandCoordinator.Installation {
        private final EntityModelBinding binding;
        private final EntityModelDemand scope;
        private String target;
        private String texture;
        private String fallback;
        private EntityModelBinding.ResourceFactory factory;

        private Receiver(EntityModelBinding binding) {
            this.binding = binding;
            scope = binding.coordinator().register(this);
        }

        public ClientDemandCoordinator.Assignment assignment() {
            return binding.assignment(target, texture, fallback, ModelResourceDemand.Mode.ONLINE,
                ModelResourcePurpose.FULL, null);
        }

        public boolean hasInstallation() {
            return binding.resourceHolder() != null;
        }

        public boolean matches(ResourceRequest request, boolean full) {
            return binding.matches(request, full);
        }

        public Throwable failure(ResourceRequest request) {
            return binding.failure(request);
        }

        public ClientDemandCoordinator.InstallationResult install(ResourceRequest request, ResourceLease lease,
            boolean intrinsic, boolean full) {
            return binding.accept(request, lease, intrinsic, full, factory);
        }
    }
}
