package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Observes the exact installed lease's real close call. */
final class EntityUseProbe {
    private final String subject;
    private final String installed;
    private final AtomicInteger closes = new AtomicInteger();

    private EntityUseProbe(AnimatableEntity<?> owner) {
        subject = owner.getEntity().getScoreboardName();
        installed = owner.getInstalledModelId();
    }

    static EntityUseProbe watch(AnimatableEntity<?> owner) throws Exception {
        var binding = field(AnimatableEntity.class, "modelBinding").get(owner);
        var holder = field(binding.getClass(), "resourceHolder").get(binding);
        if (holder == null)
            return null;
        var result = new EntityUseProbe(owner);
        var leaseField = field(holder.getClass(), "lease");
        var delegate = (ResourceLease)leaseField.get(holder);
        leaseField.set(holder, new ResourceLease() {
            @Override
            public com.elfmcys.ysm.model.resource.client.ModelResourcePurpose purpose() {
                return delegate.purpose();
            }

            @Override
            public AcquireResult poll() {
                return delegate.poll();
            }

            @Override
            public boolean isCurrent(ResourceRequest request) {
                return delegate.isCurrent(request);
            }

            @Override
            public void cancelPending() {
                delegate.cancelPending();
            }

            @Override
            public void close() {
                result.closes.incrementAndGet();
                delegate.close();
            }
        });
        return result;
    }

    Map<String, Object> snapshot() {
        return Map.of("subject", subject, "installed", installed, "leaseCloses", closes.get());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
