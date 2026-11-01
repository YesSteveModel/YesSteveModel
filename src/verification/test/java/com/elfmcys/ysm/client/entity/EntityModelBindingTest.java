package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;

import com.elfmcys.ysm.expression.host.builtin.PositionComponent;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.FPArmConditionManager;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.host.MolangExtensions;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.expression.host.InvocationProvenance;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.util.FifoHashMap;
import com.elfmcys.ysm.util.UnsafeUtil;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityModelBindingTest {
    private static final BakeProfile PROFILE = new BakeProfile("test");
    private static final ExpressionHost HOST = ExpressionHost.create(HostAvailability.none());
    private static LivingEntity subject;

    @BeforeAll
    static void initializeMinecraftEntityClasses() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        var gate = Bootstrap.class.getDeclaredField("isBootstrapped");
        gate.setAccessible(true);
        boolean previous = gate.getBoolean(null);
        gate.setBoolean(null, true);
        try {
            BuiltInRegistries.REGISTRY.size();
            Class.forName(ArmorStand.class.getName(), true, EntityModelBindingTest.class.getClassLoader());
        } finally {
            gate.setBoolean(null, previous);
        }
        subject = (LivingEntity)UnsafeUtil.getUnsafe().allocateInstance(ArmorStand.class);
        var locator = com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorTypeRegistry
            .get(ResourceLocation.tryParse("ysm:player"));
        if (locator == null) {
            var locatorConstructor = com.elfmcys.ysm.client.model.locator.PlayerLocator.class.getDeclaredConstructor();
            locatorConstructor.setAccessible(true);
            locator = locatorConstructor.newInstance();
            var freezeLocator =
                com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType.class.getDeclaredMethod("freeze");
            freezeLocator.setAccessible(true);
            freezeLocator.invoke(locator);
        }
        var locatorField = com.elfmcys.ysm.client.model.locator.PlayerLocator.class.getDeclaredField("INSTANCE");
        locatorField.setAccessible(true);
        locatorField.set(null, locator);
    }

    @org.junit.jupiter.api.AfterEach
    void clearDemandDrivers() {
        DemandFixtures.clear();
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
        binding.updateModelHash(models.defaultId, "player", "candidate");
        var request = models.resourceRequest(models.defaultId, "player", "candidate");
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
    void demandRegistrationWithoutMinecraftUsesBackgroundPriority() throws Exception {
        var instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        var previous = instance.get(null);
        try {
            instance.set(null, null);
            assertDemandPriority(subject, WorkPriority.BACKGROUND);
        } finally {
            instance.set(null, previous);
        }
    }

    @Test
    void demandRegistrationUsesOnlyTheCurrentLocalPlayerIdentity() throws Exception {
        var instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        var previous = instance.get(null);
        var unsafe = UnsafeUtil.getUnsafe();
        var client = (Minecraft)unsafe.allocateInstance(Minecraft.class);
        var player = (LocalPlayer)unsafe.allocateInstance(LocalPlayer.class);
        client.player = player;
        try {
            instance.set(null, client);
            assertDemandPriority(player, WorkPriority.LOCAL_PLAYER);
            assertDemandPriority((LocalPlayer)unsafe.allocateInstance(LocalPlayer.class), WorkPriority.BACKGROUND);
            assertDemandPriority((RemotePlayer)unsafe.allocateInstance(RemotePlayer.class), WorkPriority.BACKGROUND);
            var guiPlayer =
                (CustomGuiPlayerEntity.FakePlayer)unsafe.allocateInstance(CustomGuiPlayerEntity.FakePlayer.class);
            assertTrue(CustomGuiPlayerEntity.isFakePlayer(guiPlayer));
            assertDemandPriority(guiPlayer, WorkPriority.BACKGROUND);
            assertDemandPriority(subject, WorkPriority.BACKGROUND);
            client.player = null;
            assertDemandPriority(subject, WorkPriority.BACKGROUND);
        } finally {
            instance.set(null, previous);
        }
    }

    private static void assertDemandPriority(LivingEntity subject, WorkPriority expected) throws Exception {
        var id = hash(89);
        var models = new FakeModelAccess();
        models.contents.put(id, content(id));
        var owner = new TestHumanoid(subject, new EntityModelBinding(models));
        owner.mode = ModelResourceDemand.Mode.CACHED;
        try {
            owner.updateModelAndTexture(id, "main");
            DemandFixtures.advance(owner);
            assertEquals(List.of(expected), models.priorities, subject.getClass().getName());
        } finally {
            owner.retire();
        }
    }

    @Test
    void cachedBindingInstallsLaterReadyWithoutWorldTicksOrAnotherAcquire() throws Exception {
        var modelId = hash(91);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        var binding = new EntityModelBinding(models);
        var entity = new TestHumanoid(binding);
        entity.mode = ModelResourceDemand.Mode.CACHED;
        subject.tickCount = 0;
        entity.updateModelAndTexture(modelId, "default");
        DemandFixtures.advance(entity);
        assertNull(entity.getModelRenderTarget());
        assertEquals(0, models.acquisitions.get());

        var target = playerTarget(modelId, "default");
        var ready = FakeLease.ready(modelId, target);
        models.ready.add(ready);
        DemandFixtures.advance(entity);
        assertEquals(0, subject.tickCount);
        assertSame(target, entity.getModelRenderTarget());
        assertSame(((AcquireResult.Ready)ready.pollResult()).target(),
            ((AcquireResult.Ready)binding.resourceHolder().lease().poll()).target());
        assertEquals(AnimatableEntity.InstallationRelation.MATCHING, binding.relation("player", "default"));
        assertEquals(0, models.acquisitions.get());
        entity.reset();
        assertEquals(1, ready.closes.get());
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
        var identity = new ModelFileIdentity(modelId, hash(97));
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
                setHolderField(next, "ownsState", false);
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
        binding.updateModelHash(models.defaultId);
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
        binding.updateModelHash(models.defaultId);
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
    void modelSwitchDoesNotExposeActivationRetryCapability() {
        var firstId = hash(30);
        var secondId = hash(31);
        var models = new FakeModelAccess();
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        binding.updateModelHash(secondId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);
        binding.updateModelHash(firstId);
        DemandFixtures.drive(binding, "player", "default", null, EntityModelBindingTest::holder);

        assertFalse(java.util.Arrays.stream(EntityModelBinding.ModelAccess.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("retryModelUse")));
        assertEquals(0, models.acquisitions.get());
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
    void pendingModelReplacementKeepsRequestedTextureWhileOldVariantRemainsVisible() throws Exception {
        var currentId = hash(7);
        var replacementId = hash(8);
        var models = new FakeModelAccess();
        models.contents.put(currentId, content(currentId));
        models.contents.put(replacementId, content(replacementId));
        var currentTarget = playerTarget(currentId, "current");
        models.leases.add(FakeLease.ready(currentId, currentTarget));
        models.leases.add(FakeLease.pending(replacementId));
        var entity = new TestHumanoid(new EntityModelBinding(models));

        entity.updateModelAndTexture(currentId, "current");

        DemandFixtures.advance(entity);
        entity.updateModelAndTexture(replacementId, "alternate");
        DemandFixtures.advance(entity);

        assertTrue(models.requests.stream().anyMatch(request -> request.requestedTexture().equals("alternate")));
        assertEquals("alternate", entity.requestedTexture());
        assertSame(currentTarget.playerResources().defaultVariant(), entity.getModelVariant());
    }

    @Test
    void remoteMissUsesStrictPerEntitySwitchDwell() throws Exception {
        var clock = new AtomicLong();
        var firstId = hash(20);
        var secondId = hash(21);
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
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
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
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
    void synchronousOwnerWithoutRetirementIsCollectibleAndReleasesItsExpressionLease(@TempDir Path cache)
        throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(cache, track(new AtomicSharedCache(cache)))), HOST, metrics);
        try (var module = faultModule(factory, 113, "return q.position(0);", false)) {
            var target = playerTarget(hash(113), "main", module);
            var reference = abandonSynchronousOwner(target);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && (reference.get() != null
                || metrics.snapshot().active(dev.semaweave.compilation.EngineMetrics.Owner.INSTANCES) != 0)) {
                System.gc();
                Thread.sleep(10);
            }
            assertNull(reference.get());
            assertEquals(0, metrics.snapshot().active(dev.semaweave.compilation.EngineMetrics.Owner.INSTANCES));
            assertEquals(1, metrics.snapshot().active(dev.semaweave.compilation.EngineMetrics.Owner.RESOURCE_LEASES));
            java.lang.ref.Reference.reachabilityFence(target);
        }
        assertEquals(0, metrics.snapshot().active(dev.semaweave.compilation.EngineMetrics.Owner.RESOURCE_LEASES));
    }

    private static java.lang.ref.WeakReference<AnimatableEntity<?>> abandonSynchronousOwner(ModelRenderTarget target)
        throws Exception {
        var id = hash(113);
        var models = new FakeModelAccess();
        models.contents.put(id, content(id));
        models.leases.add(FakeLease.ready(id, target));
        var cyclicSubject = (GcSubject)UnsafeUtil.getUnsafe().allocateInstance(GcSubject.class);
        var owner = new TestHumanoid(cyclicSubject, new EntityModelBinding(models));
        owner.updateModelAndTexture(id, "main");
        DemandFixtures.advance(owner);
        // Root -> component -> subject -> owner must remain collectible as a whole.
        cyclicSubject.owner = owner;
        assertTrue(owner.hasInstalledModel());
        assertTrue(owner.getExpressionRuntime().module().isPresent());
        return new java.lang.ref.WeakReference<>(owner);
    }

    @Test
    void productionOwnerRetirementRevokesAndDetachesTheCurrentHolder() throws Exception {
        var id = hash(109);
        var models = new FakeModelAccess();
        models.contents.put(id, content(id));
        var lease = FakeLease.ready(id, playerTarget(id, "main"));
        models.leases.add(lease);
        var owner = new TestHumanoid(new EntityModelBinding(models));
        owner.updateModelAndTexture(id, "main");
        DemandFixtures.advance(owner);
        var component = owner.exactComponent(PositionComponent.class);
        assertTrue(component.isPresent());
        owner.retire();
        owner.retire();
        assertEquals(1, lease.closes.get());
        assertEquals(1, owner.modelEnds);
        assertFalse(owner.isAssigned());
        assertFalse(owner.hasInstalledModel());
        assertFalse(owner.hasUnfinishedWork());
        assertTrue(owner.exactComponent(PositionComponent.class).isEmpty());
        assertNull(owner.update(0));
        DemandFixtures.advance(owner);
        assertEquals(1, models.acquisitions.get());
        var replacement = FakeLease.ready(id, playerTarget(id, "main"));
        models.leases.add(replacement);
        assertThrows(IllegalStateException.class, () -> owner.updateModelAndTexture(id, "main"));
        var nextOwner = new TestHumanoid(new EntityModelBinding(models));
        nextOwner.updateModelAndTexture(id, "main");
        DemandFixtures.advance(nextOwner);
        assertTrue(nextOwner.isAssigned());
        assertTrue(nextOwner.hasInstalledModel());
        nextOwner.reset();
        assertTrue(nextOwner.isAssigned());
        assertEquals(1, replacement.closes.get());
        nextOwner.retire();
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
        binding.failExpression(severeFailure(), null, EntityModelBindingTest::holder);
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
        binding.failExpression(severeFailure(), "player", EntityModelBindingTest::holder);
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
            () -> binding.failExpression(failure, "player", EntityModelBindingTest::holder)));
        assertEquals(0, fallback.closes.get());
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
        binding.updateModelHash(models.defaultId);
        DemandFixtures.drive(binding, "player", "", "player", EntityModelBindingTest::holder);
        var failure = severeFailure();
        assertSame(failure, assertThrows(ExecutionFailure.class,
            () -> binding.failExpression(failure, "player", EntityModelBindingTest::holder)));
        var ordinary =
            new ExecutionFailure(ExecutionFailure.Reason.LOOP_BUDGET, failure.provenance(), "ordinary budget failure");
        assertSame(ordinary, assertThrows(ExecutionFailure.class,
            () -> binding.failExpression(ordinary, "player", EntityModelBindingTest::holder)));
        assertEquals(1, models.acquisitions.get());
        assertEquals(0, primary.closes.get());
        DemandFixtures.close(binding);
        assertEquals(1, primary.closes.get());
    }

    private static ExecutionFailure severeFailure() {
        return new ExecutionFailure(ExecutionFailure.Reason.NON_NULL_RETURN,
            new InvocationProvenance("player", "provider", 0, 1, 1), "non-null return");
    }

    @Test
    void generatedReturnViolationReachesTheRealOwnerAfterTransactionDiscard(@TempDir Path temporary) throws Exception {
        var output = Files.createDirectory(temporary.resolve("provider"));
        String source = """
            package independent;
            import com.elfmcys.ysm.api.annotation.*;
            import com.elfmcys.ysm.api.molang.*;
            import net.minecraft.world.entity.Entity;
            @YsmEntityComponent("broken") public final class Broken {
                public record Point(float x) {}
                public static int mode, after;
                public static final OutOfMemoryError MEMORY = new OutOfMemoryError("injected provider");
                @MolangFunction(effect=MolangEffect.ACTION)
                public static Point value(MolangContextView<Entity> c) {
                    c.submit((PreparedEffect)() -> {});
                    if(mode==1) throw new NullPointerException("ordinary-provider");
                    if(mode==3) throw MEMORY;
                    return mode==2 ? new Point(1) : null;
                }
                @MolangFunction(effect=MolangEffect.ACTION)
                public static it.unimi.dsi.fastutil.floats.FloatList array(MolangContextView<Entity> c) {
                    c.submit((PreparedEffect)() -> {}); return null;
                }
                @MolangNullable @MolangFunction(effect=MolangEffect.CONTEXT)
                public static Point nullable(MolangContextView<Entity> c) { return null; }
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static Float boxed(MolangContextView<Entity> c) { return null; }
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static String text(MolangContextView<Entity> c) { return null; }
                @MolangFunction(effect=MolangEffect.ACTION)
                public static void after(MolangContextView<Entity> c) { after++; }
            }
            """;
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var unit =
                new SimpleJavaFileObject(URI.create("string:///independent/Broken.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignored) {
                        return source;
                    }
                };
            var task = compiler.getTask(null, manager, diagnostics, List.of("--release", "17", "-classpath",
                System.getProperty("java.class.path"), "-d", output.toString()), null, List.of(unit));
            task.setProcessors(List.of(new com.elfmcys.ysm.api.internal.processor.UnifiedDeclarationProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        // JNI registrations and provider carriers must belong to the same visibility root.
        var log = temporary.resolve("owner-probe.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8", "-cp", output + File.pathSeparator + System.getProperty("java.class.path"),
            GeneratedOwnerFailureProbe.class.getName(), output.toString(), temporary.resolve("cache").toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS), "Owner probe timed out");
            assertEquals(0, process.exitValue(),
                new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class GeneratedOwnerFailureProbe {
        public static void main(String[] args) throws Exception {
            com.elfmcys.ysm.util.NativeLibUtil.load();
            verifyGeneratedOwnerFailures(Path.of(args[0]), Path.of(args[1]));
        }
    }

    public static void verifyGeneratedOwnerFailures(Path provider, Path cache) throws Exception {
        initializeMinecraftEntityClasses();
        var loader = EntityModelBindingTest.class.getClassLoader();
        var collector = new MolangExtensions.Collector();
        collector.collectBuiltins();
        collector.collect(new MolangExtensions.Source(provider.toString(), Set.of("broken"),
            List.of(new MolangExtensions.DeclarationRef("independent.Broken", "broken", "")), loader, provider));
        var admitted = collector.freeze(HostAvailability.none(), new MolangExtensions.Qualification() {
            public boolean dependency(String mod, String version) {
                return true;
            }

            public boolean checker(MolangExtensions.Source origin,
                com.elfmcys.ysm.api.molang.DeclarationSchema schema) {
                return true;
            }
        });
        assertTrue(admitted.rejectedMods().isEmpty(), admitted.diagnostics().toString());
        assertTrue(admitted.host().registry().functions().containsKey(new dev.semaweave.SymbolKey("ysm:sync")),
            admitted.diagnostics().toString());
        var host = admitted.host();
        var providerClass = loader.loadClass("independent.Broken");
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(cache, track(new AtomicSharedCache(cache)))), host,
            new ExpressionEngineMetrics());
        try (var value = faultModule(factory, 70, "broken.value(); broken.after();", false);
            var array = faultModule(factory, 71, "broken.array(); broken.after();", false);
            var valueScalar = faultModule(factory, 72, "broken.value()+1; broken.after();", false);
            var arrayScalar = faultModule(factory, 73, "broken.array()+1; broken.after();", false);
            var valueCompare = faultModule(factory, 74, "broken.value()==2; broken.after();", false);
            var arrayCompare = faultModule(factory, 75, "2==broken.array(); broken.after();", false)) {
            var failedModules = List.of(value, array, valueScalar, arrayScalar, valueCompare, arrayCompare);
            for (var failed : failedModules) {
                providerClass.getField("mode").setInt(null, 0);
                providerClass.getField("after").setInt(null, 0);
                var models = new FakeModelAccess();
                models.host = host;
                var primaryId = hash(70 + failedModules.indexOf(failed));
                models.contents.put(primaryId, content(primaryId));
                var primary = FakeLease.ready(primaryId, playerTarget(primaryId, "main", failed));
                var defaultLease = FakeLease.ready(models.defaultId,
                    playerTarget(models.defaultId, "main", faultModule(factory, 100, "0", true)));
                models.leases.add(primary);
                models.leases.add(defaultLease);
                var owner = new TestHumanoid(new EntityModelBinding(models));
                owner.updateModelAndTexture(primaryId, "main");
                DemandFixtures.advance(owner);
                var runtime = owner.getExpressionRuntime();
                var prevalidated = new AtomicInteger();
                var committed = new AtomicInteger();
                var inputs = faultInputs(prevalidated, committed);
                ((FakeLease)((AnimatableEntity.ResourceHolder)owner.installationIdentity()).lease()).onClose =
                    () -> assertRuntimeIdle(runtime);
                var previousGeneration = runtime.generation();
                runOwnerEvent(owner, inputs);
                assertEquals(1, prevalidated.get());
                assertEquals(0, committed.get());
                assertEquals(0, providerClass.getField("after").getInt(null));
                assertEquals(1, primary.closes.get());
                DemandFixtures.advance(owner);
                assertSame(((AcquireResult.Ready)defaultLease.result).target(), owner.getModelRenderTarget());
                assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, owner.getInstallationRelation());
                assertEquals(primaryId, owner.getModelHash());
                assertTrue(runtime.generation() > previousGeneration);
                owner.updateModelAndTexture(primaryId, "main");
                DemandFixtures.advance(owner);
                assertEquals(2, models.acquisitions.get());
                runOwnerEvent(owner, inputs);
                assertEquals(0, providerClass.getField("after").getInt(null));
                assertEquals(0, defaultLease.closes.get());
                assertEquals(2, models.acquisitions.get());
                owner.retire();
                assertEquals(1, defaultLease.closes.get());
            }
            // Neither legal missing nor an ordinary NPE may revoke the shared declaration or owner.
            for (int mode : new int[] {0, 1, 2, 3}) {
                providerClass.getField("mode").setInt(null, mode);
                providerClass.getField("after").setInt(null, 0);
                var modelId = hash(80 + mode);
                // Each render target owns its module; a previous target's Cleaner may run at any time.
                var model = faultModule(factory, 80 + mode,
                    mode == 0 ? "broken.nullable(); broken.boxed(); broken.text(); broken.after();"
                        : "broken.value(); broken.after();",
                    false);
                var models = new FakeModelAccess();
                models.host = host;
                models.contents.put(modelId, content(modelId));
                var primary = FakeLease.ready(modelId, playerTarget(modelId, "main", model));
                models.leases.add(primary);
                var owner = new TestHumanoid(new EntityModelBinding(models));
                owner.updateModelAndTexture(modelId, "main");
                DemandFixtures.advance(owner);
                var prevalidated = new AtomicInteger();
                var committed = new AtomicInteger();
                runOwnerEvent(owner, faultInputs(prevalidated, committed));
                assertSame(((AcquireResult.Ready)primary.result).target(), owner.getModelRenderTarget());
                assertEquals(0, primary.closes.get());
                assertEquals(mode == 1 || mode == 3 ? 0 : 1, providerClass.getField("after").getInt(null));
                assertEquals(mode == 2 ? 1 : 0, committed.get());
                if (mode == 3) {
                    assertRuntimeIdle(owner.getExpressionRuntime());
                    providerClass.getField("mode").setInt(null, 2);
                    runOwnerEvent(owner, faultInputs(prevalidated, committed));
                    assertEquals(1, providerClass.getField("after").getInt(null));
                    assertEquals(1, committed.get());
                    assertEquals(0, primary.closes.get());
                }
                owner.retire();
                assertEquals(1, primary.closes.get());
            }
        }
    }

    private static RenderTargetExpressionModule faultModule(RenderTargetExpressionModuleFactory factory, int marker,
        String source, boolean defaultModel) throws Exception {
        var body = EncodedProgramMapper.toProto(new MolangFrontend()
            .compile(source, MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("fault/@sync"))
            .program());
        return factory.create(() -> false, new ModelFileIdentity(hash(marker), hash(121)), "player", "main", PROFILE,
            defaultModel, ModelDataProto.newBuilder().build(),
            StringDataProto.newBuilder()
                .addUserFunctions(UserFunctionProto.newBuilder().setName("@sync").setBody(body).build()).build(),
            SettingsProto.newBuilder().build(), new byte[0]);
    }

    private static EntityExpressionRuntime.InvocationInputs faultInputs(AtomicInteger prevalidated,
        AtomicInteger committed) {
        return new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<Entity>() {
            public Entity entity() {
                return subject;
            }

            public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {
                prevalidated.incrementAndGet();
            }
        }, (commands, provenance) -> () -> committed.addAndGet(commands.size()), () -> false);
    }

    private static void runOwnerEvent(TestHumanoid owner, EntityExpressionRuntime.InvocationInputs inputs)
        throws ReflectiveOperationException {
        var field = AnimatableEntity.class.getDeclaredField("lifecycle");
        field.setAccessible(true);
        var lifecycle = (EntityExecutionLifecycle)field.get(owner);
        lifecycle.submit(() -> owner.getExpressionRuntime().action(inputs, evaluation -> {
            evaluation.dispatchSync(EventArguments.sync());
            return null;
        }));
        owner.waitForAsyncUpdate();
    }

    private static void assertRuntimeIdle(EntityExpressionRuntime runtime) {
        try {
            var active = EntityExpressionRuntime.class.getDeclaredField("evaluating");
            active.setAccessible(true);
            assertFalse(active.getBoolean(runtime));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static FakeModelAccess missingThenReady(Hash256 firstId, Hash256 secondId) throws Exception {
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
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

    private static ModelRenderTarget playerTarget(Hash256 modelId, String textureName)
        throws ReflectiveOperationException {
        return playerTarget(modelId, textureName, TestExpressionModules.create());
    }

    private static ModelRenderTarget playerTarget(Hash256 modelId, String textureName,
        RenderTargetExpressionModule expressions) throws ReflectiveOperationException {
        var model = (GeoModel)UnsafeUtil.getUnsafe().allocateInstance(GeoModel.class);
        var sorted = GeoModel.class.getDeclaredField("sortedBones");
        sorted.setAccessible(true);
        sorted.set(model, it.unimi.dsi.fastutil.objects.ReferenceLists.emptyList());
        var locatorType = com.elfmcys.ysm.client.model.locator.PlayerLocator.get();
        var groups = new it.unimi.dsi.fastutil.objects.ReferenceArrayList<
            it.unimi.dsi.fastutil.objects.ReferenceArrayList<com.elfmcys.ysm.geckolib3.geo.render.built.GeoBone>>();
        for (int i = 0; i < locatorType.size(); i++)
            groups.add(new it.unimi.dsi.fastutil.objects.ReferenceArrayList<>());
        var locatorMap = GeoModel.class.getDeclaredField("locatorMap");
        locatorMap.setAccessible(true);
        locatorMap.set(model, groups);
        var variants = new FifoHashMap<>(new String[] {textureName},
            new PlayerModelVariant[] {new PlayerModelVariant(model, model)});
        var handlers = new Object2ReferenceOpenHashMap<String, List<com.elfmcys.ysm.expression.aot.EventProgram>>();
        var location =
            new ModelProgramInventory.SemanticLocation("player", "function/@sync", ProgramRole.EVENT_SYNC, "sync:0");
        try {
            handlers.put("sync", List.of(expressions.requireEventProgram(location)));
        } catch (IllegalArgumentException missing) {
        }
        var assets = new CommonAsset(Map.of(), TargetSoundResources.empty(new ModelFileIdentity(modelId, hash(120))),
            new Object2ReferenceOpenHashMap<>(), handlers);
        var resources =
            new PlayerModelResources(variants, new AnimationStore(), new AnimationStore(), new ConditionManager(),
                new FPArmConditionManager(), new Object2ReferenceOpenHashMap<>(), textureName, assets);
        var info = (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class);
        return new ModelRenderTarget(modelId, "player", resources, assets, expressions, info);
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static ModelContent content(Hash256 modelId) {
        return new ModelContent() {
            @Override
            public Hash256 modelId() {
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
        private final HashMap<Hash256, ModelContent> contents = new HashMap<>();
        private final ArrayDeque<ResourceLease> leases = new ArrayDeque<>();
        private final ArrayDeque<ResourceLease> ready = new ArrayDeque<>();
        private final ArrayDeque<CompletableFuture<Optional<ResourceLease>>> offlineResults = new ArrayDeque<>();
        private final ArrayList<ResourceRequest> requests = new ArrayList<>();
        private final ArrayList<WorkPriority> priorities = new ArrayList<>();
        private final AtomicInteger acquisitions = new AtomicInteger();
        private final AtomicInteger onlineAcquisitions = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private Hash256 defaultId = hash(100);

        @Override
        public ModelContent content(Hash256 modelId) {
            return contents.get(modelId);
        }

        @Override
        public ResourceRequest resourceRequest(Hash256 modelId, String targetId, String textureName) {
            var request = new ResourceRequest(modelId, targetId, textureName, PROFILE, host.expressionBinding());
            requests.add(request);
            return request;
        }

        @Override
        public ResourceRequest defaultResourceRequest(String targetId) {
            var request = new ResourceRequest(defaultId, targetId, "", PROFILE, host.expressionBinding());
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
        public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request,
            WorkPriority priority) {
            priorities.add(priority);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
            acquisitions.incrementAndGet();
            onlineAcquisitions.incrementAndGet();
            return track(request, leases.removeFirst());
        }

        @Override
        public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request,
            WorkPriority priority) {
            acquisitions.incrementAndGet();
            if (!offlineResults.isEmpty()) {
                return offlineResults.removeFirst();
            }
            return CompletableFuture.completedFuture(Optional.of(track(request, leases.removeFirst())));
        }

        @Override
        public void reportActiveModelUse(ResourceRequest request) {}

        @Override
        public void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable) {
            failures.incrementAndGet();
        }
    }

    private static final class GcSubject extends ArmorStand {
        private AnimatableEntity<?> owner;

        private GcSubject() {
            super(net.minecraft.world.entity.EntityType.ARMOR_STAND, null);
        }
    }

    private static final class TestHumanoid extends CustomHumanoidEntity<LivingEntity> {
        private ModelResourceDemand.Mode mode = ModelResourceDemand.Mode.ONLINE;

        @Override
        protected ModelResourceDemand.Mode resourceDemandMode() {
            return mode;
        }

        @Override
        protected String fallbackRenderTargetId() {
            return mode == ModelResourceDemand.Mode.CACHED ? null : super.fallbackRenderTargetId();
        }

        private TestHumanoid(EntityModelBinding binding) {
            this(subject, binding);
        }

        private int modelEnds;

        @Override
        protected void onModelEnded() {
            super.onModelEnded();
            modelEnds++;
        }

        private TestHumanoid(LivingEntity entity, EntityModelBinding binding) {
            super(entity, false, binding);
        }

        private String requestedTexture() {
            return requestedTextureName();
        }

        @Override
        protected HumanoidStateTracker<LivingEntity> createStateTracker(LivingEntity entity) {
            return new HumanoidStateTracker<>(entity);
        }

        @Override
        public int getFrameRateLimit() {
            return 60;
        }

        @Override
        protected void prepareAnimationControllers(ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {}
    }

    private static final class FakeLease implements ResourceLease {
        private Runnable onClose = () -> {
        };
        private final Hash256 modelId;
        private final String textureName;
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger pendingCancellations = new AtomicInteger();
        private AcquireResult result;
        private ModelResourcePurpose purpose = ModelResourcePurpose.FULL;
        private boolean closed;

        private FakeLease(Hash256 modelId, AcquireResult result) {
            this(modelId, null, result);
        }

        private FakeLease(Hash256 modelId, String textureName, AcquireResult result) {
            this.modelId = modelId;
            this.textureName = textureName;
            this.result = result;
        }

        private static FakeLease pending(Hash256 modelId) {
            return new FakeLease(modelId, new AcquireResult.Pending());
        }

        private static FakeLease ready(Hash256 modelId, ModelRenderTarget target) {
            return new FakeLease(modelId, new AcquireResult.Ready(target));
        }

        private static FakeLease ready(Hash256 modelId, String textureName, ModelRenderTarget target) {
            return new FakeLease(modelId, textureName, new AcquireResult.Ready(target));
        }

        private static FakeLease failed(Hash256 modelId, ResourceFailure.Kind kind) {
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
            return modelId.equals(request.modelId()) && purpose == request.purpose()
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
