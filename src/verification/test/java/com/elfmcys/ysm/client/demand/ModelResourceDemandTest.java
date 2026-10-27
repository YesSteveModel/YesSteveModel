package com.elfmcys.ysm.client.demand;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

import static com.elfmcys.ysm.client.demand.ModelResourceDemand.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelResourceDemandTest {
    private static final ResourceRequest REQUEST =
        new ResourceRequest(new Hash256(new byte[Hash256.SIZE]), "player", "default", new BakeProfile("test"));

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
        access.offline = new CompletableFuture<>();
        try (var demand = new ModelResourceDemand(access, REQUEST)) {
            assertNull(demand.advance(READY));
            assertEquals(0, access.cachedCalls + access.offlineCalls + access.onlineCalls);
            assertNull(demand.advance(OFFLINE));
            assertNull(demand.advance(OFFLINE));
            assertNull(demand.advance(ONLINE));
            assertNull(demand.advance(ONLINE));
            assertFalse(access.offline.isCancelled());
            assertEquals(1, access.offlineCalls);
            assertEquals(0, access.onlineCalls);
            assertTrue(demand.hasUnfinishedWork());

            access.offline.complete(Optional.empty());
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
        access.offline = new CompletableFuture<>();
        var ready = Lease.ready();
        try (var demand = new ModelResourceDemand(access, REQUEST)) {
            demand.advance(OFFLINE);
            demand.advance(ONLINE);
            assertTrue(access.offline.complete(Optional.of(ready)));
            assertSame(ready, demand.advance(ONLINE));
            assertEquals(1, access.offlineCalls);
            assertEquals(0, access.onlineCalls);
        }
        assertEquals(0, ready.releases);
        ready.close();
    }

    @Test
    void closingOfflineWorkAfterOnlineAdmissionReleasesLateReadyWithoutStartingNetwork() {
        var access = new Access();
        access.offline = uncancellableFuture();
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(OFFLINE);
        demand.advance(ONLINE);
        demand.close();
        var late = Lease.ready();
        access.offline.complete(Optional.of(late));
        demand.close();
        assertNull(demand.advance(ONLINE));
        assertFalse(demand.hasUnfinishedWork());
        assertEquals(1, late.releases);
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
            assertEquals(java.util.List.of(WorkPriority.LOCAL_PLAYER, WorkPriority.LOCAL_PLAYER,
                WorkPriority.LOCAL_PLAYER), access.priorities);
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
            assertEquals(java.util.List.of(WorkPriority.BACKGROUND, WorkPriority.BACKGROUND), access.priorities);
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
        access.offline = new CompletableFuture<>();
        var admitted = new ModelResourceDemand(access, REQUEST);
        assertNull(admitted.advance(OFFLINE));
        access.permitted = false;
        var accepted = Lease.ready();
        access.offline.complete(Optional.of(accepted));
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
        access.cached = new CompletableFuture<>();
        var pending = new Lease();
        access.offline = CompletableFuture.completedFuture(Optional.of(pending));
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(CACHED);
        demand.advance(OFFLINE);
        assertTrue(access.cached.isCancelled());
        assertEquals(1, access.offlineCalls);
        demand.advance(CACHED);
        assertEquals(1, pending.releases);
        assertEquals(0, access.onlineCalls);
        access.offline = CompletableFuture.completedFuture(Optional.of(Lease.ready()));
        var ready = demand.advance(OFFLINE);
        assertNotNull(ready);
        assertEquals(2, access.offlineCalls);
        ready.close();
        demand.close();
    }

    @Test
    void ordinaryLocalPendingIsNotRestartedAsOnlineWork() {
        var access = new Access();
        var pending = new Lease();
        access.offline = CompletableFuture.completedFuture(Optional.of(pending));
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(OFFLINE);
        demand.advance(ONLINE);
        assertEquals(0, access.onlineCalls);
        pending.result = new AcquireResult.Ready(target());
        assertSame(pending, demand.advance(ONLINE));
        demand.close();
        assertEquals(0, pending.releases);
        pending.close();
    }

    @Test
    void lateCompletionAfterCloseReleasesOnlyItsOwnLease() {
        var access = new Access();
        access.cached = uncancellableFuture();
        var old = new ModelResourceDemand(access, REQUEST);
        old.advance(CACHED);
        old.close();
        var replacement = new ModelResourceDemand(access, REQUEST);
        var replacementLease = Lease.ready();
        access.ready.add(replacementLease);
        assertSame(replacementLease, replacement.advance(CACHED));
        var late = Lease.ready();
        access.cached.complete(Optional.of(late));
        assertEquals(1, late.releases);
        assertEquals(0, replacementLease.releases);
        assertNull(old.advance(ONLINE));
        replacementLease.close();
        replacement.close();
    }

    @Test
    void completedButUnconsumedAcquisitionIsReleasedOnClose() {
        var access = new Access();
        access.cached = new CompletableFuture<>();
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(CACHED);
        var ready = Lease.ready();
        access.cached.complete(Optional.of(ready));
        demand.close();
        demand.close();
        assertEquals(1, ready.releases);
    }

    @Test
    void currentReadySupersedesProbeAndLateProbeCannotReplaceIt() {
        var access = new Access();
        access.cached = uncancellableFuture();
        var demand = new ModelResourceDemand(access, REQUEST);
        demand.advance(CACHED);
        var shared = Lease.ready();
        access.ready.add(shared);
        assertSame(shared, demand.advance(CACHED));
        var late = Lease.ready();
        access.cached.complete(Optional.of(late));
        assertEquals(1, late.releases);
        assertEquals(0, shared.releases);
        shared.close();
        demand.close();
    }

    @Test
    void failedAcquisitionRetainsCauseWithoutPerFrameRetryOrGlobalPoison() {
        var access = new Access();
        var failure = new IllegalArgumentException("invalid animation");
        access.cached = CompletableFuture.failedFuture(failure);
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
        access.cached = CompletableFuture.failedFuture(new CancellationException());
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

    private static <T> CompletableFuture<T> uncancellableFuture() {
        return new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
    }

    private static final class Access implements ModelResourceDemand.Access {
        boolean permitted = true;

        @Override
        public boolean permitsAcquisition(ResourceRequest request) {
            return permitted;
        }

        final ArrayDeque<ResourceLease> ready = new ArrayDeque<>();
        CompletableFuture<Optional<ResourceLease>> cached = CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<ResourceLease>> offline = CompletableFuture.completedFuture(Optional.empty());
        int cachedCalls, offlineCalls, onlineCalls;
        final java.util.List<WorkPriority> priorities = new java.util.ArrayList<>();

        @Override
        public Optional<ResourceLease> findReady(ResourceRequest request) {
            return Optional.ofNullable(ready.poll());
        }

        @Override
        public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request, WorkPriority priority) {
            cachedCalls++;
            priorities.add(priority);
            return cached;
        }

        @Override
        public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request, WorkPriority priority) {
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
