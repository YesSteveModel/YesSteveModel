package com.elfmcys.ysm.capability;

import static com.elfmcys.ysm.expression.TestRoamingPayloads.named;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.fromMap;
import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.named;
import static org.junit.jupiter.api.Assertions.fail;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.player.LocalPlayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.extension.ExtendWith(com.elfmcys.ysm.testutil.NativeLibraryExtension.class)
final class ClientRoamingSessionTest {
    @BeforeAll
    static void initializeMinecraftEntityClasses() throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion();
        var gate = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        gate.setAccessible(true);
        boolean previous = gate.getBoolean(null);
        gate.setBoolean(null, true);
        try {
            net.minecraft.core.registries.BuiltInRegistries.REGISTRY.size();
            Class.forName(LocalPlayer.class.getName(), true, ClientRoamingSessionTest.class.getClassLoader());
        } finally {
            gate.setBoolean(null, previous);
        }
    }

    @Test
    void completeSelectionClearsMissingAndUnknownFieldsAreFiltered() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0, 1}, new float[] {1, 9}), null, budget);
            installRemote(session, runtime, animationStore);
            assertEquals(1F, named(runtime.roamingState().orElseThrow()).get("known"));
            session.acceptSelection(runtime, remote(new short[0], new float[0]), null, budget);
            session.drain(runtime, ignored -> fail());
            assertEquals(0F, named(runtime.roamingState().orElseThrow()).get("known"));
            var reordered = new CommittedSelection(published(7), 1, null,
                new com.elfmcys.ysm.network.protocol.RoamingDictionary(new String[] {"unknown", "known"}),
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0, 1}, new float[] {9, 6}), "", "");
            session.acceptSelection(runtime, reordered, null, budget);
            session.drain(runtime, ignored -> fail());
            assertEquals(6F, named(runtime.roamingState().orElseThrow()).get("known"));
        }
    }

    @Test
    void invalidBatchCannotModifyEitherProjectionOrInstalledRuntime() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), null, budget);
            installRemote(session, runtime, animationStore);
            assertThrows(IllegalArgumentException.class,
                () -> session.updateRemote(runtime, com.elfmcys.ysm.network.protocol.SelectionCodec.read(
                    com.elfmcys.ysm.proto.network.RoamingStateProto.newBuilder().addIds(0, 2).addValues(3F, 4F).build(),
                    session.remoteSelection().dictionary())));
            assertEquals(1F, named(runtime.roamingState().orElseThrow()).get("known"));
            assertEquals(1F, session.remoteSelection().roaming().snapshot().values()[0]);
        }
    }

    @Test
    void fullWithNoMappedFieldsStillClearsTheWholeLocalDomain() {
        try (var runtime = new EntityExpressionRuntime(); var animationStore =
            TestExpressionModules.store(TestExpressionModules.create(Set.of("local_only")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), null, budget);
            installRemote(session, runtime, animationStore);
            var state = runtime.roamingState().orElseThrow();
            state.write((short)0, 9);
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {2}), null, budget);
            session.drain(runtime, ignored -> fail());
            assertEquals(0F, state.read((short)0));
        }
    }

    @Test
    void stateOnlyInputsCoalesceIntoTheLatestInstallationBaseline() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[0], new float[0]), null, budget);
            session.updateRemote(runtime,
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {2}));
            session.updateRemote(runtime,
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0, 1}, new float[] {3, 4}));
            installRemote(session, runtime, animationStore);
            assertEquals(3F, named(runtime.roamingState().orElseThrow()).get("known"));
        }
    }

    @Test
    void installationReadsInputsAtCommitAndFailedCandidatesDoNotConsumeEvents() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), null, budget);
            session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(7), ignored -> fail());
            try (var failed = runtime.prepare(animationStore, TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.CONSUMER)) {
                var publish = session.prepare(id(7), failed);
                assertThrows(IllegalStateException.class, () -> runtime.install(failed, () -> {
                    publish.run();
                    throw new IllegalStateException("publication failed");
                }));
            }
            assertEquals(1, budget.pendingSyncs());
            try (var prepared = runtime.prepare(animationStore, TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.CONSUMER)) {
                var publish = session.prepare(id(7), prepared);
                session.updateRemote(runtime,
                    new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {3}));
                runtime.install(prepared, publish);
            }
            var seen = new java.util.ArrayList<Float>();
            session.drain(runtime, ignored -> seen.add(named(runtime.roamingState().orElseThrow()).get("known")));
            assertEquals(java.util.List.of(1F), seen);
            assertEquals(3F, named(runtime.roamingState().orElseThrow()).get("known"));
            assertEquals(0, budget.pendingSyncs());
        }
    }

    @Test
    void deltasBeforeTheFirstEventAndDuringPreparationReachTheInstalledBaseline() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), null, budget);
            try (var prepared = runtime.prepare(animationStore, TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.CONSUMER)) {
                var publish = session.prepare(id(7), prepared);
                session.updateRemote(runtime,
                    new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {4}));
                runtime.install(prepared, publish);
                assertEquals(4F, named(runtime.roamingState().orElseThrow()).get("known"));
            }
        }
    }

    @Test
    void pendingEventsRespectBothCapacityBoundsAndAllCleanupReturnsCapacity() {
        try (var runtime = new EntityExpressionRuntime()) {
            var budget = new RemoteSyncBudget();
            var sessions = new java.util.ArrayList<ClientRoamingSession>();
            for (int index = 0; index < 65; index++) {
                var session = new ClientRoamingSession(null, false, () -> {
                });
                sessions.add(session);
                session.acceptSelection(runtime, remote(new short[0], new float[0]), null, budget);
                for (int event = 0; event < 64; event++)
                    session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(event),
                        ignored -> fail());
            }
            assertEquals(4096, budget.pendingSyncs());
            assertEquals(64, budget.droppedSyncs());
            sessions.get(0).acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(),
                ignored -> fail());
            assertEquals(65, budget.droppedSyncs());
            sessions.get(0).updateRemote(runtime,
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {9}));
            assertEquals(9F, sessions.get(0).remoteSelection().roaming().snapshot().values()[0]);
            budget.close();
            sessions.forEach(ClientRoamingSession::clearRemote);
            assertEquals(0, budget.pendingSyncs());
        }
    }

    @Test
    void invalidatedBusinessAndReplacedSelectionRejectPreparedCandidates() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var operation = new java.util.concurrent.atomic.AtomicReference<Object>(new Object());
            var session = new ClientRoamingSession(null, false, () -> {
            }, operation::get);
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), operation.get(), budget);
            try (var prepared = runtime.prepare(animationStore, TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.CONSUMER)) {
                var publish = session.prepare(id(7), prepared);
                budget.close();
                session.clearRemote();
                assertThrows(java.util.concurrent.CancellationException.class,
                    () -> runtime.install(prepared, publish));
            }
            var nextBudget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), operation.get(), nextBudget);
            session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(), ignored -> fail());
            try (var prepared = runtime.prepare(animationStore, TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.CONSUMER)) {
                var publish = session.prepare(id(7), prepared);
                operation.set(new Object());
                session.acceptSelection(runtime, remote(new short[] {0}, new float[] {8}), operation.get(), nextBudget);
                assertEquals(0, nextBudget.pendingSyncs());
                assertThrows(java.util.concurrent.CancellationException.class,
                    () -> runtime.install(prepared, publish));
            }
            installRemote(session, runtime, animationStore);
            assertEquals(8F, named(runtime.roamingState().orElseThrow()).get("known"));
        }
    }

    @Test
    void replayInputsCannotOvertakeTheCurrentEventAndRetirementDropsTheRemainder() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), null, budget);
            session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(1), ignored -> fail());
            session.updateRemote(runtime,
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {2}));
            session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(2), ignored -> fail());
            installRemote(session, runtime, animationStore);
            var order = new java.util.ArrayList<Float>();
            session.drain(runtime, arguments -> {
                assertEquals(1, budget.pendingSyncs());
                order.add(named(runtime.roamingState().orElseThrow()).get("known"));
                session.updateRemote(runtime,
                    new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {3}));
                session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(3), ignored -> fail());
                assertEquals(1F, named(runtime.roamingState().orElseThrow()).get("known"));
                runtime.invalidate();
            });
            assertEquals(java.util.List.of(1F), order);
            assertEquals(0, budget.pendingSyncs());
            installRemote(session, runtime, animationStore);
            session.drain(runtime, ignored -> fail("retired events must not replay"));
            assertEquals(3F, named(runtime.roamingState().orElseThrow()).get("known"));
        }
    }

    @Test
    void eventFailureAndMissingHandlerAreTerminalAndReinstallationDoesNotRepeatThem() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), null, budget);
            session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(), ignored -> fail());
            session.updateRemote(runtime,
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {4}));
            session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(), ignored -> fail());
            installRemote(session, runtime, animationStore);
            var calls = new AtomicInteger();
            session.drain(runtime, arguments -> {
                assertEquals(0, arguments.size());
                if (calls.getAndIncrement() == 0)
                    throw new IllegalArgumentException("handler failed");
                runtime.action(
                    new EntityExpressionRuntime.InvocationInputs(
                        new com.elfmcys.ysm.expression.host.InvocationInputs<net.minecraft.world.entity.Entity>() {}),
                    evaluation -> {
                        evaluation.dispatchSync(arguments);
                        return null;
                    });
            });
            assertEquals(2, calls.get());
            assertEquals(4F, named(runtime.roamingState().orElseThrow()).get("known"));
            assertEquals(0, budget.pendingSyncs());
            installRemote(session, runtime, animationStore);
            session.drain(runtime, ignored -> fail("consumed events must not replay"));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void inputsArrivingDuringReplayAppendAfterTheAlreadyAcceptedTail() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {1}), null, budget);
            session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(1), ignored -> fail());
            session.updateRemote(runtime,
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {2}));
            session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(2), ignored -> fail());
            installRemote(session, runtime, animationStore);
            var seen = new java.util.ArrayList<Float>();
            session.drain(runtime, arguments -> {
                seen.add(named(runtime.roamingState().orElseThrow()).get("known"));
                if (arguments.getFloat(0) == 1) {
                    session.updateRemote(runtime,
                        new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {3}));
                    session.acceptSync(runtime, com.elfmcys.ysm.expression.host.EventArguments.sync(3),
                        ignored -> fail("recursive drain must wait for the current event"));
                    assertEquals(1F, named(runtime.roamingState().orElseThrow()).get("known"));
                }
            });
            assertEquals(java.util.List.of(1F, 2F, 3F), seen);
            assertEquals(0, budget.pendingSyncs());
        }
    }

    @Test
    void retainedFallbackCannotConsumeAnotherSelectionsProjection() {
        try (var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var session = new ClientRoamingSession(null, false, () -> {
            });
            var budget = new RemoteSyncBudget();
            try (var prepared = runtime.prepare(animationStore, TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.CONSUMER)) {
                runtime.install(prepared, session.prepare(id(8), prepared, published(8)));
            }
            session.acceptSelection(runtime, remote(new short[] {0}, new float[] {9}), null, budget);
            session.updateRemote(runtime,
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {10}));
            assertEquals(0F, named(runtime.roamingState().orElseThrow()).get("known"));
            installRemote(session, runtime, animationStore);
            assertEquals(10F, named(runtime.roamingState().orElseThrow()).get("known"));
        }
    }

    private static CommittedSelection remote(short[] ids, float[] values) {
        return new CommittedSelection(published(7), 1, null,
            new com.elfmcys.ysm.network.protocol.RoamingDictionary(new String[] {"known", "unknown"}),
            new com.elfmcys.ysm.network.protocol.RoamingValues(ids, values), "", "");
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;

    @Test
    void selfFullAndDeltaNeverRestoreOrClearTheLocalProducer() throws Exception {
        try (var fixture = new LocalFixture(directory); var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var scope = fixture.preferences.select(fixture.player, fixture.authority, published(7), 1, payload(2));
            install(fixture.session, runtime, animationStore, id(7));
            fixture.session.acceptSelection(runtime, remote(new short[] {0}, new float[] {99}), null, fixture.budget);
            fixture.session.updateRemote(runtime,
                new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {98}));
            assertEquals(Map.of("known", 2F), named(fixture.preferences.snapshot(scope)));
            assertEquals(2F, named(runtime.snapshot().orElseThrow()).get("known"));
        }
    }

    @Test
    void oldSelectionAndInstallationCannotPublishAfterAToBToA() throws Exception {
        try (var fixture = new LocalFixture(directory); var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            fixture.preferences.select(fixture.player, fixture.authority, published(7), 1, payload(1));
            install(fixture.session, runtime, animationStore, id(7));
            var old = fixture.session.prepareLocalProjection();
            fixture.preferences.select(fixture.player, fixture.authority, published(8), 2, payload(2));
            var current = fixture.preferences.select(fixture.player, fixture.authority, published(7), 3, payload(3));
            install(fixture.session, runtime, animationStore, id(7));
            assertTrue(old.adopt(payload(99).toPayload(animationStore.roamingFields()), true));
            assertEquals(3F, named(fixture.preferences.snapshot(current)).get("known"));
            assertEquals(3F, named(runtime.snapshot().orElseThrow()).get("known"));
            var projection = fixture.session.prepareLocalProjection();
            runtime.roamingState().orElseThrow().write((short)animationStore.roamingFields().indexOf("known"), 4);
            assertTrue(projection.adopt(payload(4).toPayload(animationStore.roamingFields()), true));
            assertEquals(4F, named(fixture.preferences.snapshot(current)).get("known"));
            assertEquals(4F,
                named(com.elfmcys.ysm.model.storage.PreferenceTestDriver
                    .await(fixture.store, fixture.store.readRoaming(fixture.player, published(7), Set.of("known")))
                    .rows().get(0)).get("known"));
        }
    }

    @Test
    void appliedClearRetiresTheOldProducerAndKeepsNewWritesAfterTheClear() throws Exception {
        try (var fixture = new LocalFixture(directory); var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            fixture.preferences.select(fixture.player, fixture.authority, published(7), 1, payload(1));
            install(fixture.session, runtime, animationStore, id(7));
            var old = fixture.session.prepareLocalProjection();
            var cleared = new java.util.concurrent.CompletableFuture<
                com.elfmcys.ysm.natives.storage.StorageResult<com.elfmcys.ysm.model.storage.NamedRoamingSnapshot>>();
            fixture.preferences.select(fixture.player, fixture.authority, published(7), 2, fromMap(Map.of()));
            fixture.preferences.clearStored(fixture.player, published(7), cleared::complete);
            assertTrue(com.elfmcys.ysm.model.storage.PreferenceTestDriver.await(fixture.store, cleared).committed());
            assertTrue(old.adopt(payload(99).toPayload(animationStore.roamingFields()), true));
            install(fixture.session, runtime, animationStore, id(7));
            assertEquals(0F, named(runtime.roamingState().orElseThrow()).get("known"));
            assertTrue(fixture.session.snapshot(runtime, published(7)).available());

            runtime.roamingState().orElseThrow().write((short)animationStore.roamingFields().indexOf("known"), 2);
            assertTrue(fixture.session.prepareLocalProjection()
                .adopt(payload(2).toPayload(animationStore.roamingFields()), true));
            assertTrue(fixture.session.snapshot(runtime, published(7)).available());
            assertEquals(Map.of("known", 2F),
                named(com.elfmcys.ysm.model.storage.PreferenceTestDriver
                    .await(fixture.store, fixture.store.readRoaming(fixture.player, published(7), Set.of("known")))
                    .rows().get(0)));

            fixture.preferences.select(fixture.player, fixture.authority, published(7), 3, payload(3));
            install(fixture.session, runtime, animationStore, id(7));
            assertEquals(3F,
                named(fixture.session.snapshot(runtime, published(7)).values(), animationStore.roamingFields())
                    .get("known"));
        }
    }

    @Test
    void temporaryFallbackCannotAcquirePreferenceQualificationFromMatchingContent() throws Exception {
        try (var fixture = new LocalFixture(directory); var runtime = new EntityExpressionRuntime();
            var animationStore = TestExpressionModules.store(TestExpressionModules.create(Set.of("known")), Map.of())) {
            var current = fixture.preferences.select(fixture.player, fixture.authority,
                com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, 1, payload(1));
            install(fixture.session, runtime, animationStore, id(7));
            assertFalse(fixture.session.snapshot(runtime, published(7)).available());
            assertTrue(fixture.session.prepareLocalProjection()
                .adopt(payload(99).toPayload(animationStore.roamingFields()), true));
            assertEquals(1F, named(fixture.preferences.snapshot(current)).get("known"));
        }
    }

    private static com.elfmcys.ysm.model.storage.NamedRoamingSnapshot payload(float value) {
        return fromMap(Map.of("known", value));
    }

    private static com.elfmcys.ysm.model.domain.PublishedModelId published(int marker) {
        return com.elfmcys.ysm.model.domain.PublishedModelId.model(id(marker));
    }

    private static void install(ClientRoamingSession session, EntityExpressionRuntime runtime,
        com.elfmcys.ysm.model.resource.client.AnimationStore animationStore, ModelId key) {
        try (var prepared =
            runtime.prepare(animationStore, TestComponentHolders.linkageScope(), dev.semaweave.RoamingRole.PRODUCER)) {
            runtime.install(prepared, session.prepare(key, prepared));
        }
    }

    private static final class LocalFixture implements AutoCloseable {
        final java.util.UUID player = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        final Object authority = new Object();
        final RemoteSyncBudget budget = new RemoteSyncBudget();
        final com.elfmcys.ysm.model.storage.DatabaseWork work = new com.elfmcys.ysm.model.storage.DatabaseWork();
        final com.elfmcys.ysm.model.storage.PlayerPreferences store;
        final com.elfmcys.ysm.model.storage.StorageWriteBuffer writes;
        final com.elfmcys.ysm.model.session.client.PlayerPreferenceSession preferences;
        final ClientRoamingSession session = new ClientRoamingSession(null, true, () -> {
        });

        LocalFixture(java.nio.file.Path directory) {
            store = new com.elfmcys.ysm.model.storage.PlayerPreferences(directory, work);
            writes = store.writes();
            preferences = new com.elfmcys.ysm.model.session.client.PlayerPreferenceSession(store, writes, Runnable::run,
                failure -> {
                    throw new AssertionError(failure);
                }, failure -> {
                    throw new AssertionError(failure);
                });
            session.preferences(preferences);
        }

        public void close() {
            writes.closeAndDrain();
            work.close();
        }
    }

    private static void installRemote(ClientRoamingSession session, EntityExpressionRuntime runtime,
        com.elfmcys.ysm.model.resource.client.AnimationStore animationStore) {
        try (var prepared =
            runtime.prepare(animationStore, TestComponentHolders.linkageScope(), dev.semaweave.RoamingRole.CONSUMER)) {
            runtime.install(prepared, session.prepare(id(7), prepared));
        }
    }

    private static ModelId id(int marker) {
        return ModelId.parse("fixture/model" + marker);
    }
}
