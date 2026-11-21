package com.elfmcys.ysm.client.demand;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static com.elfmcys.ysm.client.demand.ModelResourceDemand.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelResourceDemandTest {
    private static final ResourceRequest REQUEST = new ResourceRequest(
        ModelId.fromHash(new Hash256(new byte[Hash256.SIZE])), "player", "default", new BakeProfile("test"));

    @Test
    void readyModeOnlyObservesPublishedResources() {
        var access = new Access();
        var ready = Lease.ready();
        try (var demand = new ModelResourceDemand(access, REQUEST)) {
            assertNull(demand.advance(READY));
            assertNull(demand.advance(READY));
            assertFalse(demand.hasUnfinishedWork());
            access.ready.add(ready);
            assertSame(ready, demand.advance(READY));
            assertEquals(0, access.cachedCalls + access.offlineCalls + access.onlineCalls);
        }
        assertEquals(0, ready.releases);
        ready.close();
    }

    @Test
    void onlineAdmissionWaitsForTheOfflineMissWithoutRestartingProbe() {
        var access = new Access();
        access.offline = new Lease();
        try (var demand = new ModelResourceDemand(access, REQUEST)) {
            assertNull(demand.advance(READY));
            assertEquals(0, access.cachedCalls + access.offlineCalls + access.onlineCalls);
            assertNull(demand.advance(OFFLINE));
            assertNull(demand.advance(OFFLINE));
            assertNull(demand.advance(ONLINE));
            assertNull(demand.advance(ONLINE));
            assertEquals(0, ((Lease)access.offline).releases);
            assertEquals(1, access.offlineCalls);
            assertEquals(0, access.onlineCalls);
            assertTrue(demand.hasUnfinishedWork());

            ((Lease)access.offline).result = new AcquireResult.Missing();
            assertNull(demand.advance(ONLINE));
            assertEquals(1, access.onlineCalls);
            assertNull(demand.advance(ONLINE));
            assertEquals(0, access.cachedCalls);
            assertEquals(1, access.offlineCalls);
            assertEquals(1, access.onlineCalls);
            assertNull(demand.failure());
        }
    }

    @Test
    void offlineReadyAfterOnlineAdmissionAvoidsAnyNetworkAcquisition() {
        var access = new Access();
        access.offline = new Lease();
        var ready = (Lease)access.offline;
        try (var demand = new ModelResourceDemand(access, REQUEST)) {
            demand.advance(OFFLINE);
            demand.advance(ONLINE);
            ready.result = new AcquireResult.Ready(target());
            assertSame(ready, demand.advance(ONLINE));
            assertEquals(1, access.offlineCalls);
            assertEquals(0, access.onlineCalls);
        }
        assertEquals(0, ready.releases);
        ready.close();
    }

    @Test
    void closingOfflineWorkAfterOnlineAdmissionReleasesItsUseAndStopsProgress() {
        var access = new Access();
        access.offline = new Lease();
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(OFFLINE);
        demand.advance(ONLINE);
        demand.close();
        demand.close();
        assertNull(demand.advance(ONLINE));
        assertFalse(demand.hasUnfinishedWork());
        assertEquals(1, ((Lease)access.offline).releases);
        assertEquals(1, access.offlineCalls);
        assertEquals(0, access.onlineCalls);
    }

    @Test
    void localPlayerPrioritySurvivesCachedOfflineAndOnlineMisses() {
        var access = new Access();
        try (var demand = new ModelResourceDemand(access, REQUEST, WorkPriority.LOCAL_PLAYER)) {
            demand.advance(CACHED);
            demand.advance(OFFLINE);
            demand.advance(ONLINE);
            assertEquals(
                java.util.List.of(WorkPriority.LOCAL_PLAYER, WorkPriority.LOCAL_PLAYER, WorkPriority.LOCAL_PLAYER),
                access.priorities);
            assertEquals(1, access.cachedCalls);
            assertEquals(1, access.offlineCalls);
            assertEquals(1, access.onlineCalls);
        }
    }

    @Test
    void unclassifiedDemandUsesBackgroundPriority() {
        var access = new Access();
        try (var demand = new ModelResourceDemand(access, REQUEST)) {
            demand.advance(ONLINE);
            assertEquals(java.util.List.of(WorkPriority.BACKGROUND), access.priorities);
        }
    }

    @Test
    void deniedNewUseDoesNotAcquireOrPoisonRecovery() {
        var access = new Access();
        access.permitted = false;
        var ready = Lease.ready();
        access.ready.add(ready);
        var demand = new ModelResourceDemand(access, REQUEST);
        assertNull(demand.advance(ONLINE));
        assertNull(demand.advance(CACHED));
        assertNull(demand.failure());
        assertEquals(0, access.cachedCalls + access.offlineCalls + access.onlineCalls);
        assertEquals(1, access.ready.size());
        access.permitted = true;
        assertSame(ready, demand.advance(ONLINE));
        demand.close();
        ready.close();
    }

    @Test
    void policyChangePreservesAdmittedPendingUseButNotTheNextCacheHit() {
        var access = new Access();
        access.offline = new Lease();
        var admitted = new ModelResourceDemand(access, REQUEST);
        assertNull(admitted.advance(OFFLINE));
        access.permitted = false;
        var accepted = (Lease)access.offline;
        accepted.result = new AcquireResult.Ready(target());
        assertSame(accepted, admitted.advance(ONLINE));
        assertEquals(0, accepted.releases);
        accepted.close();
        admitted.close();
        access.ready.add(Lease.ready());
        var next = new ModelResourceDemand(access, REQUEST);
        assertNull(next.advance(ONLINE));
        assertEquals(1, access.offlineCalls);
        assertEquals(1, access.ready.size());
        next.close();
        access.ready.remove().close();
    }

    @Test
    void cachedMissObservesLaterReadyWithoutRepeatingDiskProbeOrStartingWork() {
        var access = new Access();
        var demand = new ModelResourceDemand(access, REQUEST);
        assertNull(demand.advance(CACHED));
        assertNull(demand.advance(CACHED));
        assertNull(demand.failure());
        var ready = Lease.ready();
        access.ready.add(ready);
        assertSame(ready, demand.advance(CACHED));
        demand.close();
        assertEquals(0, ready.releases);
        assertEquals(1, access.cachedCalls);
        assertEquals(0, access.offlineCalls);
        assertEquals(0, access.onlineCalls);
        ready.close();
    }

    @Test
    void hoverUpgradeDoesNotWaitForCachedProbeAndLeavingCancelsOnlyItsInterest() {
        var access = new Access();
        access.cached = new Lease();
        var pending = new Lease();
        access.offline = pending;
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(CACHED);
        demand.advance(OFFLINE);
        assertEquals(1, ((Lease)access.cached).releases);
        assertEquals(1, access.offlineCalls);
        demand.advance(CACHED);
        assertEquals(1, pending.releases);
        assertEquals(0, access.onlineCalls);
        access.offline = Lease.ready();
        var ready = demand.advance(OFFLINE);
        assertNotNull(ready);
        assertEquals(2, access.offlineCalls);
        ready.close();
        demand.close();
    }

    @Test
    void completedButUnconsumedAcquisitionIsReleasedOnClose() {
        var access = new Access();
        access.cached = new Lease();
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(CACHED);
        var ready = (Lease)access.cached;
        ready.result = new AcquireResult.Ready(target());
        demand.close();
        demand.close();
        assertEquals(1, ready.releases);
    }

    @Test
    void stablePendingPollsRetainTheOriginalUse() {
        var access = new Access();
        var pending = new Lease();
        access.cached = pending;
        var demand = new ModelResourceDemand(access, REQUEST);
        for (int tick = 0; tick < 100; tick++)
            assertNull(demand.advance(CACHED));
        pending.result = new AcquireResult.Ready(target());
        assertSame(pending, demand.advance(CACHED));
        assertEquals(1, access.cachedCalls);
        assertEquals(0, pending.releases);
        pending.close();
        demand.close();
    }

    @Test
    void failedAcquisitionRetainsCauseWithoutPerFrameRetryOrGlobalPoison() {
        var access = new Access();
        var failure = new IllegalArgumentException("invalid animation");
        access.cached = Lease.failed(failure);
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(CACHED);
        demand.advance(OFFLINE);
        assertSame(failure, demand.failure());
        assertEquals(1, access.cachedCalls);
        assertEquals(0, access.offlineCalls);
        var ready = Lease.ready();
        access.ready.add(ready);
        assertSame(ready, demand.advance(CACHED));
        assertNull(demand.failure());
        ready.close();
        demand.close();
    }

    @Test
    void cancellationIsNotContentFailure() {
        var access = new Access();
        access.cached = Lease.failed(new CancellationException());
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(CACHED);
        assertTrue(demand.failure() instanceof CancellationException);
        assertNull(demand.advance(CACHED));
        assertEquals(1, access.cachedCalls);
        demand.close();
    }

    @Test
    void staleLeaseIsReleasedBeforeAnyInstallation() {
        var access = new Access();
        var stale = Lease.ready();
        stale.current = false;
        access.ready.add(stale);
        var demand = new ModelResourceDemand(access, REQUEST);
        assertNull(demand.advance(CACHED));
        assertEquals(1, stale.releases);
        assertTrue(demand.failure() instanceof CancellationException);
        demand.close();
    }

    private static final class Access implements ModelResourceDemand.Access {
        boolean permitted = true;

        @Override
        public boolean permitsAcquisition(ResourceRequest request) {
            return permitted;
        }

        final ArrayDeque<ResourceLease> ready = new ArrayDeque<>();
        ResourceLease cached = ResourceLease.missing(REQUEST);
        ResourceLease offline = ResourceLease.missing(REQUEST);
        int cachedCalls, offlineCalls, onlineCalls;
        final java.util.List<WorkPriority> priorities = new java.util.ArrayList<>();

        @Override
        public Optional<ResourceLease> findReady(ResourceRequest request) {
            return Optional.ofNullable(ready.poll());
        }

        @Override
        public ResourceLease getOrStartCached(ResourceRequest request, WorkPriority priority) {
            cachedCalls++;
            priorities.add(priority);
            return cached;
        }

        @Override
        public ResourceLease getOrStartOffline(ResourceRequest request, WorkPriority priority) {
            offlineCalls++;
            priorities.add(priority);
            return offline;
        }

        @Override
        public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
            onlineCalls++;
            priorities.add(priority);
            return new Lease();
        }
    }

    private static final class Lease implements ResourceLease {
        AcquireResult result = new AcquireResult.Pending();
        boolean current = true;
        int releases;

        static Lease ready() {
            var lease = new Lease();
            lease.result = new AcquireResult.Ready(target());
            return lease;
        }

        static Lease failed(Throwable failure) {
            var lease = new Lease();
            lease.result = new AcquireResult.Failed(new ResourceFailure(ResourceFailure.Kind.TRANSIENT, failure));
            return lease;
        }

        @Override
        public ModelResourcePurpose purpose() {
            return REQUEST.purpose();
        }

        @Override
        public AcquireResult poll() {
            assertEquals(0, releases);
            return result;
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            return current;
        }

        @Override
        public void cancelPending() {
            assertInstanceOf(AcquireResult.Pending.class, result);
            releases++;
        }

        @Override
        public void close() {
            releases++;
        }
    }

    private static ModelRenderTarget target() {
        try {
            return (ModelRenderTarget)UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
        } catch (InstantiationException failure) {
            throw new AssertionError(failure);
        }
    }
}
