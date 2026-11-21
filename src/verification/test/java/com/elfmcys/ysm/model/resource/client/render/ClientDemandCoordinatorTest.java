package com.elfmcys.ysm.model.resource.client.render;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.named;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.fromMap;

import com.elfmcys.ysm.client.entity.EntityModelDemand;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.*;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.DatabaseWork;
import com.elfmcys.ysm.model.storage.PlayerPreferences;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual cache and independent leases with a zero unused target allowance. */
class ClientDemandCoordinatorTest {
    private static final BakeProfile PROFILE = new BakeProfile("coordinator");
    private static final ClientDemandCoordinator.Target A = target(1), B = target(2), C = target(3);

    @BeforeAll
    static void renderOwner() {
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
    }

    static Stream<Arguments> pageAdmissionCases() {
        return Stream.of(-1L, 0L, 300L, 301L, 699L, 700L, 701L)
            .flatMap(previous -> Stream.of(0L, 299L, 300L, 699L, 700L).map(current -> Arguments.of(previous, current)));
    }

    @ParameterizedTest
    @MethodSource("pageAdmissionCases")
    void pagePublishesTheCompleteIndependentAdmissionMatrix(long previous, long current) throws Exception {
        try (var f = new Fixture()) {
            var first = f.coordinator.openPage("first");
            var start = Math.max(0, previous);
            f.clock.set(start);
            var page = previous < 0 ? first : f.coordinator.replacePage(first, "current");
            var modes = new ArrayList<ModelResourceDemand.Mode>();
            page.action(new ClientDemandCoordinator.PageAction() {
                public void advance(ModelResourceDemand.Mode allowed) {
                    modes.add(allowed);
                }

                public void close() {}
            });
            f.clock.set(start + current);
            page.advance();
            var expected = previous < 0 || previous > 700 || current >= 700 ? ModelResourceDemand.Mode.ONLINE
                : current >= 300 ? ModelResourceDemand.Mode.OFFLINE : ModelResourceDemand.Mode.READY;
            assertEquals(List.of(expected), modes);
            assertEquals(0, f.offlineCalls);
            assertEquals(0, f.onlineCalls, "a page time gate alone never authorizes network work");
        }
    }

    @Test
    void publishedEagerPolicyChangesAtTheNextAdvanceWithoutResettingPageContinuity() throws Exception {
        try (var f = new Fixture()) {
            var previous = f.coordinator.openPage("first");
            f.clock.set(100);
            var page = f.coordinator.replacePage(previous, List.of("pack", 1, "filter"));
            var modes = new ArrayList<ModelResourceDemand.Mode>();
            page.action(new ClientDemandCoordinator.PageAction() {
                public void advance(ModelResourceDemand.Mode allowed) {
                    modes.add(allowed);
                }

                public void close() {}
            });
            assertFalse(page.eagerLoading());
            f.eager = true;
            assertFalse(page.eagerLoading());
            f.clock.set(399);
            page.advance();
            assertTrue(page.eagerLoading());
            assertEquals(ModelResourceDemand.Mode.READY, modes.get(modes.size() - 1));
            f.eager = false;
            assertTrue(page.eagerLoading());
            assertSame(page, f.coordinator.replacePage(page, List.of("pack", 1, "filter")));
            f.clock.set(400);
            page.advance();
            assertFalse(page.eagerLoading());
            assertEquals(ModelResourceDemand.Mode.OFFLINE, modes.get(modes.size() - 1));
            f.clock.set(800);
            page.advance();
            assertEquals(ModelResourceDemand.Mode.ONLINE, modes.get(modes.size() - 1));
        }
    }

    @Test
    void frameInstallationAcquiresIndependentlyAndNeverInstallsFromTick() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            f.local = true;
            var scope = f.coordinator.register(receiver);
            scope.useFrameInstallation();
            receiver.mode = ModelResourceDemand.Mode.OFFLINE;
            f.tick();
            f.ready(A);
            f.tick();
            assertNull(receiver.lease);
            assertTrue(scope.hasReadyInstallation());
            assertEquals(1, f.interests(A));
            var eventStart = f.events.size();
            var claim = Objects.requireNonNull(scope.claimReadyInstallation());
            assertEquals(List.of("0:acquire:1"), f.events.subList(eventStart, f.events.size()));
            assertEquals(2, f.interests(A));
            var lease = claim.takeLease();
            assertThrows(IllegalStateException.class, claim::takeLease);
            var outcome = receiver.install(claim.request(), lease, claim.fallback(), false);
            assertEquals(ClientDemandCoordinator.InstallationResult.INSTALLED, outcome);
            assertFalse(scope.hasReadyInstallation(), "the actual installation ends matching candidate demand");
            claim.close();
            assertSame(lease, receiver.lease);
            scope.close();
            assertEquals(1, f.interests(A), "the binding owns the transferred lease after scope retirement");
            receiver.close();
            assertEquals(0, f.interests(A));
        }
    }

    @Test
    void readyPublishedBetweenTicksCanEnterTheNextFrameWithoutWaitingForDemandTick() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            f.local = true;
            receiver.mode = ModelResourceDemand.Mode.OFFLINE;
            var scope = f.coordinator.register(receiver);
            scope.useFrameInstallation();
            f.tick();
            assertFalse(scope.hasReadyInstallation());
            f.complete(A);
            f.cache.tick();
            var before = f.events.size();
            assertTrue(scope.hasReadyInstallation());
            assertEquals(List.of("0:acquire:1", "0:release:1"), f.events.subList(before, f.events.size()));
            assertNull(receiver.lease, "Ready discovery cannot install before frame admission");
            assertEquals(1, f.offlineCalls);
            assertEquals(0, f.onlineCalls);
            assertEquals(1, f.starts.size());
            try (var claim = Objects.requireNonNull(scope.claimReadyInstallation())) {
                assertNull(receiver.lease);
                assertEquals(2, f.interests(A));
            }
            assertEquals(1, f.interests(A));
        }
    }

    @Test
    void firstRenderCanDiscoverReadyDuringThePageWaitAndKeepsItsAnchorAcrossTheFirstTick() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var request = f.resourceRequest(A.modelId(), "player", "main", ModelResourcePurpose.GUI, null);
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.complete(A);
                f.cache.tick();
                var previous = f.coordinator.openPage("previous");
                f.clock.set(100);
                var page = f.coordinator.replacePage(previous, "new");
                receiver.mode = ModelResourceDemand.Mode.READY;
                var scope = f.coordinator.register(receiver);
                scope.useFrameInstallation();
                page.entity(scope);
                page.advance();
                assertTrue(scope.hasReadyInstallation());
                assertNull(receiver.lease, "a Ready hit still requires the frame's new-card admission");
                assertEquals(2, f.interests(A));
                var before = List.copyOf(f.events);
                f.tick();
                assertEquals(before, f.events, "the first tick must preserve the first-render anchor");
                assertEquals(0, f.offlineCalls);
                assertEquals(0, f.onlineCalls);
                try (var claim = Objects.requireNonNull(scope.claimReadyInstallation())) {
                    assertNull(receiver.lease);
                }
                assertEquals(2, f.interests(A));
                page.close();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"CACHED", "OFFLINE"})
    void readyOnlyMissDoesNotCancelRestartOrDowngradeAnExistingAcquisition(ModelResourceDemand.Mode mode)
        throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var probe = new Probe();
            if (mode == ModelResourceDemand.Mode.CACHED)
                f.cachedProbe = probe;
            else
                f.offlineProbe = probe;
            receiver.mode = mode;
            var scope = f.coordinator.register(receiver);
            scope.useFrameInstallation();
            f.tick();
            for (int frame = 0; frame < 3; frame++) {
                assertFalse(scope.hasReadyInstallation());
                assertNull(scope.claimReadyInstallation());
                assertFalse(probe.closed);
            }
            f.tick();
            assertFalse(probe.closed);
            assertEquals(mode == ModelResourceDemand.Mode.CACHED ? 1 : 0, f.cachedCalls);
            assertEquals(mode == ModelResourceDemand.Mode.OFFLINE ? 1 : 0, f.offlineCalls);
            assertEquals(0, f.onlineCalls);
            assertEquals(0, f.starts.size());
            var request = receiver.assignment().request();
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.complete(A);
                f.cache.tick();
                assertTrue(scope.hasReadyInstallation());
                assertTrue(probe.closed, "only an acquired Ready replacement can retire the old probe");
                assertEquals(2, f.interests(A));
                assertNull(receiver.lease);
            }
        }
    }

    @Test
    void readyOnlyLookupRechecksNewUsePermissionBeforeTakingAResidentCapability() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var request = receiver.assignment().request();
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.complete(A);
                f.cache.tick();
                var scope = f.coordinator.register(receiver);
                scope.useFrameInstallation();
                f.denied.add(A.modelId());
                var before = List.copyOf(f.events);
                assertFalse(scope.hasReadyInstallation());
                assertNull(scope.claimReadyInstallation());
                assertEquals(before, f.events);
                f.denied.remove(A.modelId());
                assertTrue(scope.hasReadyInstallation());
                f.denied.add(A.modelId());
                assertNull(scope.claimReadyInstallation());
                assertEquals(2, f.interests(A), "the earlier anchor retains its accepted ownership");
                assertEquals(0, f.offlineCalls);
                assertEquals(0, f.onlineCalls);
            }
        }
    }

    @Test
    void unsubmittedAndBusyFrameClaimsCloseOnlyTheirOwnCapabilityAndCanRetry() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var request = f.resourceRequest(A.modelId(), "player", "main", ModelResourcePurpose.GUI, null);
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.ready(A);
                var scope = f.coordinator.register(receiver);
                scope.useFrameInstallation();
                receiver.mode = ModelResourceDemand.Mode.READY;
                f.tick();
                var rejectedSubmission = Objects.requireNonNull(scope.claimReadyInstallation());
                rejectedSubmission.close();
                assertEquals(2, f.interests(A));
                f.tick();
                var busy = Objects.requireNonNull(scope.claimReadyInstallation());
                receiver.busy = true;
                var lease = busy.takeLease();
                var outcome = receiver.install(busy.request(), lease, false, false);
                assertEquals(ClientDemandCoordinator.InstallationResult.BUSY, outcome);
                lease.close();
                busy.close();
                f.tick();
                assertTrue(scope.hasReadyInstallation());
                var finalClaim = Objects.requireNonNull(scope.claimReadyInstallation());
                var taskLease = finalClaim.takeLease();
                scope.close();
                finalClaim.close();
                taskLease.close();
                f.tick();
                assertNull(scope.claimReadyInstallation());
                assertEquals(1, f.interests(A));
                assertEquals(1, f.starts.size(), "claim/retry never starts a new load");
            }
        }
    }

    @Test
    void frameUseDefersPageRetirementAndStillClosesEverySiblingOnFailure() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var page = f.coordinator.openPage("old");
            var scope = f.coordinator.register(receiver);
            page.entity(scope);
            var calls = new ArrayList<String>();
            var primary = new IllegalStateException("first cleanup");
            var secondary = new IllegalArgumentException("second cleanup");
            page.action(new ClientDemandCoordinator.PageAction() {
                public void advance(ModelResourceDemand.Mode allowed) {}

                public void close() {
                    calls.add("first");
                    throw primary;
                }
            });
            page.action(new ClientDemandCoordinator.PageAction() {
                public void advance(ModelResourceDemand.Mode allowed) {}

                public void close() {
                    calls.add("second");
                    throw secondary;
                }
            });
            var frame = page.beginFrame();
            assertThrows(IllegalStateException.class, page::beginFrame);
            var replacement = f.coordinator.replacePage(page, "new");
            assertTrue(page.isClosed());
            assertFalse(scope.isClosed());
            assertTrue(calls.isEmpty());
            assertThrows(IllegalStateException.class, page::beginFrame);
            assertSame(primary, assertThrows(IllegalStateException.class, frame::close));
            assertEquals(List.of("first", "second"), calls);
            assertArrayEquals(new Throwable[] {secondary}, primary.getSuppressed());
            assertTrue(scope.isClosed());
            frame.close();
            assertFalse(replacement.isClosed());
        }
    }

    @Test
    void closingAnActivePageSealsHoverAndEntityAcquisitionBeforeDeferredCleanup() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var page = f.coordinator.openPage("list");
            var scope = f.coordinator.register(receiver);
            scope.useFrameInstallation();
            receiver.mode = ModelResourceDemand.Mode.OFFLINE;
            page.entity(scope);
            page.pointer(A);
            f.clock.set(300);
            page.advance();
            var frame = page.beginFrame();
            page.close();
            assertFalse(scope.isClosed());
            f.tick();
            assertEquals(0, f.offlineCalls);
            assertEquals(0, f.onlineCalls);
            assertNull(scope.claimReadyInstallation());
            frame.close();
            assertTrue(scope.isClosed());
        }
    }

    @Test
    void transferredFrameCandidateHasNoCompletionProtocolAndRetainsOnlyItsOwnUse() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var request = f.resourceRequest(A.modelId(), "player", "main", ModelResourcePurpose.GUI, null);
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.ready(A);
                var scope = f.coordinator.register(receiver);
                scope.useFrameInstallation();
                f.tick();
                var claim = Objects.requireNonNull(scope.claimReadyInstallation());
                var taskLease = claim.takeLease();
                claim.close();
                assertTrue(taskLease.poll() instanceof AcquireResult.Ready);
                scope.close();
                assertTrue(taskLease.poll() instanceof AcquireResult.Ready);
                taskLease.close();
                claim.close();
                assertEquals(1, f.interests(A));
            }
        }
    }

    @Test
    void selectionKeepsAnIndependentGuiFlightAndPageCloseCannotCancelFull() throws Exception {
        try (var f = new Fixture()) {
            f.local = true;
            var page = f.coordinator.openPage("list");
            f.hover(page, A, false);
            var producer = f.pending.get(A.modelId());
            assertEquals(WorkPriority.BACKGROUND, f.priorities.get(A.modelId()));
            f.select(A, false);
            assertEquals(WorkPriority.LOCAL_PLAYER, f.priorities.get(A.modelId()));
            var full = f.pending.get(A.modelId());
            assertNotSame(producer, full);
            assertFalse(producer.isCancelled());
            assertEquals(2, f.starts.size());
            assertEquals(2, f.interests(A));
            page.close();
            f.cache.tick();
            assertTrue(producer.isCancelled());
            assertFalse(full.isCancelled());
            assertEquals(1, f.interests(A));
        }
    }

    @Test
    void readyPublishedByAnotherConsumerDoesNotActivateAStaticCard() throws Exception {
        try (var f = new Fixture(); var player = new Receiver(f, A, ModelResourcePurpose.FULL);
            var card = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            f.coordinator.register(player);
            f.tick();
            f.ready(A);
            var page = f.coordinator.openPage("list");
            page.card(card, A);
            f.at(1000);
            assertNull(card.scope);
            assertNull(card.lease);
            assertEquals(1, f.starts.size());
            page.close();
            assertInstanceOf(AcquireResult.Ready.class, player.lease.poll());
        }
    }

    @Test
    void pointerExitRetiresTheReceiverImmediatelyAndReentryNeedsThreeHundredMillis() throws Exception {
        try (var f = new Fixture(); var card = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var page = f.coordinator.openPage("list");
            page.card(card, A);
            page.pointer(A);
            f.at(299);
            assertNull(card.scope);
            f.at(300);
            f.ready(A);
            assertNotNull(card.lease);
            assertEquals(ModelResourcePurpose.GUI, card.lease.purpose());
            var original = card.scope;
            page.pointer(null);
            assertTrue(original.isClosed());
            assertNull(card.lease);
            assertEquals(1, f.interests(A), "only the independently admitted preparation remains");
            page.pointer(A);
            f.at(599);
            assertNull(card.lease);
            f.at(600);
            assertNotSame(original, card.scope);
            assertNotNull(card.lease);
            page.close();
            assertNull(card.lease);
            assertEquals(0, f.interests(A));
        }
    }

    @Test
    void latePreparationCannotReactivateAnExitedCard() throws Exception {
        try (var f = new Fixture(); var card = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var page = f.coordinator.openPage("list");
            page.card(card, A);
            f.hover(page, A, false);
            page.pointer(null);
            f.ready(A);
            assertNull(card.lease);
            assertTrue(card.scope.isClosed());
            assertEquals(1, f.interests(A));
            page.close();
            assertEquals(0, f.interests(A));
        }
    }

    @Test
    void eagerUsesOfflineGuiAndConfigurationChangesPreserveThePointerStart() throws Exception {
        try (var f = new Fixture(); var a = new Receiver(f, A, ModelResourcePurpose.GUI);
            var b = new Receiver(f, B, ModelResourcePurpose.GUI)) {
            f.eager = f.local = true;
            var page = f.coordinator.openPage("list");
            page.card(a, A);
            page.card(b, B);
            page.pointer(A);
            f.tick();
            f.ready(A);
            f.ready(B);
            assertNotNull(a.lease);
            assertNotNull(b.lease);
            assertEquals(0, f.onlineCalls);
            assertTrue(f.requests.stream().allMatch(request -> request.purpose() == ModelResourcePurpose.GUI));
            assertEquals(WorkPriority.BACKGROUND, f.priorities.get(A.modelId()));
            assertEquals(WorkPriority.BACKGROUND, f.priorities.get(B.modelId()));
            f.eager = false;
            f.at(299);
            assertNull(a.lease);
            assertNull(b.lease);
            assertSame(page, f.coordinator.replacePage(page, "list"));
            page.pointer(A);
            f.at(300);
            f.ready(A);
            assertNotNull(a.lease);
            assertNull(b.lease);
            f.eager = true;
            f.at(301);
            assertFalse(b.scope.isClosed());
            assertEquals(1, f.onlineCalls, "Online admission delegates the local probe to the resource owner");
        }
    }

    @Test
    void absentImageKeepsLoadingThroughEagerPreparationAndBusyInstallation() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            f.eager = f.local = true;
            var page = f.coordinator.openPage("list");
            page.card(receiver, A);
            f.tick();
            var state = cardState(page, receiver, true);
            assertTrue(cardLoading(state), "an image miss must not end the live model's loading feedback");
            receiver.busy = true;
            f.ready(A);
            assertNull(receiver.lease);
            assertTrue(cardLoading(state), "a Ready lease still waits for the receiving host");
            receiver.busy = false;
            f.tick();
            assertNotNull(receiver.lease);
            assertFalse(cardLoading(state));
            page.close();
            assertFalse(cardLoading(state));
        }
    }

    @Test
    void absentImageTracksSharedHoverPreparationWithoutGivingAStaticCardLoadingState() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var page = f.coordinator.openPage("list");
            page.card(receiver, A);
            f.hover(page, A, false);
            assertFalse(receiver.scope.hasUnfinishedWork(), "the hover owner holds the preparation");
            var state = cardState(page, receiver, true);
            assertTrue(cardLoading(state));
            field(state.getClass(), "active").setBoolean(state, false);
            assertFalse(cardLoading(state), "retired display eligibility must not inherit pending preparation");
            page.close();
            assertFalse(page.preparing(A));
        }
    }

    @Test
    void absentImageStopsLoadingOnAnOfflineMissOrPreparationFailure() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            f.eager = true;
            var page = f.coordinator.openPage("list");
            page.card(receiver, A);
            f.tick();
            assertFalse(cardLoading(cardState(page, receiver, true)));
            f.hover(page, A, false);
            assertTrue(page.preparing(A));
            f.pending.get(A.modelId()).completeExceptionally(new IllegalStateException("preparation failed"));
            f.tick();
            assertFalse(page.preparing(A));
            assertFalse(cardLoading(cardState(page, receiver, true)));
        }
    }

    private static Object cardState(ClientDemandCoordinator.PageScope page, Receiver receiver, boolean active)
        throws Exception {
        var type = Class.forName("com.elfmcys.ysm.client.gui.button.CatalogModelCardState");
        var state = UnsafeUtil.getUnsafe().allocateInstance(type);
        var imageType = com.elfmcys.ysm.model.resource.client.asset.PageImages.Image.class;
        var image = UnsafeUtil.getUnsafe().allocateInstance(imageType);
        field(imageType, "absent").setBoolean(image, true);
        assertFalse(((com.elfmcys.ysm.model.resource.client.asset.PageImages.Image)image).loading());
        field(type, "page").set(state, page);
        field(type, "target").set(state, receiver.target);
        field(type, "scope").set(state, receiver.scope);
        var imagesType = com.elfmcys.ysm.client.gui.button.CatalogCardImages.class;
        var images = UnsafeUtil.getUnsafe().allocateInstance(imagesType);
        field(imagesType, "preview").set(images, image);
        field(type, "images").set(state, images);
        field(type, "active").setBoolean(state, active);
        return state;
    }

    private static boolean cardLoading(Object state) throws Exception {
        var method = state.getClass().getDeclaredMethod("loading");
        method.setAccessible(true);
        return (boolean)method.invoke(state);
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void offlineMissCanAdvanceOnlineAtTheInteractionBoundaryWithoutFullResources() throws Exception {
        try (var f = new Fixture(); var card = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            f.eager = true;
            var page = f.coordinator.openPage("list");
            page.card(card, A);
            f.tick();
            assertTrue(f.offlineCalls > 0);
            assertEquals(0, f.onlineCalls);
            page.pointer(A);
            f.at(299);
            assertEquals(0, f.onlineCalls);
            f.at(300);
            assertEquals(1, f.onlineCalls);
            assertEquals(ModelResourcePurpose.GUI, f.requests.get(0).purpose());
            f.ready(A);
            assertFalse(card.full);
            assertFalse(page.feedback(A).waiting());
        }
    }

    @Test
    void selectionKeepsGuiAndFullIndependentAndFeedbackWaitsForTheRealPlayer() throws Exception {
        try (var f = new Fixture(); var card = new Receiver(f, A, ModelResourcePurpose.GUI);
            var player = new Receiver(f, A, ModelResourcePurpose.FULL)) {
            var page = f.coordinator.openPage("list");
            page.card(card, A);
            f.hover(page, A, true);
            assertEquals(WorkPriority.BACKGROUND, f.priorities.get(A.modelId()));
            page.pointer(null);
            f.coordinator.select(A);
            assertTrue(page.feedback(A).waiting());
            assertFalse(page.feedback(B).waiting());
            f.tick();
            assertEquals(ModelResourcePurpose.FULL, f.requests.get(1).purpose());
            assertEquals(WorkPriority.LOCAL_PLAYER, f.priorities.get(A.modelId()));
            assertEquals(2, f.interests(A), "GUI anchor and FULL selection remain independent");
            f.ready(A);
            assertTrue(page.feedback(A).waiting());
            assertNull(card.lease);
            f.coordinator.register(player);
            f.tick();
            assertNotNull(player.lease);
            f.selections.authority = f.selections.installed = A;
            f.coordinator.selectionCommitted(f.selections.context(), 1, A, A);
            f.tick();
            assertFalse(page.feedback(A).waiting());
            assertNull(card.lease);
            page.close();
            assertInstanceOf(AcquireResult.Ready.class, player.lease.poll());
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void hoverAdmissionIncludesTheBoundaryAndExitRetainsTheExactPurpose(boolean ready) throws Exception {
        try (var f = new Fixture()) {
            var page = f.coordinator.openPage("list");
            page.pointer(A);
            f.tick();
            f.at(299);
            assertEquals(0, f.starts.size());
            f.at(300);
            assertEquals(List.of(A.modelId()), f.starts);
            if (ready)
                f.ready(A);
            page.pointer(null);
            f.at(800);
            assertTrue(f.interests(A) > 0);
            page.pointer(B);
            f.at(1099);
            assertEquals(1, f.starts.size());
            f.at(1100);
            assertEquals(2, f.starts.size());
            assertEquals(0, f.interests(A));
            assertTrue(f.interests(B) > 0);
            assertTrue(f.selections.sent.isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"1,false", "2,false", "3,false", "4,false", "5,false", "6,false", "7,false", "8,false", "1,true",
        "2,true", "3,true", "4,true", "5,true", "6,true", "7,true", "8,true"})
    void continuityMatrixKeepsIndependentPendingAndReadyPurposes(int row, boolean ready) throws Exception {
        try (var f = new Fixture()) {
            var page = f.coordinator.openPage("list");
            if (row <= 2)
                f.hover(page, A, ready);
            else {
                f.select(A, ready);
            }
            if (row >= 5 && row <= 7)
                f.hover(page, B, ready);
            int startsBefore = f.starts.size();
            switch (row) {
                case 1 -> f.coordinator.select(A);
                case 2 -> f.coordinator.select(B);
                case 3 -> f.hover(page, A, false);
                case 4 -> f.hover(page, B, false);
                case 5 -> f.coordinator.select(B);
                case 6 -> f.coordinator.select(C);
                case 7 -> f.hover(page, A, false);
                case 8 -> {
                    f.coordinator.select(B);
                    f.tick();
                    f.coordinator.select(B);
                }
                default -> throw new AssertionError();
            }
            f.clock.addAndGet(701);
            f.tick();
            boolean keepA = row == 1 || row == 2 || row == 3 || row == 4 || row == 7;
            boolean keepB = row == 2 || row == 4 || row == 5 || row == 6 || row == 8;
            assertEquals(keepA, f.interests(A) > 0, "A purpose, row " + row);
            assertEquals(keepB, f.interests(B) > 0, "B purpose, row " + row);
            assertEquals(row == 6, f.interests(C) > 0, "C purpose");
            if (row == 3 || row == 7)
                assertEquals(startsBefore + 1, f.starts.size(), "GUI must start independently of FULL");
            assertEquals(row == 1 || row == 3 || row == 7 ? 2 : 1,
                f.starts.stream().filter(A.modelId()::equals).count());
        }
    }

    @Test
    void reentrantSubmissionCannotSendTwiceOrCompleteBeforeTheSendReturns() throws Exception {
        try (var f = new Fixture()) {
            f.selections.authority = f.selections.installed = A;
            f.selections.trace = event -> {
                if (event.startsWith("send:")) {
                    assertNotNull(f.coordinator.association(f.selections.context(), 1, A));
                    f.coordinator.selectionCommitted(f.selections.context(), 1, A, A);
                    f.tick();
                    assertTrue(f.coordinator.feedback().waiting());
                }
            };
            f.coordinator.select(A);
            f.tick();
            assertEquals(List.of(A), f.selections.sent);
            assertFalse(f.coordinator.feedback().waiting());
            assertNull(f.coordinator.association(f.selections.context(), 1, A));
        }
    }

    @Test
    void matchingTargetWithOldInstallationCannotFinishANewApplication() throws Exception {
        try (var f = new Fixture()) {
            f.selections.authority = f.selections.installed = A;
            f.selections.installedApplication = new Object();
            f.coordinator.reselect(A);
            f.tick();
            var application = new Object();
            f.coordinator.selectionCommitted(f.selections.context(), 1, A, application);
            f.tick();
            assertTrue(f.coordinator.feedback().waiting());
            f.selections.installedApplication = application;
            f.tick();
            assertFalse(f.coordinator.feedback().waiting());
        }
    }

    @Test
    void explicitReselectKeepsPreferenceInputAndRequiresItsAppliedInstallation() throws Exception {
        try (var f = new Fixture()) {
            f.selections.authority = f.selections.installed = A;
            var value = fromMap(Map.of("old", 7F));
            f.selections.retained = value;
            f.coordinator.reselect(A);
            f.tick();
            assertEquals(List.of(value), f.selections.initials);
            assertEquals(ClientDemandCoordinator.SelectionKind.RESELECT,
                f.coordinator.association(f.selections.context(), 1, A).kind());
            assertTrue(f.coordinator.feedback().waiting());
        }
    }

    @Test
    void synchronousSendFailureTerminatesOnceAndDoesNotReplay() throws Exception {
        try (var f = new Fixture()) {
            var cause = new IllegalStateException("transport unavailable");
            f.selections.sendFailure = cause;
            f.selections.installed = A;
            f.coordinator.select(A);
            f.tick();
            assertFalse(f.coordinator.feedback().waiting());
            var failure =
                assertInstanceOf(ClientDemandCoordinator.SelectionFailure.class, f.coordinator.feedback().failure());
            assertEquals(ClientDemandCoordinator.FailureStage.SEND, failure.stage());
            assertSame(cause, failure.getCause());
            assertEquals(List.of(A), f.selections.sent);
            assertEquals(List.of(failure), f.selections.failures);
            f.coordinator.selectionCommitted(f.selections.context(), 1, A, A);
            f.at(1000);
            f.at(ClientDemandCoordinator.WAIT_MILLIS);
            assertEquals(List.of(A), f.selections.sent);
            assertEquals(1, f.selections.failures.size());
            assertNull(f.coordinator.association(f.selections.context(), 1, A));
        }
    }

    @ParameterizedTest
    @CsvSource({"uuid", "binding"})
    void sameOwnerIdentityReplacementRetiresOldInitialAndRejectsItsAuthority(String replacement) throws Exception {
        try (var f = new Fixture()) {
            f.select(A, true);
            var previous = f.selections.context();
            if (replacement.equals("uuid"))
                f.selections.player = new UUID(0, 2);
            else
                f.selections.preferenceBinding = new Object();
            f.tick();
            assertFalse(f.coordinator.feedback().waiting());
            assertNull(f.coordinator.association(previous, 1, A));
            f.coordinator.select(B);
            f.at(701);
            f.ready(B);
            assertEquals(List.of(1L, 2L), f.selections.ids);
            f.selections.authority = f.selections.installed = B;
            f.coordinator.selectionCommitted(previous, 2, B, B);
            f.tick();
            assertTrue(f.coordinator.feedback().waiting());
            f.coordinator.selectionCommitted(f.selections.context(), 2, B, B);
            f.tick();
            assertFalse(f.coordinator.feedback().waiting());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    @ExtendWith(NativeLibraryExtension.class)
    void realPreferenceFailureSendsEmptyInitialWithoutMakingItPersistable(@TempDir Path directory) throws Exception {
        try (var work = new DatabaseWork()) {
            var preferences = new PlayerPreferences(directory, work);
            com.elfmcys.ysm.model.storage.PreferenceTestDriver.await(preferences,
                preferences.readSelection(new UUID(0, 1)));
        }
        try (
            var connection =
                java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("preferences-c2.sqlite"));
            var statement = connection.createStatement()) {
            statement.execute("UPDATE storage_meta SET compatibility_epoch=99 WHERE id=1");
        }
        try (var work = new DatabaseWork(); var f = new Fixture(new PlayerPreferences(directory, work))) {
            f.selections.retained = null;
            f.selections.authority = f.selections.installed = B;
            f.select(A, true);
            work.awaitIdle();
            f.tick();
            assertTrue(f.coordinator.feedback().waiting());
            assertNull(f.coordinator.feedback().failure());
            assertTrue(f.selections.failures.isEmpty());
            assertEquals(1, f.selections.readFailures.size());
            assertTrue(f.selections.readFailures.get(0).getMessage().contains("INCOMPATIBLE"));
            assertEquals(List.of(A), f.selections.sent);
            assertTrue(named(f.selections.initials.get(0)).isEmpty());
            assertFalse(f.coordinator.association(f.selections.context(), 1, A).initial().persistable());
            assertEquals(B, f.selections.authority);
            assertEquals(B, f.selections.installed);
            f.at(1000);
            assertEquals(1, f.selections.readFailures.size());
            assertEquals(List.of(A), f.selections.sent);
            f.selections.authority = f.selections.installed = A;
            f.coordinator.selectionCommitted(f.selections.context(), 1, A, A);
            f.tick();
            assertFalse(f.coordinator.feedback().waiting());
            assertNull(f.coordinator.feedback().failure());
            assertEquals(0, f.interests(A));
        }
    }

    @ParameterizedTest
    @CsvSource({"replacement", "uuid", "binding", "clear"})
    @EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    @ExtendWith(NativeLibraryExtension.class)
    void retiringSelectionCancelsItsQueuedDatabaseFill(String action, @TempDir Path directory) throws Exception {
        try (var work = new DatabaseWork()) {
            var preferences = new PlayerPreferences(directory, work);
            try (var f = new Fixture(preferences)) {
                f.selections.retained = null;
                var original = f.selections.context();
                f.select(A, false);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var blocker = work.createResource(token -> (AutoCloseable)() -> {
                });
                work.submit(blocker, (unused, token) -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("Worker was not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return StorageResult.refused(StorageResult.Failure.CANCELLED);
                });
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    f.ready(A);
                    assertTrue(f.coordinator.feedback().waiting());
                    switch (action) {
                        case "replacement" -> f.coordinator.select(B);
                        case "uuid" -> f.selections.player = new UUID(0, 2);
                        case "binding" -> f.selections.preferenceBinding = new Object();
                        case "clear" -> f.coordinator.cancelSelection(A.identity());
                        default -> throw new AssertionError(action);
                    }
                    f.tick();
                    assertTrue(f.selections.sent.isEmpty());
                } finally {
                    release.countDown();
                }
                work.awaitIdle();
                f.tick();
                assertTrue(f.selections.sent.isEmpty());
                assertNull(f.coordinator.association(original, 1, A));
                assertEquals(StorageResult.Outcome.MISSING, com.elfmcys.ysm.model.storage.PreferenceTestDriver
                    .await(preferences, preferences.readRoaming(original.player(), A.identity(), Set.of())).outcome());
                assertTrue(f.selections.failures.isEmpty());
            }
        }
    }

    @Test
    void busyInstallationKeepsReadyWithoutWaitingAndRetirementClosesOnlyItsInterest() throws Exception {
        try (var f = new Fixture()) {
            var endpoint = new Receiver(f, A, ModelResourcePurpose.FULL);
            endpoint.busy = true;
            var scope = f.coordinator.register(endpoint);
            f.tick();
            f.ready(A);
            assertNull(endpoint.lease);
            assertTrue(f.interests(A) > 0);
            endpoint.busy = false;
            f.tick();
            assertNotNull(endpoint.lease);
            scope.close();
            assertInstanceOf(AcquireResult.Ready.class, endpoint.lease.poll());
            endpoint.close();
            f.cache.tick();
        }
    }

    @Test
    void pageIdentitySurvivesRebuildAndLateOldCloseCannotRetireReplacement() throws Exception {
        try (var f = new Fixture()) {
            f.local = true;
            var old = f.coordinator.openPage(List.of("A", "B"));
            f.hover(old, A, false);
            assertSame(old, f.coordinator.replacePage(old, List.of("A", "B")));
            assertTrue(f.interests(A) > 0);
            var replacement = f.coordinator.replacePage(old, List.of("B", "A"));
            assertEquals(0, f.interests(A));
            f.hover(replacement, B, false);
            old.close();
            assertTrue(f.interests(B) > 0);
            replacement.close();
            f.cache.tick();
            assertEquals(0, f.cache.loadingCount());
            assertTrue(f.pending.get(B.modelId()).isCancelled());
        }
    }

    @Test
    void readFrequencyDoesNotAdvanceWorkOrRenewWait() throws Exception {
        try (var f = new Fixture()) {
            f.coordinator.select(A);
            for (int i = 0; i < 1000; i++) {
                assertTrue(f.coordinator.feedback().waiting());
                f.coordinator.canOpenTextures();
            }
            assertEquals(0, f.starts.size());
            assertTrue(f.selections.sent.isEmpty());
            f.tick();
            assertEquals(1, f.starts.size());
            assertTrue(f.selections.sent.isEmpty());
            f.ready(A);
            assertEquals(List.of(A), f.selections.sent);
        }
    }

    @Test
    void switchDwellIsStrictAndCompleteLocalWorkBypassesIt() throws Exception {
        try (var f = new Fixture()) {
            f.select(A, true);
            f.clock.set(100);
            f.coordinator.select(B);
            f.tick();
            f.at(800);
            assertEquals(List.of(A), f.selections.sent);
            f.at(801);
            f.ready(B);
            assertEquals(List.of(A, B), f.selections.sent);
        }
        try (var f = new Fixture()) {
            f.local = true;
            f.select(A, true);
            f.clock.set(100);
            f.coordinator.select(B);
            f.tick();
            assertEquals(List.of(A), f.selections.sent);
            f.at(400);
            assertEquals(1, f.starts.size());
            f.at(401);
            f.ready(B);
            assertEquals(List.of(A, B), f.selections.sent);
            assertEquals(1, f.onlineCalls, "Online admission delegates the local probe to the resource owner");
        }
    }

    @Test
    void pageAndHoverClocksAreIndependentAndRebuildDoesNotRenewEither() throws Exception {
        try (var f = new Fixture()) {
            var old = f.coordinator.openPage("first");
            var next = f.coordinator.replacePage(old, "second");
            var modes = new ArrayList<ModelResourceDemand.Mode>();
            next.action(new ClientDemandCoordinator.PageAction() {
                public void advance(ModelResourceDemand.Mode mode) {
                    modes.add(mode);
                }

                public void close() {}
            });
            next.pointer(A);
            f.at(299);
            assertTrue(f.starts.isEmpty());
            assertEquals(ModelResourceDemand.Mode.READY, modes.get(modes.size() - 1));
            assertSame(next, f.coordinator.replacePage(next, "second"));
            f.at(301);
            assertTrue(f.starts.isEmpty());
            assertEquals(1, f.offlineCalls);
            assertEquals(ModelResourceDemand.Mode.OFFLINE, modes.get(modes.size() - 1));
            f.at(700);
            assertEquals(1, f.onlineCalls);
            f.at(701);
            assertEquals(1, f.onlineCalls);
            assertEquals(ModelResourceDemand.Mode.ONLINE, modes.get(modes.size() - 1));
        }
    }

    @ParameterizedTest
    @CsvSource({"300,false", "301,false", "700,false", "701,true"})
    void eagerCardsDoNotStartLocalWorkUntilTheirPageIsAdmitted(long previousDuration, boolean immediate)
        throws Exception {
        try (var f = new Fixture(); var card = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            f.eager = f.local = true;
            var previous = f.coordinator.openPage("first");
            f.clock.set(previousDuration);
            var page = f.coordinator.replacePage(previous, "second");
            page.card(card, A);
            f.tick();
            assertEquals(immediate ? 1 : 0, f.offlineCalls);
            f.at(previousDuration + 299);
            assertEquals(immediate ? 1 : 0, f.offlineCalls);
            f.at(previousDuration + 300);
            assertEquals(1, f.offlineCalls);
            f.at(previousDuration + 301);
            f.at(previousDuration + 701);
            assertEquals(1, f.offlineCalls);
            assertEquals(List.of(A.modelId()), f.starts);
            assertEquals(0, f.onlineCalls, "automatic cards never fetch remote content");
            page.close();
            assertEquals(0, f.interests(A));
        }
    }

    @Test
    void aQualifiedCardCanReuseReadyBeforeLocalAdmission() throws Exception {
        try (var f = new Fixture(); var card = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            f.eager = true;
            var request = f.resourceRequest(A.modelId(), "player", "main", ModelResourcePurpose.GUI, null);
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.ready(A);
                var previous = f.coordinator.openPage("first");
                f.clock.set(100);
                var page = f.coordinator.replacePage(previous, "second");
                page.card(card, A);
                f.tick();
                assertNotNull(card.lease);
                assertEquals(0, f.offlineCalls);
                assertEquals(0, f.onlineCalls);
                page.close();
                assertInstanceOf(AcquireResult.Ready.class, producer.poll());
            }
        }
    }

    @Test
    void lateOfflineMissUsesTheAlreadyOpenNetworkGate() throws Exception {
        try (var f = new Fixture()) {
            f.offlineProbe = new Probe();
            var previous = f.coordinator.openPage("first");
            f.clock.set(100);
            var page = f.coordinator.replacePage(previous, "second");
            page.pointer(A);
            f.at(400);
            assertEquals(1, f.offlineCalls);
            f.at(401);
            f.at(801);
            assertFalse(f.offlineProbe.closed);
            assertEquals(1, f.offlineCalls);
            assertEquals(0, f.onlineCalls);
            f.clock.set(1000);
            f.offlineProbe.missing = true;
            f.tick();
            assertEquals(1, f.offlineCalls);
            assertEquals(1, f.onlineCalls, "900 ms miss does not start another dwell interval");
        }
    }

    @Test
    void readyWaitDoesNotRetainFullFromAReplacedRepresentation() throws Exception {
        try (var f = new Fixture()) {
            f.local = true;
            var request = f.resourceRequest(A.modelId(), "player", "main", ModelResourcePurpose.FULL, null);
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.ready(A);
                f.select(B, false);
                f.clock.set(100);
                f.select(A, false);
                assertEquals(2, f.interests(A));
                int localBefore = f.offlineCalls;
                f.contents.put(A.modelId(), content(A.modelId(), hash(99)));
                f.tick();
                assertEquals(1, f.interests(A), "only the independent old consumer retains the old representation");
                assertEquals(localBefore, f.offlineCalls);
                f.at(400);
                assertEquals(localBefore, f.offlineCalls);
                f.at(401);
                assertEquals(localBefore + 1, f.offlineCalls);
                assertInstanceOf(AcquireResult.Ready.class, producer.poll());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void fullPendingDoesNotGrantOrSuppressGuiAdmissionAndNeitherResultCompletesTheOther(boolean guiFirst)
        throws Exception {
        try (var f = new Fixture(); var card = new Receiver(f, A, ModelResourcePurpose.GUI);
            var player = new Receiver(f, A, ModelResourcePurpose.FULL)) {
            var page = f.coordinator.openPage("list");
            page.card(card, A);
            f.select(A, false);
            f.coordinator.register(player);
            f.tick();
            var full = f.pending.get(A.modelId());
            f.at(1000);
            assertEquals(1, f.starts.size(), "unqualified GUI must stay static even with FULL pending");
            assertNull(card.lease);
            page.pointer(A);
            f.at(1299);
            assertEquals(1, f.starts.size());
            f.at(1300);
            var gui = f.pending.get(A.modelId());
            assertNotSame(full, gui);
            assertEquals(2, f.starts.size());
            assertEquals(ModelResourceDemand.Mode.READY, card.mode, "the GUI anchor owns its accepted work");
            var first = guiFirst ? gui : full;
            var second = guiFirst ? full : gui;
            f.complete(first);
            f.tick();
            assertEquals(guiFirst, card.lease != null);
            assertEquals(!guiFirst, player.lease != null);
            assertTrue(f.coordinator.feedback().waiting(), "FULL Ready does not prove Matching installation");
            f.complete(second);
            f.tick();
            assertEquals(ModelResourcePurpose.GUI, card.lease.purpose());
            assertEquals(ModelResourcePurpose.FULL, player.lease.purpose());
            f.selections.installed = f.selections.authority = A;
            f.coordinator.selectionCommitted(f.selections.context(), 1, A, A);
            f.tick();
            assertFalse(f.coordinator.feedback().waiting());
            page.close();
            assertNull(card.lease);
            assertInstanceOf(AcquireResult.Ready.class, player.lease.poll());
        }
    }

    @Test
    void anEntityKeepsItsAcceptedReceivingLeaseAfterPermissionRevocation() throws Exception {
        try (var f = new Fixture(); var host = new Receiver(f, A, ModelResourcePurpose.FULL)) {
            f.coordinator.register(host);
            f.tick();
            assertEquals(1, f.interests(A));
            f.denied.add(A.modelId());
            f.ready(A);
            assertTrue(host.hasInstallation());
            assertTrue(host.full);
            assertEquals(1, f.interests(A));
            try (var replacement = new Receiver(f, A, ModelResourcePurpose.FULL)) {
                f.coordinator.register(replacement);
                f.tick();
                assertFalse(replacement.hasInstallation());
            }
        }
    }

    @Test
    void selectionWaitsForCompleteDeclarationPreparationEvenWithRetainedInput() throws Exception {
        try (var f = new Fixture()) {
            f.coordinator.select(A);
            f.tick();
            assertTrue(f.selections.sent.isEmpty());
            f.ready(A);
            assertEquals(List.of(A), f.selections.sent);
            var association = f.coordinator.association(f.selections.context(), 1, A);
            assertNotNull(association);
            assertNotNull(association.dictionary());
            f.coordinator.selectionCommitted(f.selections.context(), 1, A, new Object());
            assertNull(f.coordinator.association(f.selections.context(), 1, A));
        }
    }

    /** A controllable pending use for cache-only and offline admission. */
    private static final class Probe implements ResourceLease {
        private ResourceRequest request;
        private boolean missing;
        private boolean closed;

        public ModelResourcePurpose purpose() {
            return request.purpose();
        }

        public AcquireResult poll() {
            if (closed)
                return new AcquireResult.Ended();
            return missing ? new AcquireResult.Missing() : new AcquireResult.Pending();
        }

        public boolean isCurrent(ResourceRequest candidate) {
            return !closed && request.equals(candidate);
        }

        public void cancelPending() {
            close();
        }

        public void close() {
            closed = true;
        }
    }

    private static final class Fixture implements ClientDemandCoordinator.Resources, AutoCloseable {
        final AtomicLong clock = new AtomicLong();
        final List<String> events = new ArrayList<>();
        final Selection selections = new Selection();
        final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
        final ModelRenderTargetCache cache = new ModelRenderTargetCache(scheduler, ignored -> {
        }, ignored -> {
        }, ignored -> {
            throw new AssertionError("testing candidate");
        }, () -> 8, () -> 0);
        final Map<ModelId, ModelContent> contents = new HashMap<>();
        final Map<ModelId, CompletableFuture<ModelRenderTargetLoader.LoadResult>> pending = new HashMap<>();
        final List<ModelId> starts = new ArrayList<>();
        final Set<ModelId> denied = new HashSet<>();
        final Map<ModelId, Integer> interests = new HashMap<>();
        final ClientDemandCoordinator coordinator;
        final PlayerPreferences preferences;
        boolean eager;
        final List<ResourceRequest> requests = new ArrayList<>();
        final Map<ModelId, WorkPriority> priorities = new HashMap<>();
        int cachedCalls;
        int offlineCalls;
        boolean local;
        int onlineCalls;
        int contentReads;
        Probe cachedProbe;
        Probe offlineProbe;

        Fixture() {
            this(null);
        }

        Fixture(PlayerPreferences preferences) {
            this.preferences = preferences;
            coordinator = new ClientDemandCoordinator(clock::get, this, selections, preferences, () -> this.eager);
            selections.trace = this::event;
        }

        void event(String effect) {
            events.add(clock.get() + ":" + effect);
        }

        ModelId sourceId(PublishedModelId id) {
            return id instanceof PublishedModelId.Model model ? model.hash() : hash(0);
        }

        public ModelContent content(PublishedModelId id) {
            return content(sourceId(id));
        }

        int marker(ModelId id) {
            return Integer.parseInt(id.toString().substring(5, 7), 16);
        }

        void tick() {
            cache.tick();
            coordinator.tick();
            if (preferences != null)
                com.elfmcys.ysm.model.storage.PreferenceTestDriver.tick(preferences);
        }

        void at(long millis) {
            clock.set(millis);
            tick();
        }

        void select(ClientDemandCoordinator.Target target, boolean ready) throws Exception {
            coordinator.select(target);
            tick();
            if (ready)
                ready(target);
        }

        void hover(ClientDemandCoordinator.PageScope page, ClientDemandCoordinator.Target target, boolean ready)
            throws Exception {
            page.pointer(target);
            clock.addAndGet(301);
            tick();
            if (ready)
                ready(target);
        }

        void ready(ClientDemandCoordinator.Target target) throws Exception {
            complete(target);
            tick();
        }

        void complete(ClientDemandCoordinator.Target target) throws Exception {
            complete(pending.get(target.modelId()));
        }

        void complete(CompletableFuture<ModelRenderTargetLoader.LoadResult> future) throws Exception {
            var value = metadataTarget(hash(0), List.of());
            future.complete(
                new ModelRenderTargetLoader.LoadResult.Ready(ModelRenderTargetLoader.ModelCandidate.testing(value)));
        }

        int interests(ClientDemandCoordinator.Target target) {
            return interests.getOrDefault(target.modelId(), 0);
        }

        public ModelContent content(ModelId id) {
            contentReads++;
            return contents.computeIfAbsent(id, ClientDemandCoordinatorTest::content);
        }

        public ResourceRequest resourceRequest(ModelId id, String target, String texture, ModelResourcePurpose purpose,
            Class<?> subject) {
            return new ResourceRequest(id, target, texture, PROFILE, ExpressionBinding.standalone(), purpose);
        }

        public ResourceRequest defaultResourceRequest(String target, ModelResourcePurpose purpose, Class<?> subject) {
            return new ResourceRequest(PublishedModelId.INTRINSIC_DEFAULT, target, "", PROFILE,
                ExpressionBinding.standalone(), purpose);
        }

        public boolean permitsAcquisition(ResourceRequest request) {
            return !denied.contains(sourceId(request.modelId()));
        }

        RenderTargetKey key(ResourceRequest request, boolean full) {
            return new RenderTargetKey(request.targetId(), request.requestedTexture(), PROFILE,
                request.expressionBinding(), full ? ModelResourcePurpose.FULL : request.purpose(),
                request.modelId().role());
        }

        public Optional<ResourceLease> findReady(ResourceRequest request) {
            if (!permitsAcquisition(request))
                return Optional.empty();
            if (request.purpose() == ModelResourcePurpose.FULL && selections.installed != null
                && request.modelId().equals(selections.installed.identity())) {
                try {
                    var target = metadataTarget(sourceId(request.modelId()),
                        selections.retained == null ? List.of() : java.util.Arrays.asList(selections.retained.names()));
                    return Optional.of(new ResourceLease() {
                        boolean ended;

                        public ModelResourcePurpose purpose() {
                            return request.purpose();
                        }

                        public AcquireResult poll() {
                            return ended ? new AcquireResult.Ended() : new AcquireResult.Ready(target);
                        }

                        public boolean isCurrent(ResourceRequest asked) {
                            return !ended && request.equals(asked);
                        }

                        public void cancelPending() {
                            close();
                        }

                        public void close() {
                            ended = true;
                        }
                    });
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }
            return cache.findReady(request, content(sourceId(request.modelId())), key(request, false),
                (identity, key, asked) -> true).map(lease -> wrap(request, lease));
        }

        public ResourceLease getOrStartCached(ResourceRequest request, WorkPriority priority) {
            cachedCalls++;
            if (cachedProbe == null)
                return ResourceLease.missing(request);
            cachedProbe.request = request;
            return cachedProbe;
        }

        public ResourceLease getOrStartOffline(ResourceRequest request, WorkPriority priority) {
            offlineCalls++;
            if (offlineProbe != null) {
                offlineProbe.request = request;
                return offlineProbe;
            }
            return local ? start(request, priority) : ResourceLease.missing(request);
        }

        public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
            onlineCalls++;
            return start(request, priority);
        }

        private ResourceLease start(ResourceRequest request, WorkPriority priority) {
            priorities.put(sourceId(request.modelId()), priority);
            return wrap(request, cache.getOrStart(request, content(sourceId(request.modelId())), key(request, false),
                (ignored, dispatch) -> {
                    requests.add(request);
                    starts.add(sourceId(request.modelId()));
                    event("start:" + marker(sourceId(request.modelId())));
                    var future = new CompletableFuture<ModelRenderTargetLoader.LoadResult>() {
                        @Override
                        public boolean cancel(boolean interrupt) {
                            event("work-cancel:" + marker(sourceId(request.modelId())));
                            return super.cancel(interrupt);
                        }
                    };
                    future.whenComplete(
                        (outcome, error) -> event("work-terminal:" + marker(sourceId(request.modelId())) + ":"
                            + (error == null ? outcome.getClass().getSimpleName() : error.getClass().getSimpleName())));
                    pending.put(sourceId(request.modelId()), future);
                    return future;
                }, (identity, key, asked) -> true, selections.owner, priority));
        }

        ResourceLease wrap(ResourceRequest request, ResourceLease inner) {
            interests.merge(sourceId(request.modelId()), 1, Integer::sum);
            event("acquire:" + marker(sourceId(request.modelId())));
            return new ResourceLease() {
                boolean closed;

                public ModelResourcePurpose purpose() {
                    return inner.purpose();
                }

                public AcquireResult poll() {
                    return inner.poll();
                }

                public boolean isCurrent(ResourceRequest asked) {
                    return inner.isCurrent(asked);
                }

                public void cancelPending() {
                    inner.cancelPending();
                }

                public void close() {
                    if (!closed) {
                        closed = true;
                        inner.close();
                        interests.merge(sourceId(request.modelId()), -1, Integer::sum);
                        event("release:" + marker(sourceId(request.modelId())));
                    }
                }
            };
        }

        public void reportActiveModelUse(ResourceRequest request) {}

        public void reportActiveModelFailure(ModelContent content, boolean fallback) {}

        public void close() {
            coordinator.close();
            cache.close();
            scheduler.shutdown();
            try {
                scheduler.awaitTermination();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }

    private static final class Selection implements ClientDemandCoordinator.Selections {
        java.util.function.Consumer<String> trace = ignored -> {
        };
        Object owner = new Object();
        UUID player = new UUID(0, 1);
        Object preferenceBinding = new Object();
        NamedRoamingSnapshot retained = fromMap(Map.of());
        RuntimeException sendFailure;
        ClientDemandCoordinator.Target authority = new ClientDemandCoordinator.Target(null, "");
        ClientDemandCoordinator.Target installed;
        Object installedApplication;
        final List<ClientDemandCoordinator.Target> sent = new ArrayList<>();
        final List<Long> ids = new ArrayList<>();
        final List<NamedRoamingSnapshot> initials = new ArrayList<>();
        final List<Boolean> reinitializes = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final List<Throwable> readFailures = new ArrayList<>();

        public ClientDemandCoordinator.SelectionContext context() {
            return new ClientDemandCoordinator.SelectionContext(owner, player, preferenceBinding, true, true);
        }

        public ClientDemandCoordinator.Target authority() {
            return authority;
        }

        public boolean installed(ClientDemandCoordinator.Target target, Object application) {
            return target.equals(installed)
                && (application == null || installedApplication == null || installedApplication == application);
        }

        public ClientDemandCoordinator.InitialRoaming retainedInitial(ClientDemandCoordinator.SelectionContext context,
            ClientDemandCoordinator.Target target) {
            return retained == null ? null : new ClientDemandCoordinator.InitialRoaming(retained, true);
        }

        public void send(ClientDemandCoordinator.SelectionContext context, ClientDemandCoordinator.Target target,
            long id, NamedRoamingSnapshot initial, boolean reinitialize) {
            assertTrue(context.matches(context()));
            assertNotNull(initial);
            sent.add(target);
            ids.add(id);
            initials.add(initial);
            reinitializes.add(reinitialize);
            trace.accept("send:" + (target.modelId() == null ? "default"
                : Integer.parseInt(target.modelId().toString().substring(5, 7), 16)) + ":" + id);
            if (sendFailure != null)
                throw sendFailure;
        }

        public void reportFailure(Throwable failure) {
            failures.add(failure);
            trace.accept("selection-terminal:" + failure.getMessage());
        }

        public void reportPreferenceReadFailure(Throwable failure) {
            readFailures.add(failure);
        }
    }

    private static final class Receiver
        implements ClientDemandCoordinator.Installation, ClientDemandCoordinator.CardDisplay, AutoCloseable {
        EntityModelDemand scope;
        ModelResourceDemand.Mode mode;
        final Fixture fixture;
        final ModelResourcePurpose purpose;
        ClientDemandCoordinator.Target target;
        ResourceLease lease;
        ResourceRequest installed;
        boolean full;
        boolean busy;

        Receiver(Fixture fixture, ClientDemandCoordinator.Target target, ModelResourcePurpose purpose) {
            this.fixture = fixture;
            this.target = target;
            this.purpose = purpose;
            mode = purpose == ModelResourcePurpose.FULL ? ModelResourceDemand.Mode.ONLINE
                : ModelResourceDemand.Mode.CACHED;
        }

        public ClientDemandCoordinator.Assignment assignment() {
            return new ClientDemandCoordinator.Assignment(
                fixture.resourceRequest(target.modelId(), "player", "main", purpose, null), null, mode, true);
        }

        public void activate(ModelResourceDemand.Mode next) {
            mode = next;
            if (scope == null || scope.isClosed())
                scope = fixture.coordinator.register(this);
        }

        public void deactivate() {
            if (scope != null)
                scope.close();
            close();
        }

        public boolean hasInstallation() {
            return lease != null;
        }

        public boolean matches(ResourceRequest request, boolean needsFull) {
            return lease != null && installed.equals(request) && (!needsFull || full);
        }

        public Throwable failure(ResourceRequest request) {
            return null;
        }

        public ClientDemandCoordinator.InstallationResult install(ResourceRequest request, ResourceLease candidate,
            boolean intrinsic, boolean needsFull) {
            if (busy)
                return ClientDemandCoordinator.InstallationResult.BUSY;
            close();
            lease = candidate;
            installed = request;
            full = candidate.purpose() == ModelResourcePurpose.FULL;
            fixture.event("install:" + fixture.marker(fixture.sourceId(request.modelId())));
            return ClientDemandCoordinator.InstallationResult.INSTALLED;
        }

        public void close() {
            if (lease != null) {
                lease.close();
                lease = null;
            }
        }
    }

    private static ModelRenderTarget metadataTarget(ModelId id, List<String> fields)
        throws ReflectiveOperationException {
        var store = com.elfmcys.ysm.expression.aot.TestExpressionModules.store(
            com.elfmcys.ysm.expression.aot.TestExpressionModules.create(new java.util.LinkedHashSet<>(fields)),
            Map.of());
        return new ModelRenderTarget(id, "main", new RenderTargetResources() {},
            (CommonAsset)UnsafeUtil.getUnsafe().allocateInstance(CommonAsset.class), store,
            (com.elfmcys.ysm.format.schema.model.views.ModelInfoView)UnsafeUtil.getUnsafe()
                .allocateInstance(com.elfmcys.ysm.format.schema.model.views.ModelInfoView.class));
    }

    static ClientDemandCoordinator.Target target(int marker) {
        return new ClientDemandCoordinator.Target(hash(marker), "main");
    }

    static ModelId hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return ModelId.fromHash(new Hash256(bytes));
    }

    static ModelContent content(ModelId id) {
        return content(id, hash(100 + Integer.parseInt(id.toString().substring(5, 7), 16)));
    }

    static ModelContent content(ModelId id, ModelId containerId) {
        try {
            var representation =
                (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
            var field = ModelRepresentation.class.getDeclaredField("identity");
            field.setAccessible(true);
            field.set(representation, new ModelFileIdentity(id, Hash256.parse(containerId.toString().substring(5))));
            return new ModelContent() {
                public ModelId modelId() {
                    return id;
                }

                public ModelRepresentation representation() {
                    return representation;
                }

                public ModelFileView modelFile() {
                    return null;
                }

                public ChunkDataSource chunks() {
                    return null;
                }
            };
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
