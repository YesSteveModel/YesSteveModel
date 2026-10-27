package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.EvidenceRun;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;
import com.elfmcys.ysm.mock.evidence.ScenarioEvidence;
import com.elfmcys.ysm.model.catalog.ClientCatalogScenarioHarness;
import com.elfmcys.ysm.model.catalog.ReloadStatus;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.RemoteCatalogActivation;
import com.elfmcys.ysm.model.session.client.RemotePublicationScenarioFixture;
import com.elfmcys.ysm.model.session.client.RemotePublicationScenarioFixture.Asset;
import com.elfmcys.ysm.model.session.client.state.ActivationFailure;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.util.ProtoBytes;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelManagementRemotePublicationScenarioTest {
    private static final String SCENARIO_ID = "MMR-DOM-REMOTE-PUB-001";

    @TempDir
    Path temp;

    @Test
    @Tag("model-management-mock")
    void currentRemotePublicationAndEntryOwnersRemainExact() throws Throwable {
        assumeTrue(EvidenceRun.isConfigured(), "Only the explicit mock task writes acceptance evidence");
        var fixture = RemotePublicationScenarioFixture.create(temp.resolve("fixture"));
        var plan = plan(fixture);
        var inputBytes = EvidenceJson.canonicalBytes(plan.input());
        var run = EvidenceRun.openConfigured();
        var identity = run.identity(SCENARIO_ID, List.of("FA-002", "FA-007"),
            List.of("CO-003", "CO-004", "CO-005", "CO-007", "CO-011", "CO-013", "CO-016"),
            EvidenceRun.Radius.DETERMINISTIC_COMPOSITION, true, inputBytes);
        var evidence = run.scenario(identity, inputBytes);

        try {
            execute(fixture, plan, evidence);
            evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS, List.of(
                "Serialized full and compatible delta inputs produced only complete expected lean publications",
                "Active exact, local exact, same-ModelId local, exact cache, and exact server sources preserved their actual representation identities",
                "Unchanged tuples retained Ready while add/failure/replacement remained entry-local",
                "Baseline-incompatible delta preserved prior authority and intrinsic-invalid full/delta closed only their model sessions",
                "Tuple replacement cancelled the old exact owner and its deliberately late completion had no current or cache effect",
                "Disconnect restored the latest local index and reconnect before restore publication prevented stale local visibility"),
                List.of(),
                Map.of("contractViolation", 0L, "unexpectedError", 0L, "expectedBaselineDrift", 1L,
                    "expectedIntrinsicInvalidFull", 1L, "expectedIntrinsicInvalidDelta", 1L, "expectedServerFailure",
                    1L, "expectedLocalAccessFailure", 1L, "expectedCacheAccessFailure", 1L),
                "Deterministic in-process Java composition; it proves domain publication, source, owner, and session-end semantics but not Forge callback or real transport ordering"));
        } catch (Throwable failure) {
            completeFailure(evidence, failure);
            throw failure;
        }
    }

    private void execute(RemotePublicationScenarioFixture fixture, ScenarioPlan plan, ScenarioEvidence evidence)
        throws Exception {
        try (
            var catalogs =
                ClientCatalogScenarioHarness.open(temp.resolve("catalog-owner"), fixture.asset("default").file(),
                    List.of(fixture.asset("active-exact").file(), fixture.asset("same-model-local").file()));
            var store = new RemoteModelStore(temp.resolve("remote-store"))) {
            var manager = catalogs.manager();
            var active = manager.beginRemote();
            require(active.byModelId().containsKey(fixture.asset("active-exact").identity().modelId()),
                "beginRemote must return the previously materialized local catalog");
            catalogs.addSource(fixture.asset("local-exact").file(), "local-exact.mxc");
            require(catalogs.reload().join().status() == ReloadStatus.COMMITTED,
                "local reload must commit while the remote session is active");
            fixture.seed(store, "cache-exact");

            var client = negotiatingClient(CatalogSnapshot.empty());
            var receiver = new SessionCollectionPublication.Receiver(Map.of());
            var initialResult =
                acceptFull(receiver, plan.initial(), null, client, evidence, "initial-full", "connection-A");
            require(initialResult.status() == SessionCollectionPublication.Status.FULL,
                "initial serialized publication must complete");
            requirePublication(initialResult.publication(), plan.initial(), "initial-full");
            client.publishFull(initialResult.publication());
            require(allPending(client.activationSnapshot().orElseThrow()),
                "lean publication must be public before any entry becomes Ready");

            try (var activation =
                new RemoteCatalogActivation(manager::localIndex, active, store, fixture.fetcher(), Runnable::run)) {
                var initialRequests = fixture.sourceRequestCount();
                var initialActivation = activation.activate(client.activationSnapshot().orElseThrow()).join();
                require(fixture.sourceRequestCount() == initialRequests + 1,
                    "only the server-exact entry may request initial metadata");
                requireInitialSources(fixture, initialActivation);
                publish(client, manager, initialActivation);
                record(evidence, "initial-activation", "connection-A", "publication-1", "activation-terminal",
                    Map.of("publication", publication(initialResult.publication()), "projection",
                        projection(initialActivation), "sourceRequests", sourceRequests(fixture)));

                var beforeGrantRequests = fixture.sourceRequestCount();
                var grantResult =
                    acceptDelta(receiver, Wire.grants(2, Set.of(fixture.asset("local-exact").identity().modelId())),
                        client.remoteSnapshot().orElseThrow(), evidence, "authorization-change", "connection-A");
                require(grantResult.status() == SessionCollectionPublication.Status.DELTA,
                    "grant-only delta must be compatible");
                requirePublication(grantResult.publication(), plan.grantChanged(), "authorization-change");
                client.publishPublication(grantResult.publication());
                require(noPending(client.activationSnapshot().orElseThrow()),
                    "unchanged exact tuples must retain terminal activation states");
                var grantActivation = activation.activate(client.activationSnapshot().orElseThrow()).join();
                require(fixture.sourceRequestCount() == beforeGrantRequests,
                    "grant-only delta must not restart unchanged sources");
                require(store.probeMetadata(fixture.asset("cache-exact").identity()).isPresent(),
                    "authorization removal must not roll back committed exact cache content");
                publish(client, manager, grantActivation);
                record(evidence, "authorization-change", "connection-A", "publication-2", "unchanged-tuples",
                    Map.of("publication", publication(grantResult.publication()), "projection",
                        projection(grantActivation), "newSourceRequests", "0", "committedCacheAfterRevoke", "exact"));

                var beforeDrift = client.remoteSnapshot().orElseThrow();
                var driftEntry = entry(fixture.asset("online-add"), "remote/active", CatalogAccess.PUBLIC);
                var drift = acceptDelta(receiver, List.of(Wire.catalogAdd(3, 0, true, driftEntry)), beforeDrift,
                    evidence, "baseline-incompatible-delta", "connection-A");
                require(drift.status() == SessionCollectionPublication.Status.BASELINE_DRIFT,
                    "delta for a conflicting baseline must be classified as drift");
                require(client.remoteSnapshot().orElseThrow().equals(beforeDrift),
                    "baseline drift must leave client authority unchanged");
                record(evidence, "baseline-incompatible-delta", "connection-A", "publication-3", "publication-rejected",
                    Map.of("status", drift.status().name(), "retainedPublication", publication(beforeDrift)));

                var failedEntry =
                    entry(fixture.asset("server-failure"), "remote/server-failure", CatalogAccess.AUTHORIZED);
                var failedResult = acceptDelta(receiver, List.of(Wire.catalogAdd(4, 0, true, failedEntry)), beforeDrift,
                    evidence, "server-fetch-failure", "connection-A");
                require(failedResult.status() == SessionCollectionPublication.Status.DELTA,
                    "server failure entry must first commit as lean publication");
                requirePublication(failedResult.publication(), plan.withServerFailure(), "server-fetch-failure");
                client.publishPublication(failedResult.publication());
                var failedActivation = activation.activate(client.activationSnapshot().orElseThrow()).join();
                var failed = requireFailed(failedActivation, fixture.asset("server-failure").identity().modelId());
                require(failed.failure().kind() == ActivationFailure.Kind.TRANSIENT_ACCESS,
                    "server access failure must remain explicitly retryable");
                require(readyCount(failedActivation) == plan.initial().entries().size(),
                    "one failed entry must not roll back unaffected Ready entries");
                publish(client, manager, failedActivation);
                record(evidence, "server-fetch-failure", "connection-A", "publication-4", "entry-local-failure",
                    Map.of("publication", publication(failedResult.publication()), "projection",
                        projection(failedActivation), "failureKind", failed.failure().kind().name(), "sourceRequests",
                        sourceRequests(fixture)));

                var heldEntry = entry(fixture.asset("replacement-old"), "remote/server", CatalogAccess.PUBLIC);
                var heldResult = acceptDelta(receiver, List.of(Wire.catalogAdd(5, 0, true, heldEntry)),
                    client.remoteSnapshot().orElseThrow(), evidence, "tuple-replacement-old", "connection-A");
                require(heldResult.status() == SessionCollectionPublication.Status.DELTA,
                    "old replacement tuple publication must commit");
                client.publishPublication(heldResult.publication());
                var staleActivation = activation.activate(client.activationSnapshot().orElseThrow());
                require(!staleActivation.isDone(), "controlled old tuple source must remain pending");

                var replacementEntry = entry(fixture.asset("replacement-new"), "remote/server", CatalogAccess.PUBLIC);
                var replacementResult = acceptDelta(receiver, List.of(Wire.catalogAdd(6, 0, true, replacementEntry)),
                    client.remoteSnapshot().orElseThrow(), evidence, "tuple-replacement-new", "connection-A");
                require(replacementResult.status() == SessionCollectionPublication.Status.DELTA,
                    "new replacement tuple publication must commit");
                requirePublication(replacementResult.publication(), plan.replaced(), "tuple-replacement-new");
                client.publishPublication(replacementResult.publication());
                var replacementActivation = activation.activate(client.activationSnapshot().orElseThrow()).join();
                requireThrowsCompletion(staleActivation, "superseded tuple owner must terminate as stale");
                require(fixture.completeHeld("replacement-old"), "late source input must complete after supersession");
                require(store.probeMetadata(fixture.asset("replacement-old").identity()).isEmpty(),
                    "late old tuple must not commit exact cache content");
                require(store.probeMetadata(fixture.asset("replacement-new").identity()).isPresent(),
                    "current replacement tuple must commit exact cache content");
                var replacementReady =
                    requireReady(replacementActivation, fixture.asset("replacement-new").identity().modelId());
                require(
                    replacementReady.record().binding().content().representation().identity()
                        .equals(fixture.asset("replacement-new").identity()),
                    "replacement Ready must carry the exact current representation");
                publish(client, manager, replacementActivation);
                record(evidence, "tuple-replacement-new", "connection-A", "publication-6", "owner-supersession",
                    Map.of("publication", publication(replacementResult.publication()), "projection",
                        projection(replacementActivation), "oldOwnerTerminal", "cancelled", "lateOldCacheEffect",
                        "none", "currentCache", "exact"));

                var removeAdd =
                    List.of(Wire.catalogRemove(7, 0, false, fixture.asset("cache-exact").identity().modelId()),
                        Wire.catalogAdd(7, 1, true,
                            entry(fixture.asset("online-add"), "remote/online", CatalogAccess.PUBLIC)));
                var onlineResult = acceptDelta(receiver, removeAdd, client.remoteSnapshot().orElseThrow(), evidence,
                    "online-remove-add", "connection-A");
                require(onlineResult.status() == SessionCollectionPublication.Status.DELTA,
                    "online remove/add must commit atomically");
                requirePublication(onlineResult.publication(), plan.onlineChanged(), "online-remove-add");
                client.publishPublication(onlineResult.publication());
                var onlineActivation = activation.activate(client.activationSnapshot().orElseThrow()).join();
                require(!onlineActivation.entries().containsKey(fixture.asset("cache-exact").identity().modelId()),
                    "removed tuple must leave the current projection");
                requireReady(onlineActivation, fixture.asset("online-add").identity().modelId());
                publish(client, manager, onlineActivation);
                record(evidence, "online-remove-add", "connection-A", "publication-7", "publication-activation",
                    Map.of("publication", publication(onlineResult.publication()), "projection",
                        projection(onlineActivation), "sourceRequests", sourceRequests(fixture)));

                verifyAccessFailures(fixture, evidence);
                verifyInvalidFull(plan.initial(), evidence);
                verifyDisconnectRestoreReconnect(fixture, catalogs, store, manager, client, receiver, activation,
                    onlineActivation, plan, evidence);
            }
        }
    }

    private void verifyAccessFailures(RemotePublicationScenarioFixture fixture, ScenarioEvidence evidence)
        throws Exception {
        var beforeLocal = fixture.sourceRequestCount();
        var inaccessible = fixture
            .inaccessibleLocalCandidate(Files.createDirectories(temp.resolve("inaccessible-local")), "server-failure");
        var localPublication = publication(
            List.of(entry(fixture.asset("server-failure"), "remote/local-access", CatalogAccess.PUBLIC)), Set.of());
        try (inaccessible; var store = new RemoteModelStore(temp.resolve("local-access-store"));
            var owner = new RemoteCatalogActivation(
                () -> new CatalogIndexSnapshot(List.of(inaccessible.entry()), List.of(), ModelScanReport.empty()),
                CatalogSnapshot.empty(), store, fixture.fetcher(), Runnable::run)) {
            var failed = requireFailed(owner.activate(localPublication).join(),
                fixture.asset("server-failure").identity().modelId());
            require(failed.failure().kind() == ActivationFailure.Kind.TRANSIENT_ACCESS,
                "local access failure must remain transient");
            require(fixture.sourceRequestCount() == beforeLocal,
                "local access failure must not be mislabeled as a server request");
            record(evidence, "local-access-failure", "connection-local-probe", "local-owner", "source-failure",
                Map.of("projection", "Failed:" + failed.failure().kind(), "serverRequests", "0"));
        }

        var closedStore = new RemoteModelStore(temp.resolve("closed-cache-store"));
        closedStore.close();
        var beforeCache = fixture.sourceRequestCount();
        try (var owner = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(), closedStore,
            fixture.fetcher(), Runnable::run)) {
            var cachePublication = publication(
                List.of(entry(fixture.asset("online-add"), "remote/cache-access", CatalogAccess.PUBLIC)), Set.of());
            var failed = requireFailed(owner.activate(cachePublication).join(),
                fixture.asset("online-add").identity().modelId());
            require(failed.failure().kind() == ActivationFailure.Kind.TRANSIENT_ACCESS,
                "cache access failure must remain transient");
            require(fixture.sourceRequestCount() == beforeCache,
                "cache access failure must not fall through to server authorization");
            record(evidence, "cache-access-failure", "connection-cache-probe", "cache-owner", "source-failure",
                Map.of("projection", "Failed:" + failed.failure().kind(), "serverRequests", "0"));
        }
    }

    private void verifyInvalidFull(RemotePublicationSnapshot valid, ScenarioEvidence evidence) throws Exception {
        var invalidFullClient = negotiatingClient(CatalogSnapshot.empty());
        var invalidFullReceiver = new SessionCollectionPublication.Receiver(Map.of());
        var invalidFull = invalidFullReceiver
            .acceptFull(Wire.catalogFull(1, 0, true, valid.entries().values().stream().toList()), null);
        require(invalidFull.status() == SessionCollectionPublication.Status.INTRINSIC_INVALID,
            "full publication missing required collections must be intrinsic invalid");
        invalidFullClient.failCompleteTransfer();
        require(invalidFullClient.state() == ClientModelSession.State.INTRINSIC_DEFAULT_ONLY,
            "invalid full must close only the model session capability");
        record(evidence, "domain-invalid-full", "connection-invalid-full", "publication-1", "model-session-failure",
            Map.of("status", invalidFull.status().name(), "clientState", invalidFullClient.state().name()));

    }

    private void verifyDisconnectRestoreReconnect(RemotePublicationScenarioFixture fixture,
        ClientCatalogScenarioHarness catalogs, RemoteModelStore store, ClientCatalogManager manager,
        ClientModelSession firstClient, SessionCollectionPublication.Receiver firstReceiver,
        RemoteCatalogActivation firstActivation, ActivationSnapshot previousRemote, ScenarioPlan plan,
        ScenarioEvidence evidence) throws Exception {
        var pendingDisconnect = acceptDelta(firstReceiver,
            List.of(Wire.catalogAdd(8, 0, true,
                entry(fixture.asset("replacement-old"), "remote/server", CatalogAccess.PUBLIC))),
            firstClient.remoteSnapshot().orElseThrow(), evidence, "disconnect-pending-old", "connection-A");
        require(pendingDisconnect.status() == SessionCollectionPublication.Status.DELTA,
            "disconnect setup tuple must commit as lean publication");
        requirePublication(pendingDisconnect.publication(), plan.disconnectPending(), "disconnect-pending-old");
        firstClient.publishPublication(pendingDisconnect.publication());
        var lateOldConnection = firstActivation.activate(firstClient.activationSnapshot().orElseThrow());
        require(!lateOldConnection.isDone(), "old connection source input must remain pending until reconnect");
        manager.publishSession(firstClient.activationSnapshot().orElseThrow());

        var invalidDelta =
            firstReceiver.acceptDelta(Wire.emptyCatalogAdd(9), firstClient.remoteSnapshot().orElseThrow());
        require(invalidDelta.status() == SessionCollectionPublication.Status.INTRINSIC_INVALID,
            "delta with empty ADD payload must be intrinsic invalid");
        firstClient.failCompleteTransfer();
        manager.failRemote();
        require(firstClient.state() == ClientModelSession.State.INTRINSIC_DEFAULT_ONLY,
            "invalid delta must close only the model session capability");
        require(manager.snapshot().catalog().byModelId().size() == 1,
            "model-session failure must expose only the intrinsic default");
        record(evidence, "domain-invalid-delta", "connection-A", "publication-9", "model-session-failure",
            Map.of("status", invalidDelta.status().name(), "clientState", firstClient.state().name(),
                "publicModelCount", "1"));

        firstActivation.close();
        firstClient.close();
        manager.endRemote();
        var expectedLocal = Set.of(fixture.asset("default").identity().modelId(),
            fixture.asset("active-exact").identity().modelId(), fixture.asset("same-model-local").identity().modelId(),
            fixture.asset("local-exact").identity().modelId());
        require(manager.snapshot().catalog().byModelId().keySet().equals(expectedLocal),
            "session end must publish the latest local authority");
        record(evidence, "disconnect-local-authority", "connection-A", "local-authority-A", "session-end",
            Map.of("remoteEntriesBeforeClose", Integer.toString(previousRemote.entries().size()), "localModelIds",
                modelIds(manager.snapshot().catalog())));

        var secondActive = manager.beginRemote();
        var secondClient = negotiatingClient(CatalogSnapshot.empty());
        var secondReceiver = new SessionCollectionPublication.Receiver(Map.of());
        var secondPublication = publication(
            List.of(entry(fixture.asset("online-add"), "remote/reconnect-b", CatalogAccess.PUBLIC)), Set.of());
        var secondFull = acceptFull(secondReceiver, secondPublication, null, secondClient, evidence, "reconnect-b-full",
            "connection-B");
        secondClient.publishFull(secondFull.publication());
        try (var secondActivation =
            new RemoteCatalogActivation(manager::localIndex, secondActive, store, fixture.fetcher(), Runnable::run)) {
            var terminal = secondActivation.activate(secondClient.activationSnapshot().orElseThrow()).join();
            publish(secondClient, manager, terminal);
        }

        secondClient.close();
        manager.endRemote();
        require(manager.snapshot().catalog().byModelId().keySet().equals(expectedLocal),
            "session end must immediately expose the current local snapshot");

        var thirdActive = manager.beginRemote();
        var thirdClient = negotiatingClient(CatalogSnapshot.empty());
        var thirdReceiver = new SessionCollectionPublication.Receiver(Map.of());
        var thirdPublication = publication(
            List.of(entry(fixture.asset("replacement-new"), "remote/reconnect-c", CatalogAccess.PUBLIC)), Set.of());
        var thirdFull = acceptFull(thirdReceiver, thirdPublication, null, thirdClient, evidence,
            "session-end-overlap-reconnect", "connection-C");
        thirdClient.publishFull(thirdFull.publication());
        try (var thirdActivation =
            new RemoteCatalogActivation(manager::localIndex, thirdActive, store, fixture.fetcher(), Runnable::run)) {
            var terminal = thirdActivation.activate(thirdClient.activationSnapshot().orElseThrow()).join();
            publish(thirdClient, manager, terminal);
            var expectedCurrent = manager.snapshot().catalog().byModelId().keySet();
            require(manager.snapshot().catalog().byModelId().keySet().equals(expectedCurrent),
                "the prior session end must not overwrite new remote authority");
            require(expectedCurrent.contains(fixture.asset("replacement-new").identity().modelId()),
                "new exact connection publication must remain current");
            requireThrowsCompletion(lateOldConnection, "disconnect must terminal the old exact entry owner");
            require(fixture.completeHeld("replacement-old"),
                "old connection input must be deliverable after reconnect");
            require(manager.snapshot().catalog().byModelId().keySet().equals(expectedCurrent),
                "late old connection completion must not affect new remote authority");
            require(store.probeMetadata(fixture.asset("replacement-old").identity()).isEmpty(),
                "late old connection completion must not commit stale exact cache data");
            record(evidence, "session-end-overlap-reconnect", "connection-C", "local-authority-B",
                "stale-effect-isolation",
                Map.of("currentPublication", publication(thirdPublication), "lateSessionEndEffect", "none",
                    "oldConnectionTerminal", "cancelled", "lateOldConnectionEffect", "none"));
        }
    }

    private static ScenarioPlan plan(RemotePublicationScenarioFixture fixture) {
        var initialEntries = List.of(entry(fixture.asset("active-exact"), "remote/active", CatalogAccess.PUBLIC),
            entry(fixture.asset("same-model-remote"), "remote/same", CatalogAccess.PUBLIC),
            entry(fixture.asset("local-exact"), "remote/local", CatalogAccess.AUTHORIZED),
            entry(fixture.asset("cache-exact"), "remote/cache", CatalogAccess.AUTHORIZED),
            entry(fixture.asset("server-exact"), "remote/server", CatalogAccess.PUBLIC));
        var initialGrants = Set.of(fixture.asset("local-exact").identity().modelId(),
            fixture.asset("cache-exact").identity().modelId());
        var initial = publication(initialEntries, initialGrants);
        var grantChanged = publication(initialEntries, Set.of(fixture.asset("local-exact").identity().modelId()));

        var failedEntries = new ArrayList<>(initialEntries);
        failedEntries.add(entry(fixture.asset("server-failure"), "remote/server-failure", CatalogAccess.AUTHORIZED));
        var withServerFailure = publication(failedEntries, grantChanged.grants());

        var replacedEntries = new ArrayList<>(failedEntries);
        replacedEntries.removeIf(entry -> entry.modelId().equals(fixture.asset("server-exact").identity().modelId()));
        replacedEntries.add(entry(fixture.asset("replacement-new"), "remote/server", CatalogAccess.PUBLIC));
        var replaced = publication(replacedEntries, grantChanged.grants());

        var onlineEntries = new ArrayList<>(replacedEntries);
        onlineEntries.removeIf(entry -> entry.modelId().equals(fixture.asset("cache-exact").identity().modelId()));
        onlineEntries.add(entry(fixture.asset("online-add"), "remote/online", CatalogAccess.PUBLIC));
        var onlineChanged = publication(onlineEntries, grantChanged.grants());

        var disconnectEntries = new ArrayList<>(onlineEntries);
        disconnectEntries.removeIf(entry -> entry.modelId().equals(fixture.asset("server-exact").identity().modelId()));
        disconnectEntries.add(entry(fixture.asset("replacement-old"), "remote/server", CatalogAccess.PUBLIC));
        var disconnectPending = publication(disconnectEntries, grantChanged.grants());

        var fixtureIdentities = new LinkedHashMap<String, String>();
        List.of("default", "active-exact", "same-model-local", "same-model-remote", "local-exact", "cache-exact",
            "server-exact", "server-failure", "replacement-old", "replacement-new", "online-add")
            .forEach(name -> fixtureIdentities.put(name, identity(fixture.asset(name).identity())));
        var actions = List.of(action("initial-full", "Full", initial, "all Pending then entry terminal"),
            action("authorization-change", "Delta", grantChanged, "unchanged tuples retain terminal"),
            action("baseline-incompatible-delta", "Delta", grantChanged, "Baseline drift; retain previous authority"),
            action("server-fetch-failure", "Delta", withServerFailure, "new entry Failed; unaffected Ready retained"),
            action("tuple-replacement-old", "Delta", null, "old exact owner held Pending"),
            action("tuple-replacement-new", "Delta", replaced, "old owner cancelled; new exact tuple Ready"),
            action("online-remove-add", "Delta", onlineChanged, "remove/add commits one complete publication"),
            new ActionInput("local-access-failure", "Activation", List.of(), "entry-local transient failure"),
            new ActionInput("cache-access-failure", "Activation", List.of(), "entry-local transient failure"),
            new ActionInput("domain-invalid-full", "Full", List.of(), "model session closes"),
            action("disconnect-pending-old", "Delta", disconnectPending, "old connection exact owner remains Pending"),
            new ActionInput("domain-invalid-delta", "Delta", List.of(), "model session closes"),
            new ActionInput("disconnect-local-authority", "Lifecycle", List.of(), "latest local index becomes current"),
            new ActionInput("session-end-overlap-reconnect", "Lifecycle", List.of(),
                "new remote authority remains current"));
        return new ScenarioPlan(initial, grantChanged, withServerFailure, replaced, onlineChanged, disconnectPending,
            new ScenarioInput(fixtureIdentities, actions));
    }

    private static ActionInput action(String id, String kind, RemotePublicationSnapshot expected,
        String expectedResult) {
        return new ActionInput(id, kind, expected == null ? List.of() : expected.entries().values().stream()
            .map(ModelManagementRemotePublicationScenarioTest::entry).sorted().toList(), expectedResult);
    }

    private static ClientModelSession negotiatingClient(CatalogSnapshot local) {
        var client = new ClientModelSession(SessionMode.AUTO, true, local);
        require(client.onServerHello(ProtocolVersion.TRANSPORT_VERSION, false).orElseThrow().accepted(),
            "same-build hello must enter remote negotiation");
        return client;
    }

    private static SessionCollectionPublication.Result acceptFull(SessionCollectionPublication.Receiver receiver,
        RemotePublicationSnapshot expected, RemotePublicationSnapshot previous, ClientModelSession client,
        ScenarioEvidence evidence, String action, String connection) throws Exception {
        var fragments = Wire.full(1, expected);
        SessionCollectionPublication.Result result = null;
        for (var index = 0; index < fragments.size(); index++) {
            result = receiver.acceptFull(fragments.get(index), previous);
            if (index + 1 != fragments.size()) {
                require(result.status() == SessionCollectionPublication.Status.PENDING,
                    "partial full transaction must remain Pending");
                require(client.remoteSnapshot().isEmpty(), "partial full transaction must not become client authority");
            }
        }
        record(evidence, action, connection, "publication-1", "serialized-full",
            Map.of("fragmentCount", Integer.toString(fragments.size()), "serializedBytes",
                Integer.toString(fragments.stream().mapToInt(SessionFullFragmentProto::getSerializedSize).sum()),
                "terminalStatus", result.status().name()));
        return result;
    }

    private static SessionCollectionPublication.Result acceptDelta(SessionCollectionPublication.Receiver receiver,
        List<SessionDeltaFragmentProto> fragments, RemotePublicationSnapshot previous, ScenarioEvidence evidence,
        String action, String connection) throws Exception {
        SessionCollectionPublication.Result result = null;
        for (var index = 0; index < fragments.size(); index++) {
            result = receiver.acceptDelta(fragments.get(index), previous);
            if (index + 1 != fragments.size()) {
                require(result.status() == SessionCollectionPublication.Status.PENDING,
                    "partial delta transaction must remain Pending");
            }
        }
        record(evidence, action, connection, "publication-" + Long.toUnsignedString(fragments.get(0).transferId()),
            "serialized-delta",
            Map.of("fragmentCount", Integer.toString(fragments.size()), "serializedBytes",
                Integer.toString(fragments.stream().mapToInt(SessionDeltaFragmentProto::getSerializedSize).sum()),
                "terminalStatus", result.status().name()));
        return result;
    }

    private static void publish(ClientModelSession client,
        com.elfmcys.ysm.model.catalog.client.ClientCatalogManager manager, ActivationSnapshot activation) {
        client.publishActivation(activation);
        manager.publishSession(client.activationSnapshot().orElseThrow());
    }

    private static void requireInitialSources(RemotePublicationScenarioFixture fixture, ActivationSnapshot snapshot) {
        requireActual(snapshot, fixture.asset("active-exact"), fixture.asset("active-exact"));
        requireActual(snapshot, fixture.asset("same-model-remote"), fixture.asset("same-model-local"));
        requireActual(snapshot, fixture.asset("local-exact"), fixture.asset("local-exact"));
        requireActual(snapshot, fixture.asset("cache-exact"), fixture.asset("cache-exact"));
        requireActual(snapshot, fixture.asset("server-exact"), fixture.asset("server-exact"));
    }

    private static void requireActual(ActivationSnapshot snapshot, Asset requested, Asset actual) {
        var ready = requireReady(snapshot, requested.identity().modelId());
        require(ready.publication().identity().equals(requested.identity()),
            requested.name() + " publication identity mismatch");
        require(ready.record().binding().content().representation().identity().equals(actual.identity()),
            requested.name() + " actual representation mismatch");
    }

    private static ActivationSnapshot.Ready requireReady(ActivationSnapshot snapshot, Hash256 modelId) {
        var state = snapshot.entries().get(modelId);
        if (!(state instanceof ActivationSnapshot.Ready ready)) {
            throw new AssertionError("Expected Ready for " + modelId + " but was " + state);
        }
        return ready;
    }

    private static ActivationSnapshot.Failed requireFailed(ActivationSnapshot snapshot, Hash256 modelId) {
        var state = snapshot.entries().get(modelId);
        if (!(state instanceof ActivationSnapshot.Failed failed)) {
            throw new AssertionError("Expected Failed for " + modelId + " but was " + state);
        }
        return failed;
    }

    private static boolean allPending(ActivationSnapshot snapshot) {
        return snapshot.entries().values().stream().allMatch(ActivationSnapshot.Pending.class::isInstance);
    }

    private static boolean noPending(ActivationSnapshot snapshot) {
        return snapshot.entries().values().stream().noneMatch(ActivationSnapshot.Pending.class::isInstance);
    }

    private static int readyCount(ActivationSnapshot snapshot) {
        return (int)snapshot.entries().values().stream().filter(ActivationSnapshot.Ready.class::isInstance).count();
    }

    private static void requirePublication(RemotePublicationSnapshot actual, RemotePublicationSnapshot expected,
        String action) {
        require(actual.equals(expected),
            action + " publication mismatch expected=" + publication(expected) + " actual=" + publication(actual));
    }

    private static void requireThrowsCompletion(CompletableFuture<?> future, String message) {
        try {
            future.join();
            throw new AssertionError(message);
        } catch (CompletionException expected) {
            // Exact old owner failure is the expected terminal.
        }
    }

    private static RemotePublicationSnapshot publication(List<PublicationEntry> entries, Set<Hash256> grants) {
        return new RemotePublicationSnapshot(entries, grants, List.of(), Map.of());
    }

    private static PublicationEntry entry(Asset asset, String path, CatalogAccess access) {
        return new PublicationEntry(asset.identity(), new HierarchyPath("local:custom/" + path), access, true);
    }

    private static String publication(RemotePublicationSnapshot publication) {
        return new String(EvidenceJson.canonicalBytes(Map.of("entries",
            publication.entries().values().stream().map(ModelManagementRemotePublicationScenarioTest::entry).sorted()
                .toList(),
            "grants", publication.grants().stream().map(Hash256::toString).sorted().toList())), StandardCharsets.UTF_8);
    }

    private static String projection(ActivationSnapshot snapshot) {
        var result = new LinkedHashMap<String, String>();
        snapshot.entries().forEach((modelId, state) -> {
            if (state instanceof ActivationSnapshot.Ready ready) {
                result.put(modelId.toString(),
                    "Ready:" + identity(ready.record().binding().content().representation().identity()));
            } else if (state instanceof ActivationSnapshot.Failed failed) {
                result.put(modelId.toString(), "Failed:" + failed.failure().kind());
            } else {
                result.put(modelId.toString(), "Pending");
            }
        });
        return new String(EvidenceJson.canonicalBytes(result), StandardCharsets.UTF_8);
    }

    private static String sourceRequests(RemotePublicationScenarioFixture fixture) {
        return new String(EvidenceJson.canonicalBytes(fixture.sourceRequests()), StandardCharsets.UTF_8);
    }

    private static String modelIds(CatalogSnapshot snapshot) {
        return new String(
            EvidenceJson
                .canonicalBytes(snapshot.byModelId().keySet().stream().map(Hash256::toString).sorted().toList()),
            StandardCharsets.UTF_8);
    }

    private static String identity(ModelFileIdentity identity) {
        return identity.modelId() + ":" + identity.containerId();
    }

    private static String entry(PublicationEntry entry) {
        return identity(entry.identity()) + ":" + entry.path().value() + ":" + entry.access();
    }

    private static void record(ScenarioEvidence evidence, String action, String connection, String operation,
        String kind, Map<String, String> payload) throws Exception {
        evidence.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, action, "remote-publication", connection,
            operation, kind, payload));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void completeFailure(ScenarioEvidence evidence, Throwable failure) throws Exception {
        var text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        evidence.retainFailureArtifact("scenario-failure.txt", text.toString().getBytes(StandardCharsets.UTF_8));
        evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.FAIL,
            List.of("The remote publication scenario terminated at a machine assertion or production boundary"),
            List.of(failure.getClass().getName() + ": " + Objects.toString(failure.getMessage(), "(no message)")),
            Map.of("contractViolation", 1L, "unexpectedError", 1L),
            "Failure is scoped to deterministic in-process publication/session/activation composition"));
    }

    private record ScenarioPlan(RemotePublicationSnapshot initial, RemotePublicationSnapshot grantChanged,
        RemotePublicationSnapshot withServerFailure, RemotePublicationSnapshot replaced,
        RemotePublicationSnapshot onlineChanged, RemotePublicationSnapshot disconnectPending, ScenarioInput input) {
    }

    private record ScenarioInput(Map<String, String> fixtureIdentities, List<ActionInput> actions) {
    }

    private record ActionInput(String actionId, String kind, List<String> expectedPublication, String expectedResult) {
    }

    private static final class Wire {
        private Wire() {}

        private static List<SessionFullFragmentProto> full(long transferId, RemotePublicationSnapshot publication) {
            return List.of(
                catalogFull(transferId, 0, false,
                    publication
                        .entries().values().stream().sorted(Comparator.comparing(PublicationEntry::modelId)).toList()),
                grantFull(transferId, 1, false, publication.grants()),
                SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(2).setFinalFragment(false)
                    .setPackPresentations(
                        PackPresentationCollectionOperationProto.newBuilder().setOpType(full()).build())
                    .build(),
                SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(3).setFinalFragment(true)
                    .setDefaultAnimations(
                        DefaultAnimationCollectionOperationProto.newBuilder().setOpType(full()).build())
                    .build());
        }

        private static SessionFullFragmentProto catalogFull(long transferId, int sequence, boolean terminal,
            List<PublicationEntry> entries) {
            var operation = CatalogCollectionOperationProto.newBuilder().setOpType(full());
            entries.forEach(entry -> operation.addEntries(publication(entry)));
            return SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
                .setFinalFragment(terminal).setCatalog(operation.build()).build();
        }

        private static SessionFullFragmentProto grantFull(long transferId, int sequence, boolean terminal,
            Set<Hash256> grants) {
            var operation = GrantCollectionOperationProto.newBuilder().setOpType(full());
            grants.stream().sorted().forEach(hash -> operation.addModelIds(ProtoBytes.wrap(hash)));
            return SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
                .setFinalFragment(terminal).setGrants(operation.build()).build();
        }

        private static List<SessionDeltaFragmentProto> grants(long transferId, Set<Hash256> grants) {
            var clear =
                SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(0).setFinalFragment(false)
                    .setGrants(
                        GrantCollectionOperationProto.newBuilder().setOpType(CollectionOperationType.CLEAR).build())
                    .build();
            var add = GrantCollectionOperationProto.newBuilder().setOpType(CollectionOperationType.ADD);
            grants.stream().sorted().forEach(hash -> add.addModelIds(ProtoBytes.wrap(hash)));
            return List.of(clear, SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(1)
                .setFinalFragment(true).setGrants(add.build()).build());
        }

        private static SessionDeltaFragmentProto catalogAdd(long transferId, int sequence, boolean terminal,
            PublicationEntry entry) {
            return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
                .setFinalFragment(terminal).setCatalog(CatalogCollectionOperationProto.newBuilder()
                    .setOpType(CollectionOperationType.ADD).addEntries(publication(entry)).build())
                .build();
        }

        private static SessionDeltaFragmentProto catalogRemove(long transferId, int sequence, boolean terminal,
            Hash256 modelId) {
            return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
                .setFinalFragment(terminal).setCatalog(CatalogCollectionOperationProto.newBuilder()
                    .setOpType(CollectionOperationType.REMOVE).addModelIds(ProtoBytes.wrap(modelId)).build())
                .build();
        }

        private static SessionDeltaFragmentProto emptyCatalogAdd(long transferId) {
            return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(0)
                .setFinalFragment(true)
                .setCatalog(CatalogCollectionOperationProto.newBuilder().setOpType(CollectionOperationType.ADD).build())
                .build();
        }

        private static CatalogPublicationProto publication(PublicationEntry entry) {
            return CatalogPublicationProto.newBuilder().setModelId(ProtoBytes.wrap(entry.modelId()))
                .setContainerId(ProtoBytes.wrap(entry.containerId())).setHierarchyPath(entry.path().value())
                .setRuntimeContentAllowed(entry.runtimeContentAllowed())
                .setAccess(entry.access() == CatalogAccess.PUBLIC ? com.elfmcys.ysm.proto.network.CatalogAccess.PUBLIC
                    : com.elfmcys.ysm.proto.network.CatalogAccess.AUTHORIZED)
                .build();
        }

        private static CollectionOperationType full() {
            return CollectionOperationType.FULL;
        }
    }
}
