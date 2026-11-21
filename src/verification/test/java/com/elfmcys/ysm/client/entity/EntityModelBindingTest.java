package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationProvenance;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.util.UnsafeUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityModelBindingTest {
    private static final BakeProfile PROFILE = new BakeProfile("test");
    private static final ExpressionHost HOST = ExpressionHost.create(HostAvailability.none());

    @org.junit.jupiter.api.AfterEach
    void clearDemandDrivers() {
        DemandFixtures.clear();
    }

    @Test
    void matchingResourceDoesNotCompleteAnotherAuthorityApplication() throws Exception {
        var modelId = hash(90);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        try (var binding = new EntityModelBinding(models)) {
            binding.updateModelHash(modelId, "player", "main");
            var firstApplication = new Object();
            binding.select(modelId, "player", "main", firstApplication);
            var request = models.resourceRequest(modelId, "player", "main");
            var first = FakeLease.ready(modelId, target());
            assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
                binding.accept(request, first, false, false, EntityModelBindingTest::holder));
            assertTrue(binding.installedForSelection(firstApplication));
            var nextApplication = new Object();
            binding.select(modelId, "player", "main", nextApplication);
            assertFalse(binding.installedForSelection(nextApplication));
            assertFalse(binding.matches(request, false));
            assertSame(first, binding.resourceHolder().lease());
            assertTrue(first.poll() instanceof AcquireResult.Ready);
            var next = FakeLease.ready(modelId, target());
            assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
                binding.accept(request, next, false, false, EntityModelBindingTest::holder));
            assertTrue(binding.installedForSelection(nextApplication));
            assertFalse(binding.installedForSelection(firstApplication));
            assertEquals(1, first.closes.get());
        }
    }

    @Test
    void memoryFailurePreservesTheOldInstallationWithoutPermanentFailureMemory() throws Exception {
        var firstId = hash(116);
        var secondId = hash(117);
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        var first = FakeLease.ready(firstId, target());
        models.leases.add(first);
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        var old = binding.resourceHolder();
        var request = models.resourceRequest(secondId, "player", "candidate");
        binding.updateModelHash(secondId, "player", "candidate");
        var rejected = FakeLease.ready(secondId, target());
        var memory = new OutOfMemoryError("injected");
        var cleanupCalls = new AtomicInteger();
        rejected.onClose = () -> {
            cleanupCalls.incrementAndGet();
        };
        assertEquals(ClientDemandCoordinator.InstallationResult.REJECTED,
            binding.accept(request, rejected, false, false, (lease, fallback) -> {
                throw memory;
            }));
        assertSame(old, binding.resourceHolder());
        assertNull(binding.failure(request));
        assertEquals(1, cleanupCalls.get());
        var next = FakeLease.ready(secondId, target());
        assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
            binding.accept(request, next, false, false, EntityModelBindingTest::holder));
        DemandFixtures.close(binding);
    }

    @Test
    void memoryFailureOfTheRequiredDefaultStillPropagates() throws Exception {
        var models = new FakeModelAccess();
        models.contents.put(models.defaultId, content(models.defaultId));
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(null, "player", "candidate");
        var request = models.defaultResourceRequest("player");
        var lease = FakeLease.ready(models.defaultId, target());
        var memory = new OutOfMemoryError("injected required default");
        assertSame(memory, assertThrows(OutOfMemoryError.class,
            () -> binding.accept(request, lease, false, false, (owned, fallback) -> {
                throw memory;
            })));
        assertEquals(1, lease.closes.get());
        assertNull(binding.resourceHolder());
        DemandFixtures.close(binding);
    }

    @Test
    void guiInstallationRequiresAnIndependentFullReplacement() throws Exception {
        var modelId = hash(91);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(modelId);
        var request = models.resourceRequest(modelId, "player", "default", ModelResourcePurpose.GUI, null);
        var minimal = FakeLease.ready(modelId, target());
        minimal.purpose = ModelResourcePurpose.GUI;
        assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
            binding.accept(request, minimal, false, false, EntityModelBindingTest::holder));
        assertTrue(binding.matches(request, false));
        assertFalse(binding.matches(request, true));

        var fullRequest = models.resourceRequest(modelId, "player", "default", ModelResourcePurpose.FULL, null);
        var invalidUpgrade = FakeLease.ready(modelId, target());
        invalidUpgrade.purpose = ModelResourcePurpose.GUI;
        assertEquals(ClientDemandCoordinator.InstallationResult.STALE,
            binding.accept(fullRequest, invalidUpgrade, false, true, EntityModelBindingTest::holder));
        assertEquals(1, invalidUpgrade.closes.get());
        assertSame(minimal, binding.resourceHolder().lease());

        var full = FakeLease.ready(modelId, target());
        assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
            binding.accept(fullRequest, full, false, true, EntityModelBindingTest::holder));
        assertTrue(binding.matches(fullRequest, true));
        assertFalse(binding.matches(request, false));
        assertEquals(1, minimal.closes.get());
        binding.close();
        assertEquals(1, full.closes.get());
    }

    @Test
    void failedReplacementInstallationKeepsThePreviousHolderAndCause() throws Exception {
        var firstId = hash(92);
        var secondId = hash(93);
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        var first = FakeLease.ready(firstId, target());
        var rejected = FakeLease.ready(secondId, target());
        models.leases.add(first);
        models.leases.add(rejected);
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        var old = binding.resourceHolder();
        var failure = new IllegalArgumentException("subject rejected");
        binding.updateModelHash(secondId);
        DemandFixtures.drive(binding, "player", "default", null, (lease, fallback) -> {
            throw failure;
        });
        assertSame(old, binding.resourceHolder());
        assertSame(failure, binding.failure());
        assertEquals(AnimatableEntity.InstallationRelation.RETAINED, binding.relation("player", "default"));
        assertEquals(1, rejected.closes.get());
        assertEquals(0, first.closes.get());
        DemandFixtures.close(binding);
        assertEquals(1, first.closes.get());
    }

    @Test
    void privateConstructionFailurePreservesTheOriginalWhenLeaseCleanupFails() throws Exception {
        var modelId = hash(96);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        var binding = new EntityModelBinding(models);
        var request = models.resourceRequest(modelId, "player", "candidate");
        var lease = FakeLease.ready(modelId, target());
        var original = new IllegalStateException("constructor failed");
        var cleanup = new IllegalStateException("lease cleanup failed");
        lease.onClose = () -> {
            throw cleanup;
        };
        binding.updateModelHash(modelId, "player", "candidate");
        assertEquals(ClientDemandCoordinator.InstallationResult.REJECTED,
            binding.accept(request, lease, false, false, (candidate, fallback) -> {
                throw original;
            }));
        assertSame(original, binding.failure(request));
        assertArrayEquals(new Throwable[] {cleanup}, original.getSuppressed());
        assertNull(binding.resourceHolder());
    }

    @Test
    void sharedActivationFailureKeepsBothRequestIdentitiesAfterTheOldInstallationCloses() throws Exception {
        var modelId = hash(96);
        var identity = new ModelFileIdentity(modelId, Hash256.parse(hash(97).toString().substring(5)));
        var representation = (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
        var identityField = ModelRepresentation.class.getDeclaredField("identity");
        identityField.setAccessible(true);
        identityField.set(representation, identity);
        var models = new FakeModelAccess();
        models.contents.put(modelId, new ModelContent() {
            public ModelRepresentation representation() {
                return representation;
            }

            public ChunkDataSource chunks() {
                return null;
            }
        });
        var binding = new EntityModelBinding(models);
        var oldRequest = models.resourceRequest(modelId, "player", "old");
        var candidateRequest = models.resourceRequest(modelId, "player", "candidate");
        var oldLease = FakeLease.ready(modelId, target());
        var candidateLease = FakeLease.ready(modelId, target());
        binding.updateModelHash(modelId, "player", "old");
        assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
            binding.accept(oldRequest, oldLease, false, true, EntityModelBindingTest::holder));
        var failure = new com.elfmcys.ysm.expression.host.ComponentLifecycleException(getClass(),
            com.elfmcys.ysm.expression.host.ComponentLifecycleException.Phase.SET_RENDER_TARGET,
            new IllegalStateException("target hook failed"));
        binding.updateModelHash(modelId, "player", "candidate");
        assertEquals(ClientDemandCoordinator.InstallationResult.REJECTED,
            binding.accept(candidateRequest, candidateLease, false, true, (lease, fallback) -> {
                var next = holder(lease, fallback);
                setHolderField(next, "abortSharedStateActivation", (Runnable)binding::releaseRenderTarget);
                java.util.function.BiConsumer<Runnable, java.util.function.BooleanSupplier> activation =
                    (publication, qualified) -> {
                        setHolderField(next, "sharedStateActivationStarted", true);
                        throw failure;
                    };
                setHolderField(next, "activation", activation);
                return next;
            }));
        assertNull(binding.resourceHolder());
        assertEquals(1, oldLease.closes.get());
        assertEquals(1, candidateLease.closes.get());
        assertSame(failure, binding.failure(candidateRequest));
        assertNull(binding.failure(oldRequest));
        assertEquals(1, failure.getSuppressed().length);
        var provenance = (EntityModelBinding.ActivationProvenance)failure.getSuppressed()[0];
        assertSame(oldRequest, provenance.previousRequest());
        assertSame(candidateRequest, provenance.candidateRequest());
        assertEquals(identity, provenance.previousContent());
        assertEquals(identity, provenance.candidateContent());
        assertEquals(0, provenance.getStackTrace().length);
        binding.close();
    }

    @Test
    void sharedActivationMemoryFailureDoesNotRememberContentEvenWhenCleanupFails() throws Exception {
        var models = new FakeModelAccess();
        models.contents.put(models.defaultId, content(models.defaultId));
        try (var binding = new EntityModelBinding(models)) {
            binding.updateModelHash(null, "player", "candidate");
            var request = models.defaultResourceRequest("player");
            var previous = FakeLease.ready(models.defaultId, target());
            assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
                binding.accept(request, previous, false, true, EntityModelBindingTest::holder));
            var memory = new OutOfMemoryError("injected shared activation failure");
            var abortFailure = new IllegalStateException("injected abort failure");
            var closeFailure = new IllegalStateException("injected candidate close failure");
            var closeCalls = new AtomicInteger();
            var candidate = FakeLease.ready(models.defaultId, target());
            candidate.onClose = () -> {
                closeCalls.incrementAndGet();
                throw closeFailure;
            };
            assertSame(memory, assertThrows(OutOfMemoryError.class,
                () -> binding.accept(request, candidate, false, true, (lease, fallback) -> {
                    var next = holder(lease, fallback);
                    setHolderField(next, "abortSharedStateActivation", (Runnable)() -> {
                        binding.releaseRenderTarget();
                        throw abortFailure;
                    });
                    java.util.function.BiConsumer<Runnable, java.util.function.BooleanSupplier> activation =
                        (publication, qualified) -> {
                            setHolderField(next, "sharedStateActivationStarted", true);
                            throw memory;
                        };
                    setHolderField(next, "activation", activation);
                    return next;
                })));
            assertNull(binding.resourceHolder());
            assertNull(binding.failure(request));
            assertEquals(1, previous.closes.get());
            assertEquals(1, closeCalls.get());
            assertArrayEquals(new Throwable[] {abortFailure, closeFailure}, memory.getSuppressed());
            var retry = FakeLease.ready(models.defaultId, target());
            assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
                binding.accept(request, retry, false, true, EntityModelBindingTest::holder));
        }
    }

    @Test
    void fatalCleanupFailureCannotBeDowngradedByAnInstallationMemoryFailure() throws Exception {
        for (boolean shared : new boolean[] {false, true}) {
            var models = new FakeModelAccess();
            var modelId = hash(99);
            models.contents.put(modelId, content(modelId));
            try (var binding = new EntityModelBinding(models)) {
                binding.updateModelHash(modelId, "player", "candidate");
                var request = models.resourceRequest(modelId, "player", "candidate");
                var previous = FakeLease.ready(modelId, target());
                binding.accept(request, previous, false, true, EntityModelBindingTest::holder);
                var memory = new OutOfMemoryError("injected installation failure");
                var fatal = new InternalError("injected fatal cleanup failure");
                var closeCalls = new AtomicInteger();
                var candidate = FakeLease.ready(modelId, target());
                candidate.onClose = () -> {
                    closeCalls.incrementAndGet();
                    if (!shared)
                        throw fatal;
                };
                assertSame(fatal, assertThrows(InternalError.class,
                    () -> binding.accept(request, candidate, false, true, (lease, fallback) -> {
                        if (!shared)
                            throw memory;
                        var next = holder(lease, fallback);
                        setHolderField(next, "abortSharedStateActivation", (Runnable)() -> {
                            binding.releaseRenderTarget();
                            throw fatal;
                        });
                        java.util.function.BiConsumer<Runnable, java.util.function.BooleanSupplier> activation =
                            (publication, qualified) -> {
                                setHolderField(next, "sharedStateActivationStarted", true);
                                throw memory;
                            };
                        setHolderField(next, "activation", activation);
                        return next;
                    })));
                assertArrayEquals(new Throwable[] {memory}, fatal.getSuppressed());
                assertEquals(1, closeCalls.get());
                assertEquals(shared ? 1 : 0, previous.closes.get());
                assertNull(binding.failure(request));
            }
        }
    }

    @Test
    void diagnosticAllocationFailureCannotSkipSharedAbortOrCandidateClose() throws Exception {
        var models = new FakeModelAccess();
        var modelId = hash(98);
        models.contents.put(modelId, content(modelId));
        try (var binding = new EntityModelBinding(models)) {
            binding.updateModelHash(modelId, "player", "candidate");
            var request = models.resourceRequest(modelId, "player", "candidate");
            var previous = FakeLease.ready(modelId, target());
            binding.accept(request, previous, false, true, EntityModelBindingTest::holder);
            var diagnostic = new OutOfMemoryError("injected failure-memory allocation");
            var failure = new IllegalStateException("injected shared activation failure");
            var failures = EntityModelBinding.class.getDeclaredField("rejectedInstallations");
            failures.setAccessible(true);
            failures.set(binding, new HashMap<ResourceRequest, Object>() {
                private boolean first = true;

                @Override
                public Object put(ResourceRequest key, Object value) {
                    if (first) {
                        first = false;
                        throw diagnostic;
                    }
                    return super.put(key, value);
                }
            });
            var candidate = FakeLease.ready(modelId, target());
            assertEquals(ClientDemandCoordinator.InstallationResult.REJECTED,
                binding.accept(request, candidate, false, true, (lease, fallback) -> {
                    var next = holder(lease, fallback);
                    setHolderField(next, "abortSharedStateActivation", (Runnable)binding::releaseRenderTarget);
                    java.util.function.BiConsumer<Runnable, java.util.function.BooleanSupplier> activation =
                        (publication, qualified) -> {
                            setHolderField(next, "sharedStateActivationStarted", true);
                            throw failure;
                        };
                    setHolderField(next, "activation", activation);
                    return next;
                }));
            assertNull(binding.resourceHolder());
            assertEquals(1, previous.closes.get());
            assertEquals(1, candidate.closes.get());
            assertSame(failure, binding.failure(request));
            assertTrue(List.of(failure.getSuppressed()).contains(diagnostic));
        }
    }

    private static void setHolderField(AnimatableEntity.ResourceHolder holder, String name, Object value) {
        try {
            var field = AnimatableEntity.ResourceHolder.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(holder, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void everyPrimaryRequestUsesTheFrozenHostPlan() throws Exception {
        var modelId = hash(90);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        models.leases.add(FakeLease.ready(modelId, target()));
        var binding = new EntityModelBinding(models, () -> 0L);

        binding.updateModelHash(modelId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);

        assertSame(models.host.expressionBinding(), models.requests.get(0).expressionBinding());
    }

    @Test
    void rejectedCandidateIsNotRebuiltOnSwitchBackUntilItsContentChanges() throws Exception {
        var models = new FakeModelAccess();
        var firstId = hash(94);
        var rejectedId = hash(95);
        models.contents.put(firstId, content(firstId));
        models.contents.put(rejectedId, content(rejectedId));
        models.leases.add(FakeLease.ready(firstId, target()));
        models.leases.add(FakeLease.ready(rejectedId, target()));
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "", null, EntityModelBindingTest::holder);
        var attempts = new AtomicInteger();
        var failure = new IllegalArgumentException("invalid candidate");
        binding.updateModelHash(rejectedId);
        DemandFixtures.drive(binding, "player", "", null, (lease, fallback) -> {
            attempts.incrementAndGet();
            throw failure;
        });
        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "", null, EntityModelBindingTest::holder);
        binding.updateModelHash(rejectedId);
        DemandFixtures.drive(binding, "player", "", null, (lease, fallback) -> {
            attempts.incrementAndGet();
            throw new AssertionError("Known content must not be rebuilt");
        });
        assertEquals(1, attempts.get());
        assertEquals(2, models.acquisitions.get());
        assertSame(failure, binding.failure());
        models.contents.put(rejectedId, content(rejectedId));
        var recovered = FakeLease.ready(rejectedId, target());
        models.leases.add(recovered);
        DemandFixtures.drive(binding, "player", "", null, EntityModelBindingTest::holder);
        assertSame(((AcquireResult.Ready)recovered.pollResult()).target(),
            ((AcquireResult.Ready)binding.resourceHolder().lease().poll()).target());
        DemandFixtures.close(binding);
    }

    @Test
    void defaultCandidateFailurePropagatesWithoutOrdinaryModelRecovery() throws Exception {
        var models = new FakeModelAccess();
        models.contents.put(models.defaultId, content(models.defaultId));
        var candidate = FakeLease.ready(models.defaultId, target());
        models.leases.add(candidate);
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(null);
        var failure = new IllegalStateException("invalid intrinsic default");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> DemandFixtures.drive(binding, "player", "", "player", (lease, fallback) -> {
                throw failure;
            })));
        assertEquals(1, candidate.closes.get());
        assertEquals(1, models.acquisitions.get());
        assertSame(failure, binding.failure(models.defaultResourceRequest("player")));
        binding.updateModelHash(hash(101));
        DemandFixtures.drive(binding, "player", "", "player", (lease, fallback) -> {
            throw new AssertionError("Rejected default must not be rebuilt as a fallback");
        });
        binding.updateModelHash(null);
        DemandFixtures.drive(binding, "player", "", "player", (lease, fallback) -> {
            throw new AssertionError("Rejected default must not be rebuilt after switching back");
        });
        assertEquals(1, models.acquisitions.get());
        assertEquals(0, models.failures.get());
        models.contents.put(models.defaultId, content(models.defaultId));
        models.leases.add(FakeLease.ready(models.defaultId, target()));
        DemandFixtures.drive(binding, "player", "", "player", EntityModelBindingTest::holder);
        assertNotNull(binding.resourceHolder());
        assertEquals(2, models.acquisitions.get());
        DemandFixtures.close(binding);
    }

    @Test
    void replacementReleasesReadyLeasesAndCancelsReplacedPending() throws Exception {
        var firstId = hash(1);
        var secondId = hash(2);
        var thirdId = hash(3);
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        models.contents.put(thirdId, content(thirdId));
        var first = FakeLease.ready(firstId, target());
        var second = FakeLease.pending(secondId);
        var third = FakeLease.pending(thirdId);
        models.leases.add(first);
        models.leases.add(second);
        models.leases.add(third);
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        var firstHolder = binding.resourceHolder();
        assertSame(((AcquireResult.Ready)first.result).target(),
            ((AcquireResult.Ready)firstHolder.lease().poll()).target());

        binding.updateModelHash(secondId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        assertSame(firstHolder, binding.resourceHolder());
        assertEquals(0, first.closes.get());

        binding.updateModelHash(thirdId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        assertEquals(1, second.closes.get());
        assertSame(firstHolder, binding.resourceHolder());

        third.result = new AcquireResult.Ready(target());
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        assertSame(((AcquireResult.Ready)third.pollResult()).target(),
            ((AcquireResult.Ready)binding.resourceHolder().lease().poll()).target());
        assertEquals(1, first.closes.get());
        assertEquals(0, third.closes.get());

        DemandFixtures.close(binding);
        assertEquals(1, third.closes.get());
    }

    @Test
    void deterministicFailureMarkerPreventsPerTickReacquisition() {
        var modelId = hash(4);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        var failed = FakeLease.failed(modelId, ResourceFailure.Kind.DETERMINISTIC);
        models.leases.add(failed);
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(modelId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);

        assertEquals(1, models.acquisitions.get());
        assertEquals(1, models.failures.get());
        assertEquals(1, failed.closes.get());
        assertEquals(0, failed.pendingCancellations.get());
    }

    @Test
    void unavailableNonPlayerReplacementAcquiresNoYsmTarget() {
        var modelId = hash(9);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(modelId);
        DemandFixtures.drive(binding, null, "", null, EntityModelBindingTest::holder);

        assertNull(binding.resourceHolder());
        assertEquals(0, models.acquisitions.get());
        assertEquals(0, models.failures.get());
    }

    @Test
    void rejectedReadyAndTemporaryFallbackHandlesAreClosed() throws Exception {
        var modelId = hash(5);
        var defaultId = hash(6);
        var models = new FakeModelAccess();
        models.defaultId = defaultId;
        models.contents.put(modelId, content(modelId));
        var rejected = FakeLease.ready(modelId, target());
        var fallbackPending = FakeLease.pending(defaultId);
        models.leases.add(rejected);
        models.leases.add(fallbackPending);
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(modelId);
        DemandFixtures.drive(binding, "player", "default", null, (lease, fallback) -> null);
        DemandFixtures.drive(binding, "player", "default", null, (lease, fallback) -> null);
        assertEquals(1, models.acquisitions.get());
        assertEquals(1, rejected.closes.get());

        binding.updateModelHash(null);
        DemandFixtures.drive(binding, null, "", "player", EntityModelBindingTest::holder);
        DemandFixtures.close(binding);
        assertEquals(1, fallbackPending.closes.get());
    }

    @Test
    void remoteMissUsesStrictPerEntitySwitchDwell() throws Exception {
        var clock = new AtomicLong();
        var firstId = hash(20);
        var secondId = hash(21);
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        models.offlineMisses++;
        models.offlineMisses++;
        models.leases.add(FakeLease.ready(firstId, target()));
        models.leases.add(FakeLease.pending(secondId));
        var binding = new EntityModelBinding(models, clock::get);

        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        assertEquals(1, models.onlineAcquisitions.get());

        clock.set(700);
        binding.updateModelHash(secondId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        clock.set(1_400);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        assertEquals(1, models.onlineAcquisitions.get());

        clock.set(1_401);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        assertEquals(2, models.onlineAcquisitions.get());
    }

    @Test
    void independentEntitiesDoNotResetEachOthersDemandClock() throws Exception {
        var clock = new AtomicLong();
        var firstId = hash(22);
        var secondId = hash(23);
        var firstModels = missingThenReady(firstId, secondId);
        var secondModels = missingThenReady(firstId, secondId);
        var first = new EntityModelBinding(firstModels, clock::get);
        var second = new EntityModelBinding(secondModels, clock::get);

        first.updateModelHash(firstId);
        DemandFixtures.drive(first, "player", "default", null, EntityModelBindingTest::holder);
        second.updateModelHash(firstId);
        DemandFixtures.drive(second, "player", "default", null, EntityModelBindingTest::holder);

        clock.set(100);
        first.updateModelHash(secondId);
        DemandFixtures.drive(first, "player", "default", null, EntityModelBindingTest::holder);
        clock.set(500);
        second.updateModelHash(secondId);
        DemandFixtures.drive(second, "player", "default", null, EntityModelBindingTest::holder);

        clock.set(801);
        DemandFixtures.drive(first, "player", "default", null, EntityModelBindingTest::holder);
        DemandFixtures.drive(second, "player", "default", null, EntityModelBindingTest::holder);
        assertEquals(2, firstModels.onlineAcquisitions.get());
        assertEquals(1, secondModels.onlineAcquisitions.get());
    }

    @Test
    void sameModelTextureChangeStartsANewStrictDemandInterval() throws Exception {
        var clock = new AtomicLong();
        var modelId = hash(24);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        models.offlineMisses++;
        models.offlineMisses++;
        models.leases.add(FakeLease.ready(modelId, "first", target()));
        models.leases.add(FakeLease.pending(modelId));
        var binding = new EntityModelBinding(models, clock::get);

        binding.updateModelHash(modelId, "player", "first");
        DemandFixtures.drive(binding, "player", "first", null, EntityModelBindingTest::holder);
        assertEquals(1, models.onlineAcquisitions.get());

        clock.set(100);
        binding.updateModelHash(modelId, "player", "second");
        DemandFixtures.drive(binding, "player", "second", null, EntityModelBindingTest::holder);
        clock.set(800);
        DemandFixtures.drive(binding, "player", "second", null, EntityModelBindingTest::holder);
        assertEquals(1, models.onlineAcquisitions.get());

        clock.set(801);
        DemandFixtures.drive(binding, "player", "second", null, EntityModelBindingTest::holder);
        assertEquals(2, models.onlineAcquisitions.get());
    }

    @Test
    void severeFailureDegradesOneOwnerAndSurvivesSwitchBackUntilContentChanges() throws Exception {
        var firstId = hash(91);
        var secondId = hash(92);
        var models = new FakeModelAccess();
        var failedContent = content(firstId);
        models.contents.put(firstId, failedContent);
        models.contents.put(secondId, content(secondId));
        var first = FakeLease.ready(firstId, target());
        models.leases.add(first);
        models.leases.add(FakeLease.ready(secondId, target()));
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "", null, EntityModelBindingTest::holder);
        binding.failExpression(severeFailure(), null, binding::releaseRenderTarget);
        assertNull(binding.resourceHolder());
        assertEquals(1, first.closes.get());
        assertEquals(1, models.failures.get());

        DemandFixtures.drive(binding, "player", "", null, EntityModelBindingTest::holder);
        binding.updateModelHash(secondId);
        DemandFixtures.drive(binding, "player", "", null, EntityModelBindingTest::holder);
        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "", null, EntityModelBindingTest::holder);
        assertEquals(2, models.acquisitions.get());

        var another = new FakeModelAccess();
        another.contents.put(firstId, failedContent);
        another.leases.add(FakeLease.ready(firstId, target()));
        var otherOwner = new EntityModelBinding(another);
        otherOwner.updateModelHash(firstId);
        DemandFixtures.drive(otherOwner, "player", "", null, EntityModelBindingTest::holder);
        assertEquals(1, another.acquisitions.get());

        models.contents.put(firstId, content(firstId));
        var retried = FakeLease.ready(firstId, target());
        models.leases.add(retried);
        DemandFixtures.drive(binding, "player", "", null, EntityModelBindingTest::holder);
        assertSame(((AcquireResult.Ready)retried.pollResult()).target(),
            ((AcquireResult.Ready)binding.resourceHolder().lease().poll()).target());
        assertEquals(3, models.acquisitions.get());
        DemandFixtures.close(binding);
        DemandFixtures.close(otherOwner);
    }

    @Test
    void humanoidSevereFailureInstallsDefaultWithoutChangingAuthoritativeSelection() throws Exception {
        var modelId = hash(93);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        var primary = FakeLease.ready(modelId, target());
        var fallback = FakeLease.ready(models.defaultId, target());
        models.leases.add(primary);
        models.leases.add(fallback);
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(modelId);
        DemandFixtures.drive(binding, "player", "", "player", EntityModelBindingTest::holder);
        binding.failExpression(severeFailure(), "player", binding::releaseRenderTarget);
        DemandFixtures.drive(binding, "player", "", "player", EntityModelBindingTest::holder);
        assertSame(modelId, binding.modelHash());
        assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, binding.relation("player", ""));
        assertSame(((AcquireResult.Ready)fallback.pollResult()).target(),
            ((AcquireResult.Ready)binding.resourceHolder().lease().poll()).target());
        assertEquals(1, primary.closes.get());
        DemandFixtures.drive(binding, "player", "", "player", EntityModelBindingTest::holder);
        assertEquals(2, models.acquisitions.get());
        var failure = severeFailure();
        assertSame(failure, assertThrows(ExecutionFailure.class,
            () -> binding.failExpression(failure, "player", binding::releaseRenderTarget)));
        assertEquals(1, fallback.closes.get());
        DemandFixtures.close(binding);
        assertEquals(1, fallback.closes.get());
    }

    @Test
    void explicitDefaultAndOrdinaryFailuresNeverEnterARecoveryLoop() throws Exception {
        var models = new FakeModelAccess();
        models.contents.put(models.defaultId, content(models.defaultId));
        var primary = FakeLease.ready(models.defaultId, target());
        models.leases.add(primary);
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(null);
        DemandFixtures.drive(binding, "player", "", "player", EntityModelBindingTest::holder);
        var failure = severeFailure();
        assertSame(failure, assertThrows(ExecutionFailure.class,
            () -> binding.failExpression(failure, "player", binding::releaseRenderTarget)));
        var ordinary = new ExecutionFailure(ExecutionFailure.Reason.USER_FUNCTION_DEPTH, failure.provenance(),
            "ordinary depth failure");
        assertSame(ordinary, assertThrows(ExecutionFailure.class,
            () -> binding.failExpression(ordinary, "player", binding::releaseRenderTarget)));
        assertEquals(1, models.acquisitions.get());
        assertEquals(1, primary.closes.get());
        DemandFixtures.close(binding);
        assertEquals(1, primary.closes.get());
    }

    private static ExecutionFailure severeFailure() {
        return new ExecutionFailure(ExecutionFailure.Reason.INSTANCE_EXECUTION,
            new InvocationProvenance("player", "provider", 0, 1, 1), "instance execution failure");
    }

    private static FakeModelAccess missingThenReady(ModelId firstId, ModelId secondId) throws Exception {
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        models.offlineMisses++;
        models.offlineMisses++;
        models.leases.add(FakeLease.ready(firstId, target()));
        models.leases.add(FakeLease.pending(secondId));
        return models;
    }

    private static AnimatableEntity.ResourceHolder holder(ResourceLease lease, boolean fallback) {
        return new AnimatableEntity.ResourceHolder(lease);
    }

    private static ModelRenderTarget target() throws InstantiationException {
        return (ModelRenderTarget)UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
    }

    private static ModelId hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return ModelId.fromHash(new Hash256(bytes));
    }

    private static ModelContent content(ModelId modelId) {
        return new ModelContent() {
            @Override
            public ModelId modelId() {
                return modelId;
            }

            @Override
            public ModelRepresentation representation() {
                return null;
            }

            @Override
            public ModelFileView modelFile() {
                return null;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }
        };
    }

    @Test
    void initialIntrinsicSelectionDoesNotBecomeAnOrdinaryPublishedDefault() {
        var models = new FakeModelAccess();
        try (var binding = new EntityModelBinding(models)) {
            binding.requestBuiltinDefault();
            assertNull(binding.modelHash());
            var initial = binding.assignment("player", "", "player", ModelResourceDemand.Mode.ONLINE,
                ModelResourcePurpose.FULL, null);
            assertTrue(initial.request().modelId().isIntrinsicDefault());
            models.contents.put(models.defaultId, content(models.defaultId));
            assertNull(binding.modelHash());
            binding.select(models.defaultId, "player", "main", new Object());
            assertEquals(models.defaultId, binding.modelHash());
        }
    }

    @Test
    void ordinaryDefaultFailureCannotRejectTheSameContentIntrinsicInstallation() throws Exception {
        var models = new FakeModelAccess();
        models.contents.put(models.defaultId, content(models.defaultId));
        var binding = new EntityModelBinding(models);
        binding.updateModelHash(models.defaultId);
        var ordinary = models.resourceRequest(models.defaultId, "player", "");
        var failure = new IllegalStateException("ordinary candidate failed");
        assertEquals(ClientDemandCoordinator.InstallationResult.REJECTED,
            binding.accept(ordinary, FakeLease.ready(models.defaultId, target()), false, false, (lease, fallback) -> {
                throw failure;
            }));
        var intrinsic = models.defaultResourceRequest("player");
        assertNull(binding.failure(intrinsic));
        var fallbackLease = FakeLease.ready(models.defaultId, target());
        fallbackLease.role = PublishedModelId.Role.INTRINSIC_DEFAULT;
        assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED,
            binding.accept(intrinsic, fallbackLease, true, false, EntityModelBindingTest::holder));
        assertTrue(binding.isIntrinsicDefault());
        assertEquals(models.defaultId, binding.modelHash());
        assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, binding.relation("player", ""));
        assertSame(failure, binding.failure(ordinary));
        binding.updateModelHash(null);
        assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, binding.relation("player", ""));
        var explicit = FakeLease.ready(models.defaultId, target());
        explicit.role = PublishedModelId.Role.INTRINSIC_DEFAULT;
        binding.accept(intrinsic, explicit, false, false, EntityModelBindingTest::holder);
        assertEquals(AnimatableEntity.InstallationRelation.MATCHING, binding.relation("player", ""));
        binding.updateModelHash(models.defaultId);
        assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, binding.relation("player", ""));
        DemandFixtures.close(binding);
    }

    private static final class FakeModelAccess implements DemandFixtures.ModelAccess {
        @Override
        public boolean permitsAcquisition(ResourceRequest request) {
            return true;
        }

        private ClientDemandCoordinator coordinator;
        private final Map<ResourceRequest, FakeLease> active = new HashMap<>();

        @Override
        public ClientDemandCoordinator coordinator(java.util.function.LongSupplier clock) {
            if (coordinator == null)
                coordinator = DemandFixtures.coordinator(this, clock);
            return coordinator;
        }

        private ResourceLease track(ResourceRequest request, ResourceLease lease) {
            active.put(request, (FakeLease)lease);
            return lease;
        }

        private ExpressionHost host = HOST;

        @Override
        public Object requestOwner() {
            return host;
        }

        private final HashMap<ModelId, ModelContent> contents = new HashMap<>();
        private final ArrayDeque<ResourceLease> leases = new ArrayDeque<>();
        private final ArrayDeque<ResourceLease> ready = new ArrayDeque<>();
        private int offlineMisses;
        private final ArrayList<ResourceRequest> requests = new ArrayList<>();
        private final ArrayList<WorkPriority> priorities = new ArrayList<>();
        private final AtomicInteger acquisitions = new AtomicInteger();
        private final AtomicInteger onlineAcquisitions = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final ArrayList<ResourceRequest> uses = new ArrayList<>();
        private ModelId defaultId = hash(100);

        @Override
        public ModelId builtinDefaultModelId() {
            return defaultId;
        }

        @Override
        public ModelContent content(ModelId modelId) {
            return contents.get(modelId);
        }

        @Override
        public ModelContent content(PublishedModelId modelId) {
            return content(modelId instanceof PublishedModelId.Model model ? model.hash() : defaultId);
        }

        @Override
        public ResourceRequest resourceRequest(ModelId modelId, String targetId, String textureName) {
            var request = new ResourceRequest(modelId, targetId, textureName, PROFILE, host.expressionBinding());
            requests.add(request);
            return request;
        }

        @Override
        public ResourceRequest defaultResourceRequest(String targetId) {
            var request = new ResourceRequest(PublishedModelId.INTRINSIC_DEFAULT, targetId, "", PROFILE,
                host.expressionBinding(), ModelResourcePurpose.FULL);
            requests.add(request);
            return request;
        }

        @Override
        public Optional<ResourceLease> findReady(ResourceRequest request) {
            var current = active.get(request);
            if (current != null && !current.closed && current.result instanceof AcquireResult.Ready) {
                return Optional.of(new FakeLease(current.modelId, current.textureName, current.result));
            }
            var cached = ready.poll();
            if (cached != null)
                active.put(request, (FakeLease)cached);
            return Optional.ofNullable(cached);
        }

        @Override
        public ResourceLease getOrStartCached(ResourceRequest request, WorkPriority priority) {
            priorities.add(priority);
            return ResourceLease.missing(request);
        }

        @Override
        public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
            acquisitions.incrementAndGet();
            onlineAcquisitions.incrementAndGet();
            return track(request, leases.removeFirst());
        }

        @Override
        public ResourceLease getOrStartOffline(ResourceRequest request, WorkPriority priority) {
            acquisitions.incrementAndGet();
            if (offlineMisses > 0) {
                offlineMisses--;
                return ResourceLease.missing(request);
            }
            return track(request, leases.removeFirst());
        }

        @Override
        public void reportActiveModelUse(ResourceRequest request) {
            uses.add(request);
        }

        @Override
        public void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable) {
            failures.incrementAndGet();
        }
    }

    private static final class FakeLease implements ResourceLease {
        private Runnable onClose = () -> {
        };
        private final ModelId modelId;
        private final String textureName;
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger pendingCancellations = new AtomicInteger();
        private AcquireResult result;
        private ModelResourcePurpose purpose = ModelResourcePurpose.FULL;
        private PublishedModelId.Role role;
        private boolean closed;
        private int currentQueries;
        private java.util.function.Predicate<ResourceRequest> current = request -> true;

        private FakeLease(ModelId modelId, AcquireResult result) {
            this(modelId, null, result);
        }

        private FakeLease(ModelId modelId, String textureName, AcquireResult result) {
            this.modelId = modelId;
            this.textureName = textureName;
            this.result = result;
        }

        private static FakeLease pending(ModelId modelId) {
            return new FakeLease(modelId, new AcquireResult.Pending());
        }

        private static FakeLease ready(ModelId modelId, ModelRenderTarget target) {
            return new FakeLease(modelId, new AcquireResult.Ready(target));
        }

        private static FakeLease ready(ModelId modelId, String textureName, ModelRenderTarget target) {
            return new FakeLease(modelId, textureName, new AcquireResult.Ready(target));
        }

        private static FakeLease failed(ModelId modelId, ResourceFailure.Kind kind) {
            return new FakeLease(modelId,
                new AcquireResult.Failed(new ResourceFailure(kind, new IllegalStateException("failed"))));
        }

        @Override
        public ModelResourcePurpose purpose() {
            return purpose;
        }

        @Override
        public AcquireResult poll() {
            requireOpen();
            return result;
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            requireOpen();
            currentQueries++;
            return current.test(request) && (role == null || role == request.modelId().role())
                && (request.modelId().isIntrinsicDefault()
                    || modelId.equals(((PublishedModelId.Model)request.modelId()).hash()))
                && purpose == request.purpose()
                && (textureName == null || textureName.equals(request.requestedTexture()));
        }

        @Override
        public void close() {
            if (!closed) {
                onClose.run();
                closed = true;
                closes.incrementAndGet();
            }
        }

        @Override
        public void cancelPending() {
            if (!closed && result instanceof AcquireResult.Pending) {
                closed = true;
                pendingCancellations.incrementAndGet();
            }
        }

        private AcquireResult pollResult() {
            return result;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("closed");
            }
        }
    }
}
