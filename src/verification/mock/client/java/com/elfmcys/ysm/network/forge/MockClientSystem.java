package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.mock.classpath.EndpointEvidence;
import com.elfmcys.ysm.mock.classpath.MockNativeRuntime;
import com.elfmcys.ysm.mock.classpath.MockScenarioFixture;
import com.elfmcys.ysm.mock.classpath.MockWireIo;
import com.elfmcys.ysm.mock.classpath.SocketByteCarrier;
import com.elfmcys.ysm.mock.evidence.MockExact100Input;
import com.elfmcys.ysm.model.catalog.MockClientCatalog;
import com.elfmcys.ysm.model.catalog.ReloadStatus;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.remote.RemoteMetadataFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.RemoteCatalogActivation;
import com.elfmcys.ysm.model.session.client.state.ActivationFailure;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.proto.network.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import us.hebi.quickbuf.ProtoMessage;

/** Full FA-005 client path on the I-08 ordinary-JVM endpoint. */
final class MockClientSystem {
    private MockClientSystem() {}

    static void run(String[] args) throws Throwable {
        if ("exact-100".equals(args[5])) {
            runExact100(args);
            return;
        }
        var port = Integer.parseInt(args[0]);
        var ready = Path.of(args[1]);
        var exit = Path.of(args[2]);
        var evidence = new EndpointEvidence(args[4], "client", Path.of(args[3]));
        var fixtureRoot = Path.of(args[6]).toAbsolutePath().normalize();
        var input = Path.of(args[7]).toAbsolutePath().normalize();
        var nativeSha256 = MockNativeRuntime.initialize(Path.of(args[8]));
        Throwable failure = null;
        var ownersClosed = 0;
        try (var fixture = MockScenarioFixture.create(fixtureRoot.resolve("fixture"));
            var catalogs = MockClientCatalog.open(fixtureRoot.resolve("catalog"), fixture.asset("default").file(),
                List.of(fixture.asset("active-exact").file(), fixture.asset("same-model-local").file()));
            var store = new RemoteModelStore(fixtureRoot.resolve("remote-store"))) {
            require(EndpointEvidence.workingDirectoryEmpty(), "Client working directory was not empty at process load");
            var messageCount = com.elfmcys.ysm.mock.classpath.ProductionWire.registeredMessageCount();
            require(messageCount > 0, "Production protocol registry was empty");
            EndpointEvidence.writeNew(ready,
                Map.of("bootstrap", "ordinary-java-main", "fixture", fixture.inventory(), "headless", true,
                    "nativeSha256", nativeSha256, "physicalSide", "client", "pid", ProcessHandle.current().pid(),
                    "port", port, "protocolMessageCount", messageCount, "workingDirectory",
                    Path.of("").toAbsolutePath().normalize().toString(), "workingDirectoryEmpty", true));
            evidence.append("system", "client-load", "client-endpoint", "endpoint-load",
                Map.of("bootstrapMainClass", MockClientEndpoint.class.getName(), "childProcessCount",
                    Long.toString(ProcessHandle.current().descendants().count()), "physicalSide", "client",
                    "workingDirectoryEmpty", "true"));
            awaitInput(input, Duration.ofSeconds(20));

            var manager = catalogs.manager();
            var activeLocal = manager.beginRemote();
            catalogs.addSource(fixture.asset("local-exact").file(), "local-exact.mxc");
            require(catalogs.reload().join().status() == ReloadStatus.COMMITTED,
                "Index-only local reload did not commit");
            try (var prefix = fixture.asset("cache-exact").content().representation().metadataPrefix().orElseThrow()) {
                store.commitMetadataPrefix(fixture.asset("cache-exact").identity(), prefix).representation().close();
            }

            var facts = new LinkedHashMap<String, String>();
            var oldIdentity = fixture.asset("replacement-old").identity();
            HoldingMetadataSource holding;
            try (var connectionA = connect(port, "connection-A", evidence, manager.localState().snapshot())) {
                var initial = receiveFull(connectionA, null);
                connectionA.session.publishFull(initial);
                holding = new HoldingMetadataSource(connectionA.transfers, oldIdentity);
                try (var activation = new com.elfmcys.ysm.model.session.client.RemoteCatalogActivation(
                    manager::localIndex, activeLocal, store, holding, Runnable::run)) {
                    var activated = settle(activation.activate(connectionA.session.activationSnapshot().orElseThrow()),
                        connectionA);
                    publish(connectionA.session, manager, activated);
                    verifyInitial(fixture, activated, holding.requestedIdentities(), facts);

                    var chunk = fixture.asset("server-exact").content().modelFile().getFileView().getAssetView()
                        .getChunkTable().values().stream()
                        .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                        .findFirst().orElseThrow();
                    var unauthorized = connectionA.transfers.fetch(fixture.asset("server-exact").identity(),
                        List.of(chunk), (ignored, bytes) -> {
                        });
                    pumpUntil(unauthorized, connectionA);
                    requireAccessFailure(unauthorized, "Unauthorized chunk request");
                    facts.put("unauthorizedAdmission", "rejected-before-dispatch");

                    var granted = receiveDelta(connectionA, connectionA.session.remoteSnapshot().orElseThrow());
                    connectionA.session.publishPublication(granted);
                    var grantActivation = settle(
                        activation.activate(connectionA.session.activationSnapshot().orElseThrow()), connectionA);
                    publish(connectionA.session, manager, grantActivation);
                    var receivedChunkBytes = new int[1];
                    var accepted = connectionA.transfers.fetch(fixture.asset("server-exact").identity(), List.of(chunk),
                        (ignored, bytes) -> receivedChunkBytes[0] += bytes.size());
                    pumpUntil(accepted, connectionA);
                    accepted.join();
                    require(receivedChunkBytes[0] == chunk.size(),
                        "Accepted chunk bytes did not match the exact descriptor");
                    facts.put("acceptedAfterRevoke", "completed");
                    facts.put("acceptedChunkBytes", Integer.toString(receivedChunkBytes[0]));

                    var revoked = receiveDelta(connectionA, connectionA.session.remoteSnapshot().orElseThrow());
                    connectionA.session.publishPublication(revoked);
                    var revokeActivation = settle(
                        activation.activate(connectionA.session.activationSnapshot().orElseThrow()), connectionA);
                    publish(connectionA.session, manager, revokeActivation);
                    require(
                        revokeActivation.entries().values().stream()
                            .noneMatch(ActivationSnapshot.Pending.class::isInstance),
                        "Authorization-only delta restarted an exact activation");

                    var failurePublication =
                        receiveDelta(connectionA, connectionA.session.remoteSnapshot().orElseThrow());
                    connectionA.session.publishPublication(failurePublication);
                    var failureActivation = settle(
                        activation.activate(connectionA.session.activationSnapshot().orElseThrow()), connectionA);
                    publish(connectionA.session, manager, failureActivation);
                    var failed = failureActivation.entries().get(fixture.asset("server-failure").identity().modelId());
                    require(failed instanceof ActivationSnapshot.Failed value && value.failure()
                        .kind() == com.elfmcys.ysm.model.session.client.state.ActivationFailure.Kind.TRANSIENT_ACCESS,
                        "Server source failure did not remain entry-local and transient");
                    facts.put("serverSourceFailure", "transient-entry-local");

                    var retained = connectionA.session.remoteSnapshot().orElseThrow();
                    var drift = receivePublication(connectionA, retained, false);
                    require(drift.status() == SessionCollectionPublication.Status.BASELINE_DRIFT,
                        "Cross-entry container conflict was not baseline drift");
                    require(connectionA.session.remoteSnapshot().orElseThrow().equals(retained),
                        "Baseline drift changed client authority");
                    facts.put("baselineIncompatibleDelta", "retained-authority");

                    var replacement = receiveDelta(connectionA, retained);
                    connectionA.session.publishPublication(replacement);
                    var staleActivation = activation.activate(connectionA.session.activationSnapshot().orElseThrow());
                    pumpUntil(holding.networkCompleted(), connectionA);
                    require(!staleActivation.isDone(), "Held old source completed before connection replacement");
                    facts.put("oldSourceAtDisconnect", "accepted-pending");
                }
                connectionA.session.failCompleteTransfer();
                facts.put("connectionAStateAfterFailure", connectionA.session.state().name());
            }
            ownersClosed++;
            catalogs.endRemote();
            require(
                manager.snapshot().catalog().byModelId().containsKey(fixture.asset("local-exact").identity().modelId()),
                "Latest local catalog was not visible during disconnect");

            try (var connectionB = connect(port, "connection-B", evidence, manager.localState().snapshot())) {
                var activeB = manager.beginRemote();
                var publicationB = receiveFull(connectionB, null);
                connectionB.session.publishFull(publicationB);
                try (var activationB = new com.elfmcys.ysm.model.session.client.RemoteCatalogActivation(
                    manager::localIndex, activeB, store, connectionB.transfers, Runnable::run)) {
                    var activatedB = settle(
                        activationB.activate(connectionB.session.activationSnapshot().orElseThrow()), connectionB);
                    publish(connectionB.session, manager, activatedB);
                    requireReadyIdentity(activatedB, fixture.asset("replacement-new").identity(),
                        fixture.asset("replacement-new").identity());

                    holding.releaseLate();
                    require(store.probeMetadata(oldIdentity).isEmpty(), "Late old source committed exact cache data");
                    require(store.probeMetadata(fixture.asset("replacement-new").identity()).isPresent(),
                        "Current replacement did not commit exact cache data");
                    facts.put("lateOldCacheEffect", "none");
                    facts.put("replacementReady", "exact-current");

                    var cacheFailurePublication =
                        receiveDelta(connectionB, connectionB.session.remoteSnapshot().orElseThrow());
                    connectionB.session.publishPublication(cacheFailurePublication);
                    store.close();
                    var cacheFailureActivation = settle(
                        activationB.activate(connectionB.session.activationSnapshot().orElseThrow()), connectionB);
                    publish(connectionB.session, manager, cacheFailureActivation);
                    var cacheFailed =
                        cacheFailureActivation.entries().get(fixture.asset("cache-failure").identity().modelId());
                    require(cacheFailed instanceof ActivationSnapshot.Failed value && value.failure()
                        .kind() == com.elfmcys.ysm.model.session.client.state.ActivationFailure.Kind.TRANSIENT_ACCESS,
                        "Closed cache access did not remain a transient entry failure");
                    facts.put("clientCacheFailure", "transient-entry-local");

                    var beforeInvalid = connectionB.session.remoteSnapshot().orElseThrow();
                    var invalid = receivePublication(connectionB, beforeInvalid, true);
                    require(invalid.status() == SessionCollectionPublication.Status.INTRINSIC_INVALID,
                        "Domain-invalid publication was not rejected");
                    connectionB.session.failCompleteTransfer();
                    manager.failRemote();
                    require(connectionB.session.state() == ClientModelSession.State.INTRINSIC_DEFAULT_ONLY,
                        "Invalid publication did not close only the model session");
                    require(manager.snapshot().catalog().byModelId().size() == 1,
                        "Invalid publication damaged the intrinsic-default sentinel");
                    facts.put("domainInvalidPublication", "model-session-only");
                    facts.put("unaffectedSentinel", "intrinsic-default-visible");
                }
            }
            ownersClosed++;
            facts.put("clientSessionCreates", "2");
            facts.put("clientSessionCloses", Integer.toString(ownersClosed));
            facts.put("currentConnection", "connection-B-retired");
            facts.put("errorCount", "0");
            evidence.append("system", "system-result", "client-system", "system-result", Map.copyOf(facts));
        } catch (Throwable error) {
            failure = error;
            try {
                evidence.append("system", "client-failure", "client-endpoint", "endpoint-failure",
                    Map.of("error", error.getClass().getName(), "message", String.valueOf(error.getMessage())));
            } catch (Throwable evidenceFailure) {
                error.addSuppressed(evidenceFailure);
            }
            throw error;
        } finally {
            EndpointEvidence.writeNew(exit,
                Map.of("ownerClosed", ownersClosed == 2, "physicalSide", "client", "status",
                    failure == null ? "success" : "failure", "workingDirectoryEmpty",
                    EndpointEvidence.workingDirectoryEmpty()));
        }
    }

    private static void runExact100(String[] args) throws Throwable {
        var port = Integer.parseInt(args[0]);
        var ready = Path.of(args[1]);
        var exit = Path.of(args[2]);
        var evidence = new EndpointEvidence(args[4], "client", Path.of(args[3]));
        var fixtureRoot = Path.of(args[6]).toAbsolutePath().normalize();
        var input = Path.of(args[7]).toAbsolutePath().normalize();
        var nativeSha256 = MockNativeRuntime.initialize(Path.of(args[8]));
        var owners = new ArrayList<ScaleClientOwner>();
        var allOwners = new ArrayList<ScaleClientOwner>();
        Throwable failure = null;
        var ownersCreated = 0;
        var ownersClosed = 0;
        try (var fixture = MockScenarioFixture.create(fixtureRoot.resolve("fixture"))) {
            require(EndpointEvidence.workingDirectoryEmpty(), "Client working directory was not empty at process load");
            var messageCount = com.elfmcys.ysm.mock.classpath.ProductionWire.registeredMessageCount();
            require(messageCount > 0, "Production protocol registry was empty");
            EndpointEvidence.writeNew(ready,
                Map.of("bootstrap", "ordinary-java-main", "fixture", fixture.inventory(), "headless", true,
                    "nativeSha256", nativeSha256, "physicalSide", "client", "pid", ProcessHandle.current().pid(),
                    "port", port, "protocolMessageCount", messageCount, "workingDirectory",
                    Path.of("").toAbsolutePath().normalize().toString(), "workingDirectoryEmpty", true));
            evidence.append("scale", "client-load", "client-endpoint", "endpoint-load",
                Map.of("bootstrapMainClass", MockClientEndpoint.class.getName(), "childProcessCount",
                    Long.toString(ProcessHandle.current().descendants().count()), "physicalSide", "client",
                    "workingDirectoryEmpty", "true"));
            awaitInput(input, Duration.ofSeconds(20));
            var root = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
            var workload = MockExact100Input.parse(root);
            require(workload.playerCount() == 100 && workload.players().size() == 100,
                "Exact workload did not contain 100 logical players");
            var heapBefore = usedHeap();
            var started = System.nanoTime();
            for (var player : workload.players()) {
                for (var connection : player.connections()) {
                    var owner = ScaleClientOwner.open(port, evidence, fixture,
                        fixtureRoot.resolve("clients").resolve(connection.connectionId()), player.playerId(),
                        connection.connectionId(), connection.role());
                    allOwners.add(owner);
                    ownersCreated++;
                    if (connection.role().equals("cancel-rerequest")) {
                        owner.cancelAndRestart();
                    }
                    if (connection.role().equals("replace-old") || connection.role().equals("leave")) {
                        owner.cancelActivation("owner-shutdown");
                        owner.close();
                        ownersClosed++;
                    } else {
                        owners.add(owner);
                    }
                }
            }

            var readyCount = 0;
            var failedCount = 0;
            ScaleClientOwner invalid = null;
            for (var owner : List.copyOf(owners)) {
                var activation = owner.completeActivation();
                var state = activation.entries().get(fixture.asset("server-exact").identity().modelId());
                if (owner.role().equals("production-failure")) {
                    require(state instanceof ActivationSnapshot.Failed,
                        "Production source failure did not become an entry-local terminal");
                    failedCount++;
                } else {
                    require(
                        state instanceof ActivationSnapshot.Ready readyState && readyState.record().binding().content()
                            .representation().identity().equals(fixture.asset("server-exact").identity()),
                        "Scale activation did not preserve the exact representation");
                    readyCount++;
                }
                if (owner.role().equals("unauthorized-then-grant")) {
                    owner.verifyAuthorizationBoundary(fixture);
                } else if (owner.role().equals("invalid")) {
                    owner.sendInvalidRequest(fixture);
                    invalid = owner;
                }
            }
            require(invalid != null, "Scale action stream omitted the invalid request owner");
            invalid.close();
            owners.remove(invalid);
            ownersClosed++;
            var currentBeforeShutdown = owners.size();
            var currentConnections =
                owners.stream().map(ScaleClientOwner::connectionId).collect(Collectors.toUnmodifiableSet());
            for (var owner : allOwners) {
                owner.observeConnectionState(currentConnections.contains(owner.connectionId()));
            }
            for (var index = owners.size() - 1; index >= 0; index--) {
                owners.get(index).close();
                ownersClosed++;
            }
            owners.clear();
            var activationTerminals = allOwners.stream().mapToInt(ScaleClientOwner::activationTerminals).sum();
            var activationOwnerShutdown =
                allOwners.stream().mapToInt(owner -> owner.activationTerminalCount("owner-shutdown")).sum();
            var activationFailed = allOwners.stream().mapToInt(owner -> owner.activationTerminalCount("failed")).sum();
            var publicationEntries = allOwners.stream().mapToInt(ScaleClientOwner::publicationEntries).sum();
            var remainingActivationOwners = allOwners.stream().filter(owner -> !owner.closed()).count();
            var elapsed = System.nanoTime() - started;
            evidence.append("scale", "soft-metrics", "client-system", "soft-metrics", Map.of("correctnessThreshold",
                "none", "heapDelta", Long.toString(usedHeap() - heapBefore), "wallNanos", Long.toString(elapsed)));
            evidence.append("scale", "system-result", "client-system", "system-result",
                Map.ofEntries(Map.entry("activationFailed", Integer.toString(activationFailed)),
                    Map.entry("activationOwnerShutdown", Integer.toString(activationOwnerShutdown)),
                    Map.entry("activationReady", Integer.toString(readyCount)),
                    Map.entry("activationTerminals", Integer.toString(activationTerminals)),
                    Map.entry("currentLogicalOwners", Integer.toString(currentBeforeShutdown)),
                    Map.entry("errorCount", "0"),
                    Map.entry("logicalPlayers", Integer.toString(workload.players().size())),
                    Map.entry("ownerCloses", Integer.toString(ownersClosed)),
                    Map.entry("ownerCreates", Integer.toString(ownersCreated)),
                    Map.entry("publicationEntries", Integer.toString(publicationEntries)),
                    Map.entry("remainingActivationOwners", Long.toString(remainingActivationOwners))));
        } catch (Throwable error) {
            failure = error;
            for (var index = owners.size() - 1; index >= 0; index--) {
                try {
                    owners.get(index).close();
                } catch (Throwable closeFailure) {
                    error.addSuppressed(closeFailure);
                }
            }
            try {
                evidence.append("scale", "client-failure", "client-endpoint", "endpoint-failure",
                    Map.of("error", error.getClass().getName(), "message", String.valueOf(error.getMessage())));
            } catch (Throwable evidenceFailure) {
                error.addSuppressed(evidenceFailure);
            }
            throw error;
        } finally {
            EndpointEvidence.writeNew(exit,
                Map.of("ownerClosed", ownersClosed == ownersCreated, "physicalSide", "client", "status",
                    failure == null ? "success" : "failure", "workingDirectoryEmpty",
                    EndpointEvidence.workingDirectoryEmpty()));
        }
    }

    private static long usedHeap() {
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static Connection connect(int port, String connectionId, EndpointEvidence evidence, CatalogSnapshot local)
        throws Exception {
        var socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 10_000);
        socket.setSoTimeout(15_000);
        var carrier = new SocketByteCarrier(socket);
        var session = new ClientModelSession(SessionMode.AUTO, true, local);
        var sender = new ClientAssetTransfer.Sender() {
            @Override
            public void request(ProtoMessage<?> request) {
                sendUnchecked(carrier, evidence, connectionId, request);
            }

            @Override
            public void cancel(ResourceTransferCancelProto cancel) {
                sendUnchecked(carrier, evidence, connectionId, cancel);
            }
        };
        var transfers = new ClientAssetTransfer(sender, session);
        var connection = new Connection(connectionId, socket, carrier, session, transfers,
            new SessionCollectionPublication.Receiver(Map.of()), evidence);
        var hello = MockWireIo.receive(carrier, evidence, connectionId, MessageDirection.SERVER_TO_CLIENT);
        require(hello.message() instanceof ServerHelloProto, "Connection did not begin with ServerHello");
        var value = (ServerHelloProto)hello.message();
        var response = session.onServerHello(value.protocolVersion(), value.syncRoaming()).orElseThrow();
        require(response.accepted(), "Production client declined the server hello");
        MockWireIo.send(carrier, evidence, connectionId,
            SessionResponseProto.newBuilder().setDecision(SessionDecision.ACCEPT).build());
        return connection;
    }

    private static RemotePublicationSnapshot receiveFull(Connection connection, RemotePublicationSnapshot previous)
        throws Exception {
        var result = receivePublication(connection, previous, true);
        require(result.status() == SessionCollectionPublication.Status.FULL,
            "Full publication did not complete atomically");
        return result.publication();
    }

    private static RemotePublicationSnapshot receiveDelta(Connection connection, RemotePublicationSnapshot previous)
        throws Exception {
        var result = receivePublication(connection, previous, false);
        require(result.status() == SessionCollectionPublication.Status.DELTA,
            "Compatible delta did not complete atomically: " + result.status());
        return result.publication();
    }

    private static SessionCollectionPublication.Result receivePublication(Connection connection,
        RemotePublicationSnapshot previous, boolean full) throws Exception {
        while (true) {
            var decoded = MockWireIo.receive(connection.carrier, connection.evidence, connection.id,
                MessageDirection.SERVER_TO_CLIENT);
            final SessionCollectionPublication.Result result;
            if (full && decoded.message() instanceof SessionFullFragmentProto value) {
                result = connection.receiver.acceptFull(value, previous);
            } else if (!full && decoded.message() instanceof SessionDeltaFragmentProto value) {
                result = connection.receiver.acceptDelta(value, previous);
            } else {
                throw new IllegalStateException(
                    "Expected " + (full ? "full" : "delta") + " publication but received " + decoded.messageType());
            }
            if (result.status() != SessionCollectionPublication.Status.PENDING) {
                return result;
            }
        }
    }

    private static ActivationSnapshot settle(CompletableFuture<ActivationSnapshot> result, Connection connection)
        throws Exception {
        pumpUntil(result, connection);
        return result.join();
    }

    private static void pumpUntil(CompletableFuture<?> result, Connection connection) throws Exception {
        while (!result.isDone()) {
            var decoded = MockWireIo.receive(connection.carrier, connection.evidence, connection.id,
                MessageDirection.SERVER_TO_CLIENT);
            if (decoded.message() instanceof MetadataPrefixFragmentProto value) {
                connection.transfers.acceptMetadata(value);
            } else if (decoded.message() instanceof ChunkFragmentProto value) {
                try (var bytes = ArrayBuffer.borrow(decoded.attachment())) {
                    connection.transfers.acceptChunk(value, bytes);
                }
            } else if (decoded.message() instanceof ResourceTransferFailureProto value) {
                connection.transfers.fail(value);
            } else {
                throw new IllegalStateException("Unexpected typed transfer response " + decoded.messageType());
            }
        }
    }

    private static void publish(ClientModelSession session,
        com.elfmcys.ysm.model.catalog.client.ClientCatalogManager manager, ActivationSnapshot activation) {
        session.publishActivation(activation);
        manager.publishSession(activation);
    }

    private static void verifyInitial(MockScenarioFixture fixture, ActivationSnapshot activation,
        List<ModelFileIdentity> requested, Map<String, String> facts) {
        requireReadyIdentity(activation, fixture.asset("active-exact").identity(),
            fixture.asset("active-exact").identity());
        requireReadyIdentity(activation, fixture.asset("local-exact").identity(),
            fixture.asset("local-exact").identity());
        requireReadyIdentity(activation, fixture.asset("same-model-remote").identity(),
            fixture.asset("same-model-local").identity());
        requireReadyIdentity(activation, fixture.asset("cache-exact").identity(),
            fixture.asset("cache-exact").identity());
        requireReadyIdentity(activation, fixture.asset("server-exact").identity(),
            fixture.asset("server-exact").identity());
        require(requested.equals(List.of(fixture.asset("server-exact").identity())),
            "Initial source request did not contain only the server-exact entry");
        facts.put("initialPublicationEntries", "5");
        facts.put("sourceActiveExact", "active-exact");
        facts.put("sourceLocalExact", "local-exact");
        facts.put("sourceSameModel", "local-same-model-representation");
        facts.put("sourceCacheExact", "remote-cache-exact");
        facts.put("sourceServerExact", "server-exact");
    }

    private static void requireReadyIdentity(ActivationSnapshot activation, ModelFileIdentity publication,
        ModelFileIdentity actual) {
        var state = activation.entries().get(publication.modelId());
        require(
            state instanceof ActivationSnapshot.Ready ready
                && ready.record().binding().content().representation().identity().equals(actual),
            "Ready activation did not preserve exact representation identity for " + publication);
    }

    private static void requireAccessFailure(CompletableFuture<?> result, String description) {
        try {
            result.join();
            throw new IllegalStateException(description + " unexpectedly succeeded");
        } catch (CompletionException failure) {
            var cause = failure.getCause();
            require(cause instanceof AssetLoadException value && value.reason() == AssetLoadException.Reason.ACCESS,
                description + " did not expose an access failure");
        }
    }

    private static void sendUnchecked(SocketByteCarrier carrier, EndpointEvidence evidence, String connectionId,
        ProtoMessage<?> message) {
        try {
            MockWireIo.send(carrier, evidence, connectionId, message);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to send typed client frame", failure);
        }
    }

    private static void awaitInput(Path input, Duration guard) throws Exception {
        var deadline = System.nanoTime() + guard.toNanos();
        while (!Files.isRegularFile(input)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out waiting for immutable action input");
            }
            Thread.sleep(10);
        }
        require(Files.size(input) > 0, "Immutable action input was empty");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class ScaleClientOwner implements AutoCloseable {
        private final String playerId;
        private final String role;
        private final MockClientCatalog catalogs;
        private final RemoteModelStore store;
        private final ClientCatalogManager manager;
        private final Connection connection;
        private final EndpointEvidence evidence;
        private RemoteCatalogActivation activation;
        private CompletableFuture<ActivationSnapshot> pending;
        private final List<CompletableFuture<Void>> pressureTransfers = new ArrayList<>();
        private final List<String> activationTerminals = new ArrayList<>();
        private final int publicationEntries;
        private boolean ready;
        private boolean closed;

        private static ScaleClientOwner open(int port, EndpointEvidence evidence, MockScenarioFixture fixture,
            Path root, String playerId, String connectionId, String role) throws Exception {
            Files.createDirectories(root);
            var catalogs = MockClientCatalog.open(root.resolve("catalog"), fixture.asset("default").file(), List.of());
            var store = new RemoteModelStore(root.resolve("remote-store"));
            Connection connection = null;
            try {
                var manager = catalogs.manager();
                connection = connect(port, connectionId, evidence, manager.localState().snapshot());
                var publication = receiveFull(connection, null);
                connection.session.publishFull(publication);
                var owner = new ScaleClientOwner(playerId, role, catalogs, store, manager, connection, evidence,
                    publication.entries().size());
                owner.beginActivation();
                if (role.equals("pressure")) {
                    owner.beginPressureTransfers(fixture);
                }
                evidence.append(connectionId, "publication-full", "client-session", "client-publication", Map
                    .of("entries", Integer.toString(publication.entries().size()), "playerId", playerId, "role", role));
                return owner;
            } catch (Throwable failure) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Throwable closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                try {
                    store.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                try {
                    catalogs.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private ScaleClientOwner(String playerId, String role, MockClientCatalog catalogs, RemoteModelStore store,
            ClientCatalogManager manager, Connection connection, EndpointEvidence evidence, int publicationEntries) {
            this.playerId = playerId;
            this.role = role;
            this.catalogs = catalogs;
            this.store = store;
            this.manager = manager;
            this.connection = connection;
            this.evidence = evidence;
            this.publicationEntries = publicationEntries;
        }

        private void beginActivation() {
            activation = new com.elfmcys.ysm.model.session.client.RemoteCatalogActivation(manager::localIndex,
                manager.beginRemote(), store, connection.transfers, Runnable::run);
            pending = activation.activate(connection.session.activationSnapshot().orElseThrow());
        }

        private void beginPressureTransfers(MockScenarioFixture fixture) {
            for (var index = 0; index < 4; index++) {
                pressureTransfers.add(connection.transfers
                    .fetchMetadata(List.of(fixture.asset("server-exact").identity()), (ignored, bytes) -> {
                    }));
            }
        }

        private void cancelAndRestart() throws Exception {
            cancelActivation("cancellation");
            beginActivation();
        }

        private void cancelActivation(String classification) throws Exception {
            require(activation != null && pending != null && !pending.isDone(),
                "Scale activation was not pending at cancellation");
            activation.close();
            activation = null;
            pending = null;
            ready = false;
            activationTerminals.add(classification);
            evidence.append(connection.id, "activation-cancel", "client-activation", "client-activation-terminal",
                Map.of("classification", classification, "playerId", playerId, "role", role));
        }

        private ActivationSnapshot completeActivation() throws Exception {
            require(activation != null && pending != null, "Scale activation owner was not live");
            var result = settle(pending, connection);
            publish(connection.session, manager, result);
            pending = null;
            var state = result.entries().values().stream().findFirst().orElseThrow();
            var classification = state instanceof ActivationSnapshot.Ready ? "ready" : "failed";
            ready = state instanceof ActivationSnapshot.Ready;
            activationTerminals.add(classification);
            evidence.append(connection.id, "activation-terminal", "client-activation", "client-activation-terminal",
                Map.of("classification", classification, "playerId", playerId, "role", role));
            if (role.equals("pressure")) {
                for (var index = 0; index < 3; index++) {
                    pumpUntil(pressureTransfers.get(index), connection);
                    pressureTransfers.get(index).join();
                }
                pumpUntil(pressureTransfers.get(3), connection);
                requireAccessFailure(pressureTransfers.get(3), "Pressure hard-bound request");
                evidence.append(connection.id, "pressure-bound", "client-transfer", "pressure-boundary",
                    Map.of("accepted", "4", "hardLimit", "4", "rejectedBusy", "1"));
            }
            return result;
        }

        private void verifyAuthorizationBoundary(MockScenarioFixture fixture) throws Exception {
            var chunk = fixture.asset("server-exact").content().modelFile().getFileView().getAssetView().getChunkTable()
                .values().stream().filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                .findFirst().orElseThrow();
            var rejected = connection.transfers.fetch(fixture.asset("server-exact").identity(), List.of(chunk),
                (ignored, bytes) -> {
                });
            pumpUntil(rejected, connection);
            requireAccessFailure(rejected, "Scale unauthorized chunk request");
            var granted = receiveDelta(connection, connection.session.remoteSnapshot().orElseThrow());
            connection.session.publishPublication(granted);
            var grantActivation =
                settle(activation.activate(connection.session.activationSnapshot().orElseThrow()), connection);
            publish(connection.session, manager, grantActivation);
            var received = new int[1];
            var accepted = connection.transfers.fetch(fixture.asset("server-exact").identity(), List.of(chunk),
                (ignored, bytes) -> received[0] += bytes.size());
            pumpUntil(accepted, connection);
            accepted.join();
            require(received[0] == chunk.size(), "Granted scale request did not receive the exact chunk bytes");
            evidence.append(connection.id, "authorization-boundary", "client-transfer", "authorization-boundary",
                Map.of("acceptedAfterGrant", "true", "rejectedBeforeDispatch", "true"));
        }

        private void sendInvalidRequest(MockScenarioFixture fixture) throws Exception {
            var chunk = fixture.asset("server-exact").content().modelFile().getFileView().getAssetView().getChunkTable()
                .values().stream().filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                .findFirst().orElseThrow();
            MockWireIo.send(connection.carrier, evidence, connection.id,
                ModelChunkRequestProto.newBuilder().setDataTransferId(0)
                    .setModelId(ByteBuffer.wrap(fixture.asset("server-exact").identity().modelId().bytes()))
                    .setContainerId(ByteBuffer.wrap(fixture.asset("server-exact").identity().containerId().bytes()))
                    .addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                        .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                        .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build())
                    .build());
            evidence.append(connection.id, "invalid-request", "client-transfer", "invalid-request-sent",
                Map.of("authorityEffect", "none"));
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            Throwable failure = null;
            if (pending != null && !pending.isDone()) {
                try {
                    cancelActivation("owner-shutdown");
                } catch (Throwable closeFailure) {
                    failure = closeFailure;
                }
            }
            if (activation != null) {
                try {
                    activation.close();
                } catch (Throwable closeFailure) {
                    failure = append(failure, closeFailure);
                }
                activation = null;
            }
            try {
                connection.close();
            } catch (Throwable closeFailure) {
                failure = append(failure, closeFailure);
            }
            try {
                store.close();
            } catch (Throwable closeFailure) {
                failure = append(failure, closeFailure);
            }
            try {
                catalogs.close();
            } catch (Throwable closeFailure) {
                failure = append(failure, closeFailure);
            }
            if (failure != null) {
                if (failure instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(failure);
            }
        }

        private void observeConnectionState(boolean current) throws Exception {
            evidence.append(connection.id, "client-connection-state", "client-session", "client-connection-state",
                Map.of("current", Boolean.toString(current), "playerId", playerId, "ready",
                    Boolean.toString(current && ready)));
        }

        private static Throwable append(Throwable prior, Throwable next) {
            if (prior == null) {
                return next;
            }
            prior.addSuppressed(next);
            return prior;
        }

        private String role() {
            return role;
        }

        private String connectionId() {
            return connection.id;
        }

        private int activationTerminals() {
            return activationTerminals.size();
        }

        private int activationTerminalCount(String classification) {
            return (int)activationTerminals.stream().filter(classification::equals).count();
        }

        private int publicationEntries() {
            return publicationEntries;
        }

        private boolean closed() {
            return closed;
        }
    }

    private static final class Connection implements AutoCloseable {
        private final String id;
        private final Socket socket;
        private final SocketByteCarrier carrier;
        private final ClientModelSession session;
        private final ClientAssetTransfer transfers;
        private final SessionCollectionPublication.Receiver receiver;
        private final EndpointEvidence evidence;

        private Connection(String id, Socket socket, SocketByteCarrier carrier, ClientModelSession session,
            ClientAssetTransfer transfers, SessionCollectionPublication.Receiver receiver, EndpointEvidence evidence) {
            this.id = id;
            this.socket = socket;
            this.carrier = carrier;
            this.session = session;
            this.transfers = transfers;
            this.receiver = receiver;
            this.evidence = evidence;
        }

        @Override
        public void close() throws Exception {
            transfers.close();
            session.close();
            carrier.close();
            if (!socket.isClosed()) {
                socket.close();
            }
            evidence.append(id, "client-close", "client-session", "owner-close",
                Map.of("sessionState", session.state().name(), "terminalCount", "1"));
        }
    }

    private static final class HoldingMetadataSource implements RemoteMetadataFetcher {
        private final RemoteMetadataFetcher delegate;
        private final ModelFileIdentity heldIdentity;
        private final List<ModelFileIdentity> requested = new ArrayList<>();
        private final CompletableFuture<Void> networkCompleted = new CompletableFuture<>();
        private final CancelResistantFuture held = new CancelResistantFuture();
        private MetadataReceiver receiver;
        private byte[] bytes;

        private HoldingMetadataSource(RemoteMetadataFetcher delegate, ModelFileIdentity heldIdentity) {
            this.delegate = delegate;
            this.heldIdentity = heldIdentity;
        }

        @Override
        public synchronized CompletableFuture<Void> fetchMetadata(List<ModelFileIdentity> identities,
            MetadataReceiver receiver) {
            requested.addAll(identities);
            if (!identities.contains(heldIdentity)) {
                return delegate.fetchMetadata(identities, receiver);
            }
            require(identities.size() == 1, "Held source action must contain one exact identity");
            this.receiver = receiver;
            var transfer = delegate.fetchMetadata(identities, (identity, input) -> {
                require(identity.equals(heldIdentity), "Held source returned a different exact identity");
                var copy = new byte[input.size()];
                input.nio().get(copy);
                synchronized (HoldingMetadataSource.this) {
                    bytes = copy;
                }
            });
            transfer.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    networkCompleted.complete(null);
                } else {
                    networkCompleted.completeExceptionally(failure);
                    held.completeExceptionally(failure);
                }
            });
            return held;
        }

        private synchronized List<ModelFileIdentity> requestedIdentities() {
            return List.copyOf(requested);
        }

        private CompletableFuture<Void> networkCompleted() {
            return networkCompleted;
        }

        private synchronized void releaseLate() throws Exception {
            require(receiver != null && bytes != null && networkCompleted.isDone(),
                "Held source was not ready for late release");
            try (var input = ArrayBuffer.borrow(bytes)) {
                receiver.accept(heldIdentity, input);
            }
            require(held.complete(null), "Held source terminal was already published");
        }
    }

    private static final class CancelResistantFuture extends CompletableFuture<Void> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }
}
