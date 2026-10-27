package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderTargetLeaseOwnershipTest {
    @Test
    void closeCancelsEveryRequiredLeaseExactlyOnce() {
        var ownership = new RenderTargetLeaseOwnership();
        var first = new CountingLease();
        var second = new CountingLease();
        ownership.addRequired(first);
        ownership.addRequired(second);

        ownership.close();
        ownership.close();

        assertEquals(1, first.pendingCancellations.get());
        assertEquals(1, second.pendingCancellations.get());
    }

    @Test
    void removedOrLateRequiredLeaseIsCancelledByItsCallerBoundary() {
        var ownership = new RenderTargetLeaseOwnership();
        var removed = new CountingLease();
        ownership.addRequired(removed);
        ownership.removeRequired(removed);
        ownership.close();
        assertEquals(1, removed.pendingCancellations.get());

        var late = new CountingLease();
        assertThrows(IllegalStateException.class, () -> ownership.addRequired(late));
        assertEquals(1, late.pendingCancellations.get());
    }

    private static final class CountingLease implements ResourceLease {
        private final AtomicInteger pendingCancellations = new AtomicInteger();

        @Override
        public ModelResourcePurpose purpose() {
            return ModelResourcePurpose.FULL;
        }

        @Override
        public AcquireResult poll() {
            return new AcquireResult.Pending();
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            return true;
        }

        @Override
        public void close() {
            pendingCancellations.compareAndSet(0, 1);
        }

        @Override
        public void cancelPending() {
            pendingCancellations.compareAndSet(0, 1);
        }
    }
}
