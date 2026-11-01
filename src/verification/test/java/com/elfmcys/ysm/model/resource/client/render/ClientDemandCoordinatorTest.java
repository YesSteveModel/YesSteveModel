package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.*;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
        return Stream.of(-1L, 0L, 300L, 301L, 699L, 700L, 701L).flatMap(previous ->
            Stream.of(0L, 299L, 300L, 699L, 700L).map(current -> Arguments.of(previous, current)));
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
            assertEquals(List.of("0:acquire:1", "0:release:1"), f.events.subList(eventStart, f.events.size()));
            assertEquals(1, f.interests(A));
            assertNull(scope.claimReadyInstallation());
            var lease = claim.takeLease();
            assertThrows(IllegalStateException.class, claim::takeLease);
            var outcome = receiver.install(claim.request(), lease, claim.fallback(), false);
            assertTrue(scope.hasUnfinishedWork(), "only explicit frame completion ends the handoff");
            claim.complete(outcome);
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
                assertEquals(1, f.interests(A));
            }
            assertEquals(0, f.interests(A));
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
                assertEquals(1, f.interests(A));
                page.close();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"CACHED", "OFFLINE"})
    void readyOnlyMissDoesNotCancelRestartOrDowngradeAnExistingAcquisition(ModelResourceDemand.Mode mode)
        throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var probe = new CompletableFuture<Optional<ResourceLease>>();
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
                assertFalse(probe.isCancelled());
            }
            f.tick();
            assertFalse(probe.isCancelled());
            assertEquals(mode == ModelResourceDemand.Mode.CACHED ? 1 : 0, f.cachedCalls);
            assertEquals(mode == ModelResourceDemand.Mode.OFFLINE ? 1 : 0, f.offlineCalls);
            assertEquals(0, f.onlineCalls);
            assertEquals(0, f.starts.size());
            var request = receiver.assignment().request();
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.complete(A);
                f.cache.tick();
                assertTrue(scope.hasReadyInstallation());
                assertTrue(probe.isCancelled(), "only an acquired Ready replacement can retire the old probe");
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
                assertEquals(1, f.interests(A));
                f.tick();
                var busy = Objects.requireNonNull(scope.claimReadyInstallation());
                receiver.busy = true;
                var lease = busy.takeLease();
                var outcome = receiver.install(busy.request(), lease, false, false);
                assertEquals(ClientDemandCoordinator.InstallationResult.BUSY, outcome);
                lease.close();
                busy.complete(outcome);
                f.tick();
                assertTrue(scope.hasReadyInstallation());
                var finalClaim = Objects.requireNonNull(scope.claimReadyInstallation());
                var taskLease = finalClaim.takeLease();
                scope.close();
                finalClaim.complete(ClientDemandCoordinator.InstallationResult.STALE);
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
    void rejectedFrameInstallationReportsItsExplicitOutcomeAndDoesNotOwnTheTransferredLease() throws Exception {
        try (var f = new Fixture(); var receiver = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var request = f.resourceRequest(A.modelId(), "player", "main", ModelResourcePurpose.GUI, null);
            try (var producer = f.start(request, WorkPriority.BACKGROUND)) {
                f.ready(A);
                var scope = f.coordinator.register(receiver);
                scope.useFrameInstallation();
                f.tick();
                var claim = Objects.requireNonNull(scope.claimReadyInstallation());
                assertThrows(IllegalStateException.class,
                    () -> claim.complete(ClientDemandCoordinator.InstallationResult.INSTALLED));
                var taskLease = claim.takeLease();
                taskLease.close();
                claim.complete(ClientDemandCoordinator.InstallationResult.REJECTED);
                assertNotNull(scope.failure());
                assertEquals("Model installation was rejected", scope.failure().getMessage());
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
            assertEquals(0, f.onlineCalls);
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
            f.coordinator.disposition(f.selections.owner, 1, true, false);
            f.ready(A);
            assertTrue(page.feedback(A).waiting());
            assertNull(card.lease);
            f.coordinator.register(player);
            f.tick();
            assertNotNull(player.lease);
            f.selections.authority = f.selections.installed = A;
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
    void hotClickHasNoFeedbackFlashAndStillSends() throws Exception {
        try (var f = new Fixture()) {
            f.selections.installed = A;
            f.coordinator.select(A);
            assertFalse(f.coordinator.feedback().waiting());
            f.tick();
            assertEquals(List.of(A), f.selections.sent);
            assertEquals(0, f.starts.size());
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
            assertEquals(0, f.cache.readyCount());
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
    void currentAssociationRejectsAbaOldConnectionZeroAndLateDisposition() throws Exception {
        try (var f = new Fixture()) {
            f.coordinator.select(A);
            f.tick();
            f.coordinator.select(B);
            f.at(701);
            f.coordinator.select(A);
            f.at(1402);
            assertEquals(List.of(1L, 2L, 3L), f.selections.ids);
            f.coordinator.disposition(f.selections.owner, 1, false, true);
            f.coordinator.disposition(new Object(), 3, false, true);
            f.coordinator.disposition(f.selections.owner, 0, false, true);
            f.coordinator.disposition(f.selections.owner, 3, true, false);
            assertTrue(f.coordinator.feedback().waiting());
            f.coordinator.disposition(f.selections.owner, 3, false, true);
            assertFalse(f.coordinator.feedback().waiting());
            assertEquals(1, f.selections.failures.size());
            f.coordinator.disposition(f.selections.owner, 3, false, true);
            assertEquals(1, f.selections.failures.size());
            f.selections.owner = new Object();
            f.tick();
            f.coordinator.select(B);
            f.tick();
            assertEquals(1L, f.selections.ids.get(3));
        }
    }

    @Test
    void authoritativeFailureBelongsToItsCardAfterAnEarlierSelectionEnds() throws Exception {
        try (var f = new Fixture()) {
            f.tick();
            f.coordinator.select(A);
            f.tick();
            f.coordinator.disposition(f.selections.owner, 1, false, true);
            assertNotNull(f.coordinator.feedback(A).failure());
            f.selections.authority = B;
            f.tick();
            assertTrue(f.coordinator.feedback(B).waiting());
            assertNull(f.coordinator.feedback(A).failure());
            f.at(ClientDemandCoordinator.WAIT_MILLIS);
            assertFalse(f.coordinator.feedback(B).waiting());
            assertNotNull(f.coordinator.feedback(B).failure());
            assertNull(f.coordinator.feedback(A).failure());
            assertEquals(B, f.selections.authority);
        }
    }

    @Test
    void unsignedIdentifiersExhaustWithoutWrapping() throws Exception {
        try (var f = new Fixture()) {
            f.tick();
            var next = ClientDemandCoordinator.class.getDeclaredField("nextIntentId");
            next.setAccessible(true);
            next.setLong(f.coordinator, Long.MIN_VALUE);
            f.coordinator.select(A);
            f.tick();
            assertEquals(Long.MIN_VALUE, f.selections.ids.get(0));
            next.setLong(f.coordinator, -1L);
            f.coordinator.select(B);
            f.at(701);
            assertEquals(-1L, f.selections.ids.get(1));
            f.coordinator.select(C);
            f.at(1402);
            assertEquals(2, f.selections.ids.size());
            assertFalse(f.coordinator.feedback().waiting());
            assertNotNull(f.coordinator.feedback().failure());
        }
    }

    @Test
    void duplicateClickDoesNotRenewDeadlineAndLateAuthorityUsesIndependentEntity() throws Exception {
        try (var f = new Fixture()) {
            var real = new Receiver(f, A, ModelResourcePurpose.FULL);
            var scope = f.coordinator.register(real);
            f.tick();
            f.ready(A);
            f.coordinator.select(B);
            f.at(1000);
            f.coordinator.select(B);
            f.at(ClientDemandCoordinator.WAIT_MILLIS);
            assertFalse(f.coordinator.feedback().waiting());
            assertEquals(1, f.selections.failures.size());
            assertNotNull(real.lease);
            assertInstanceOf(AcquireResult.Ready.class, real.lease.poll());
            assertEquals(0, f.interests(B));
            real.target = B;
            f.tick();
            f.ready(B);
            f.selections.authority = B;
            f.selections.installed = B;
            f.tick();
            assertFalse(f.coordinator.feedback().waiting());
            assertEquals(1, f.selections.failures.size());
            scope.close();
            real.close();
        }
    }

    @Test
    void failureCannotSuppressSelectionSendOrRevokeAnIndependentAssignment() throws Exception {
        try (var f = new Fixture()) {
            var real = new Receiver(f, A, ModelResourcePurpose.FULL);
            var scope = f.coordinator.register(real);
            f.tick();
            f.ready(A);
            f.denied.add(B.modelId());
            f.coordinator.select(B);
            f.tick();
            assertEquals(List.of(B), f.selections.sent);
            assertEquals(1, f.selections.failures.size());
            assertInstanceOf(AcquireResult.Ready.class, real.lease.poll());
            scope.close();
            real.close();
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
            assertEquals(List.of(A), f.selections.sent);
        }
    }

    @Test
    void zeroOnceAndRepeatedViewReadsKeepTheCompleteEffectTrace() throws Exception {
        var expected = readTrace(0);
        assertEquals(expected, readTrace(1));
        assertEquals(expected, readTrace(8));
        assertTrue(expected.stream().anyMatch(event -> event.contains("work-cancel:1")));
        assertTrue(expected.stream().anyMatch(event -> event.contains("work-terminal:2:Ready")));
        assertTrue(expected.stream().anyMatch(event -> event.contains("work-terminal:3:Failed")));
        assertTrue(expected.contains("1503:selection-terminal:trace resource failure"));
        assertTrue(expected.contains("31600:selection-terminal:Model selection installation timed out"));
        assertTrue(expected.contains("32010:probe-cancel"));
        assertTrue(expected.contains("32020:release:3"));
        assertEquals(3, expected.stream().filter(event -> event.contains(":send:")).count());
    }

    private List<String> readTrace(int reads) throws Exception {
        var f = new Fixture();
        try (f; var player = new Receiver(f, B, ModelResourcePurpose.FULL);
            var lateCard = new Receiver(f, C, ModelResourcePurpose.GUI)) {
            var page = f.coordinator.openPage("trace-list");
            page.pointer(A);
            traceTick(f, 0, reads, null);
            traceTick(f, 301, reads, null);
            f.clock.set(350);
            page.pointer(B);
            traceTick(f, 651, reads, null);
            traceTick(f, 700, reads, null);
            f.clock.set(710);
            f.ready(B);
            var playerScope = f.coordinator.register(player);
            f.clock.set(720);
            f.coordinator.select(B);
            traceTick(f, 720, reads, playerScope);
            f.ready(B);
            assertNotNull(player.lease);
            f.selections.authority = B;
            f.selections.installed = B;
            traceTick(f, 730, reads, playerScope);
            page.close();

            f.clock.set(800);
            f.coordinator.select(C);
            traceTick(f, 800, reads, playerScope);
            traceTick(f, 1500, reads, playerScope);
            traceTick(f, 1501, reads, playerScope);
            f.clock.set(1502);
            f.pending.get(C.modelId()).complete(new ModelRenderTargetLoader.LoadResult.Failed(new ResourceFailure(
                ResourceFailure.Kind.DETERMINISTIC, new IllegalArgumentException("trace resource failure"))));
            traceTick(f, 1503, reads, playerScope);

            f.clock.set(1600);
            f.coordinator.select(A);
            traceTick(f, 1600, reads, playerScope);
            f.clock.set(31000);
            f.coordinator.select(A);
            traceTick(f, 31000, reads, playerScope);
            traceTick(f, 31599, reads, playerScope);
            traceTick(f, 31600, reads, playerScope);
            traceTick(f, 31601, reads, playerScope);
            assertEquals(2, f.selections.failures.size());

            f.cachedProbe = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean interrupt) {
                    f.event("probe-cancel");
                    return false;
                }
            };
            f.clock.set(32000);
            var latePage = f.coordinator.openPage("late-card");
            var lateScope = f.coordinator.register(lateCard);
            latePage.entity(lateScope);
            traceTick(f, 32000, reads, lateScope);
            f.clock.set(32005);
            var late = f.start(lateCard.assignment().request(), WorkPriority.BACKGROUND);
            f.clock.set(32010);
            latePage.close();
            // The producer can complete after retirement; neither a view read nor a coordinator tick follows.
            f.clock.set(32020);
            f.complete(C);
            f.cache.tick();
            assertTrue(f.cachedProbe.complete(Optional.of(late)));
            assertEquals(0, f.interests(C));
            f.cache.tick();
            f.clock.set(33000);
            playerScope.close();
            player.close();
            f.coordinator.close();
            f.cache.tick();
            assertEquals(0, f.cache.loadingCount());
            assertEquals(0, f.cache.readyCount());
            assertTrue(f.interests.values().stream().allMatch(count -> count == 0));
        }
        return List.copyOf(f.events);
    }

    private void traceTick(Fixture f, long at, int reads, ClientDemandCoordinator.EntityScope scope) {
        long before = f.clock.get();
        for (int frame = 1; reads > 1 && frame <= 4; frame++) {
            f.clock.set(before + (at - before) * frame / 4);
            readViews(f, reads, scope);
        }
        f.at(at);
        readViews(f, reads, scope);
    }

    private void readViews(Fixture f, int reads, ClientDemandCoordinator.EntityScope scope) {
        var before = List.copyOf(f.events);
        for (int i = 0; i < reads; i++) {
            f.coordinator.feedback();
            f.coordinator.canOpenTextures();
            if (scope != null) {
                scope.failure();
                scope.hasUnfinishedWork();
                scope.isClosed();
            }
        }
        assertEquals(before, f.events, "view reads must not produce effects between owner ticks");
    }

    @Test
    void switchDwellIsStrictAndCompleteLocalWorkBypassesIt() throws Exception {
        try (var f = new Fixture()) {
            f.select(A, false);
            f.clock.set(100);
            f.coordinator.select(B);
            f.tick();
            f.at(800);
            assertEquals(List.of(A), f.selections.sent);
            f.at(801);
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
            assertEquals(0, f.onlineCalls);
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
    void pageAndPointerGatesRunTogetherAndAdmittedHoverKeepsThePageNetworkGate() throws Exception {
        try (var f = new Fixture()) {
            var previous = f.coordinator.openPage("first");
            f.clock.set(100);
            var page = f.coordinator.replacePage(previous, "second");
            f.clock.set(300);
            page.pointer(A);
            f.at(401);
            assertEquals(0, f.offlineCalls, "pointer has not qualified");
            f.at(600);
            assertEquals(1, f.offlineCalls);
            assertEquals(0, f.onlineCalls);
            page.pointer(null);
            f.at(800);
            assertEquals(1, f.onlineCalls);
            f.at(801);
            assertEquals(1, f.onlineCalls, "accepted hover continues after pointer exit");
            assertEquals(1, f.offlineCalls, "network admission reuses the completed offline probe");
            page.close();
            assertEquals(0, f.interests(A));
        }
    }

    @Test
    void exitingBeforePageAdmissionLeavesNoDeferredHover() throws Exception {
        try (var f = new Fixture(); var card = new Receiver(f, A, ModelResourcePurpose.GUI)) {
            var previous = f.coordinator.openPage("first");
            f.clock.set(100);
            var page = f.coordinator.replacePage(previous, "second");
            page.card(card, A);
            page.pointer(A);
            f.at(399);
            assertNull(card.scope);
            assertEquals(0, f.offlineCalls);
            page.pointer(null);
            f.at(1000);
            assertTrue(f.starts.isEmpty());
            assertEquals(0, f.offlineCalls);
            assertNull(card.scope);
        }
    }

    @Test
    void lateOfflineMissUsesTheAlreadyOpenNetworkGate() throws Exception {
        try (var f = new Fixture()) {
            f.offlineProbe = new CompletableFuture<>();
            var previous = f.coordinator.openPage("first");
            f.clock.set(100);
            var page = f.coordinator.replacePage(previous, "second");
            page.pointer(A);
            f.at(400);
            assertEquals(1, f.offlineCalls);
            f.at(401);
            f.at(801);
            assertFalse(f.offlineProbe.isCancelled());
            assertEquals(1, f.offlineCalls);
            assertEquals(0, f.onlineCalls);
            f.clock.set(1000);
            f.offlineProbe.complete(Optional.empty());
            f.tick();
            assertEquals(1, f.offlineCalls);
            assertEquals(1, f.onlineCalls, "900 ms miss does not start another dwell interval");
        }
    }

    @Test
    void rejectedFullSelectionLeavesTheIndependentGuiPreparationUntilThePageCloses() throws Exception {
        try (var f = new Fixture()) {
            var page = f.coordinator.openPage("list");
            page.pointer(A);
            f.select(A, false);
            var full = f.pending.get(A.modelId());
            f.at(301);
            var gui = f.pending.get(A.modelId());
            assertNotSame(full, gui);
            f.coordinator.disposition(f.selections.owner, 1, false, true);
            f.tick();
            assertTrue(full.isCancelled());
            assertFalse(gui.isCancelled());
            assertEquals(1, f.interests(A));
            page.close();
            f.cache.tick();
            assertTrue(gui.isCancelled());
            assertEquals(0, f.interests(A));
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
            f.tick();
            assertFalse(f.coordinator.feedback().waiting());
            page.close();
            assertNull(card.lease);
            assertInstanceOf(AcquireResult.Ready.class, player.lease.poll());
        }
    }

    @Test
    void unrelatedCatalogGrowthDoesNotIncreaseActiveTraversal() throws Exception {
        try (var f = new Fixture()) {
            var receiver = new Receiver(f, A, ModelResourcePurpose.FULL);
            var scope = f.coordinator.register(receiver);
            f.tick();
            f.contentReads = 0;
            f.tick();
            int before = f.contentReads;
            for (int i = 10; i < 250; i++)
                f.contents.put(hash(i), content(hash(i)));
            f.contentReads = 0;
            f.tick();
            assertEquals(before, f.contentReads);
            scope.close();
            receiver.close();
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
        final Map<Hash256, ModelContent> contents = new HashMap<>();
        final Map<Hash256, CompletableFuture<ModelRenderTargetLoader.LoadResult>> pending = new HashMap<>();
        final List<Hash256> starts = new ArrayList<>();
        final Set<Hash256> denied = new HashSet<>();
        final Map<Hash256, Integer> interests = new HashMap<>();
        final ClientDemandCoordinator coordinator =
            new ClientDemandCoordinator(clock::get, this, selections, () -> this.eager);
        boolean eager;
        final List<ResourceRequest> requests = new ArrayList<>();
        final Map<Hash256, WorkPriority> priorities = new HashMap<>();
        int cachedCalls;
        int offlineCalls;
        boolean local;
        int onlineCalls;
        int contentReads;
        CompletableFuture<Optional<ResourceLease>> cachedProbe;
        CompletableFuture<Optional<ResourceLease>> offlineProbe;

        Fixture() {
            selections.trace = this::event;
        }

        void event(String effect) {
            events.add(clock.get() + ":" + effect);
        }

        int marker(Hash256 id) {
            return Byte.toUnsignedInt(id.bytes()[0]);
        }

        void tick() {
            cache.tick();
            coordinator.tick();
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
            var value = (ModelRenderTarget)UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
            future.complete(new ModelRenderTargetLoader.LoadResult.Ready(
                ModelRenderTargetLoader.ModelCandidate.testing(value)));
        }

        int interests(ClientDemandCoordinator.Target target) {
            return interests.getOrDefault(target.modelId(), 0);
        }

        public ModelContent content(Hash256 id) {
            contentReads++;
            return contents.computeIfAbsent(id, ClientDemandCoordinatorTest::content);
        }

        public ResourceRequest resourceRequest(Hash256 id, String target, String texture, ModelResourcePurpose purpose,
            Class<?> subject) {
            return new ResourceRequest(id, target, texture, PROFILE, ExpressionBinding.standalone(),
                purpose);
        }

        public ResourceRequest defaultResourceRequest(String target, ModelResourcePurpose purpose, Class<?> subject) {
            return resourceRequest(hash(0), target, "", purpose, subject);
        }

        public boolean permitsAcquisition(ResourceRequest request) {
            return !denied.contains(request.modelId());
        }

        RenderTargetKey key(ResourceRequest request, boolean full) {
            return new RenderTargetKey(request.targetId(), request.requestedTexture(), PROFILE,
                request.expressionBinding(), full ? ModelResourcePurpose.FULL : request.purpose());
        }

        public Optional<ResourceLease> findReady(ResourceRequest request) {
            if (!permitsAcquisition(request))
                return Optional.empty();
            return cache.findReady(request, content(request.modelId()), key(request, false),
                (identity, key, asked) -> true).map(lease -> wrap(request, lease));
        }

        public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request, WorkPriority priority) {
            cachedCalls++;
            return cachedProbe == null ? CompletableFuture.completedFuture(Optional.empty()) : cachedProbe;
        }

        public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request, WorkPriority priority) {
            offlineCalls++;
            if (offlineProbe != null)
                return offlineProbe;
            return CompletableFuture.completedFuture(local ? Optional.of(start(request, priority)) : Optional.empty());
        }

        public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
            onlineCalls++;
            return start(request, priority);
        }

        private ResourceLease start(ResourceRequest request, WorkPriority priority) {
            priorities.put(request.modelId(), priority);
            return wrap(request, cache.getOrStart(request, content(request.modelId()), key(request, false), (ignored, dispatch) -> {
                requests.add(request);
                starts.add(request.modelId());
                event("start:" + marker(request.modelId()));
                var future = new CompletableFuture<ModelRenderTargetLoader.LoadResult>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                        event("work-cancel:" + marker(request.modelId()));
                        return super.cancel(interrupt);
                    }
                };
                future.whenComplete((outcome, error) -> event("work-terminal:" + marker(request.modelId()) + ":"
                    + (error == null ? outcome.getClass().getSimpleName() : error.getClass().getSimpleName())));
                pending.put(request.modelId(), future);
                return future;
            }, (identity, key, asked) -> true, selections.owner, priority));
        }

        ResourceLease wrap(ResourceRequest request, ResourceLease inner) {
            interests.merge(request.modelId(), 1, Integer::sum);
            event("acquire:" + marker(request.modelId()));
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
                        interests.merge(request.modelId(), -1, Integer::sum);
                        event("release:" + marker(request.modelId()));
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
        ClientDemandCoordinator.Target authority = new ClientDemandCoordinator.Target(null, "");
        ClientDemandCoordinator.Target installed;
        final List<ClientDemandCoordinator.Target> sent = new ArrayList<>();
        final List<Long> ids = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();

        public ClientDemandCoordinator.SelectionContext context() {
            return new ClientDemandCoordinator.SelectionContext(owner, true, true);
        }

        public ClientDemandCoordinator.Target authority() {
            return authority;
        }

        public boolean installed(ClientDemandCoordinator.Target target) {
            return target.equals(installed);
        }

        public void send(ClientDemandCoordinator.Target target, long id) {
            sent.add(target);
            ids.add(id);
            trace.accept("send:"
                + (target.modelId() == null ? "default" : Byte.toUnsignedInt(target.modelId().bytes()[0])) + ":" + id);
        }

        public void reportFailure(Throwable failure) {
            failures.add(failure);
            trace.accept("selection-terminal:" + failure.getMessage());
        }
    }

    private static final class Receiver
        implements ClientDemandCoordinator.Installation, ClientDemandCoordinator.CardDisplay, AutoCloseable {
        ClientDemandCoordinator.EntityScope scope;
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
            fixture.event("install:" + fixture.marker(request.modelId()));
            return ClientDemandCoordinator.InstallationResult.INSTALLED;
        }

        public void close() {
            if (lease != null) {
                lease.close();
                lease = null;
            }
        }
    }

    static ClientDemandCoordinator.Target target(int marker) {
        return new ClientDemandCoordinator.Target(hash(marker), "main");
    }

    static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    static ModelContent content(Hash256 id) {
        return content(id, hash(100 + Byte.toUnsignedInt(id.bytes()[0])));
    }

    static ModelContent content(Hash256 id, Hash256 containerId) {
        try {
            var representation =
                (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
            var field = ModelRepresentation.class.getDeclaredField("identity");
            field.setAccessible(true);
            field.set(representation, new ModelFileIdentity(id, containerId));
            return new ModelContent() {
                public Hash256 modelId() {
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
