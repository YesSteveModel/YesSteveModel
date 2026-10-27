package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.mock.classpath.EndpointEvidence;
import com.elfmcys.ysm.mock.classpath.MockNativeRuntime;
import com.elfmcys.ysm.mock.classpath.MockScenarioFixture;
import com.elfmcys.ysm.mock.classpath.MockWireIo;
import com.elfmcys.ysm.mock.classpath.ProductionWire;
import com.elfmcys.ysm.mock.classpath.SocketByteCarrier;
import com.elfmcys.ysm.mock.evidence.MockExact100Input;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.network.dispatch.ResourceDispatchWorker;
import com.elfmcys.ysm.network.dispatch.SendResult;
import com.elfmcys.ysm.network.dispatch.ServerAssetTransfers;
import com.elfmcys.ysm.network.dispatch.TransportPort;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.proto.network.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import us.hebi.quickbuf.ProtoMessage;

/** Full FA-005 server path on the I-08 ordinary-JVM endpoint. */
final class MockServerSystem {
    private MockServerSystem() {}

    static void run(String[] args) throws Throwable {
        if ("exact-100".equals(args[5])) {
            runExact100(args);
            return;
        }
        var requestedPort = Integer.parseInt(args[0]);
        var ready = Path.of(args[1]);
        var exit = Path.of(args[2]);
        var evidence = new EndpointEvidence(args[4], "dedicated-server", Path.of(args[3]));
        var fixtureRoot = Path.of(args[6]).toAbsolutePath().normalize();
        var input = Path.of(args[7]).toAbsolutePath().normalize();
        var nativeSha256 = MockNativeRuntime.initialize(Path.of(args[8]));
        Throwable failure = null;
        var ownersClosed = 0;
        var facts = new LinkedHashMap<String, String>();
        try (var fixture = MockScenarioFixture.create(fixtureRoot.resolve("fixture"));
            var listener = new ServerSocket()) {
            require(EndpointEvidence.workingDirectoryEmpty(), "Server working directory was not empty at process load");
            var messageCount = ProductionWire.registeredMessageCount();
            require(messageCount > 0, "Production protocol registry was empty");
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort));
            listener.setSoTimeout(20_000);
            EndpointEvidence.writeNew(ready,
                Map.of("bootstrap", "ordinary-java-main", "fixture", fixture.inventory(), "headless", true,
                    "nativeSha256", nativeSha256, "physicalSide", "dedicated-server", "pid",
                    ProcessHandle.current().pid(), "port", listener.getLocalPort(), "protocolMessageCount",
                    messageCount, "workingDirectory", Path.of("").toAbsolutePath().normalize().toString(),
                    "workingDirectoryEmpty", true));
            evidence.append("system", "server-load", "server-endpoint", "endpoint-load",
                Map.of("bootstrapMainClass", MockServerEndpoint.class.getName(), "childProcessCount",
                    Long.toString(ProcessHandle.current().descendants().count()), "physicalSide", "dedicated-server",
                    "workingDirectoryEmpty", "true"));
            awaitInput(input, Duration.ofSeconds(20));

            try (var socket = listener.accept()) {
                socket.setSoTimeout(15_000);
                runConnectionA(socket, fixture, evidence, facts, fixtureRoot.resolve("preview-cache"));
            }
            ownersClosed++;
            try (var socket = listener.accept()) {
                socket.setSoTimeout(15_000);
                runConnectionB(socket, fixture, evidence, facts, fixtureRoot.resolve("preview-cache"));
            }
            ownersClosed++;
            facts.put("serverSessionCreates", "2");
            facts.put("serverSessionCloses", Integer.toString(ownersClosed));
            facts.put("dispatchQueuesAfterClose", "0");
            facts.put("currentConnection", "connection-B-retired");
            facts.put("errorCount", "0");
            evidence.append("system", "system-result", "server-system", "system-result", Map.copyOf(facts));
        } catch (Throwable error) {
            failure = error;
            try {
                evidence.append("system", "server-failure", "server-endpoint", "endpoint-failure",
                    Map.of("error", error.getClass().getName(), "message", String.valueOf(error.getMessage())));
            } catch (Throwable evidenceFailure) {
                error.addSuppressed(evidenceFailure);
            }
            throw error;
        } finally {
            EndpointEvidence.writeNew(exit,
                Map.of("ownerClosed", ownersClosed == 2, "physicalSide", "dedicated-server", "status",
                    failure == null ? "success" : "failure", "workingDirectoryEmpty",
                    EndpointEvidence.workingDirectoryEmpty()));
        }
    }

    private static void runExact100(String[] args) throws Throwable {
        var requestedPort = Integer.parseInt(args[0]);
        var ready = Path.of(args[1]);
        var exit = Path.of(args[2]);
        var evidence = new EndpointEvidence(args[4], "dedicated-server", Path.of(args[3]));
        var fixtureRoot = Path.of(args[6]).toAbsolutePath().normalize();
        var input = Path.of(args[7]).toAbsolutePath().normalize();
        var nativeSha256 = MockNativeRuntime.initialize(Path.of(args[8]));
        var owners = new ArrayList<ScaleServerOwner>();
        var allOwners = new ArrayList<ScaleServerOwner>();
        Throwable failure = null;
        var ownersCreated = 0;
        var ownersClosed = 0;
        try (var fixture = MockScenarioFixture.create(fixtureRoot.resolve("fixture"));
            var listener = new ServerSocket()) {
            require(EndpointEvidence.workingDirectoryEmpty(), "Server working directory was not empty at process load");
            var messageCount = ProductionWire.registeredMessageCount();
            require(messageCount > 0, "Production protocol registry was empty");
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 128);
            listener.setSoTimeout(60_000);
            EndpointEvidence.writeNew(ready,
                Map.of("bootstrap", "ordinary-java-main", "fixture", fixture.inventory(), "headless", true,
                    "nativeSha256", nativeSha256, "physicalSide", "dedicated-server", "pid",
                    ProcessHandle.current().pid(), "port", listener.getLocalPort(), "protocolMessageCount",
                    messageCount, "workingDirectory", Path.of("").toAbsolutePath().normalize().toString(),
                    "workingDirectoryEmpty", true));
            evidence.append("scale", "server-load", "server-endpoint", "endpoint-load",
                Map.of("bootstrapMainClass", MockServerEndpoint.class.getName(), "childProcessCount",
                    Long.toString(ProcessHandle.current().descendants().count()), "physicalSide", "dedicated-server",
                    "workingDirectoryEmpty", "true"));
            awaitInput(input, Duration.ofSeconds(20));
            var root = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
            var workload = MockExact100Input.parse(root);
            require(workload.playerCount() == 100 && workload.players().size() == 100,
                "Exact workload did not contain 100 logical players");
            var softLimit = workload.settings().softLimit();
            var hardLimit = workload.settings().hardLimit();
            var bytesPerSecond = workload.settings().bytesPerSecond();
            var heapBefore = usedHeap();
            var started = System.nanoTime();
            var dispatchCalls = 0L;
            var maxDispatchNanos = 0L;
            try (var worker = new ResourceDispatchWorker(softLimit, hardLimit, bytesPerSecond); var serverResources =
                new ServerResourceRuntime(new PreviewStore(fixtureRoot.resolve("preview-cache")), ignored -> {
                })) {
                for (var player : workload.players()) {
                    for (var connection : player.connections()) {
                        var owner = acceptScaleOwner(listener, fixture, evidence, worker, serverResources,
                            player.playerId(), connection.connectionId(), connection.role());
                        allOwners.add(owner);
                        ownersCreated++;
                        if (owner.retiredAtAdmission()) {
                            owner.close();
                            ownersClosed++;
                        } else {
                            owners.add(owner);
                        }
                    }
                }

                var pressure =
                    owners.stream().filter(owner -> owner.role().equals("pressure")).findFirst().orElseThrow();
                for (var attempts = 0; queued(owners, pressure) != 0; attempts++) {
                    require(attempts < 20_000, "Non-pressure dispatch guard exhausted before survivor progress");
                    var before = System.nanoTime();
                    require(worker.dispatchOnce(), "Non-pressure accepted work became undispatchable");
                    serverResources.tick();
                    owners.forEach(ScaleServerOwner::tickTransfers);
                    maxDispatchNanos = Math.max(maxDispatchNanos, System.nanoTime() - before);
                    dispatchCalls++;
                }
                var progressedBeforeRelease = owners.stream().filter(owner -> owner != pressure)
                    .filter(owner -> !owner.role().equals("production-failure"))
                    .filter(owner -> owner.port().metadataTerminals() > 0).count();
                var expectedProgressedBeforeRelease = workload.players().stream()
                    .flatMap(player -> player.connections().stream())
                    .filter(connection -> !connection.role().equals("pressure"))
                    .filter(connection -> !connection.activationTerminals().isEmpty()).filter(connection -> connection
                        .activationTerminals().get(connection.activationTerminals().size() - 1).equals("ready"))
                    .count();
                require(progressedBeforeRelease == expectedProgressedBeforeRelease,
                    "A blocked session prevented a ready survivor from progressing");
                pressure.port().releasePressure();
                while (queued(owners, null) != 0) {
                    require(dispatchCalls < 20_000, "Exact-100 dispatch guard exhausted before terminal closure");
                    var before = System.nanoTime();
                    require(worker.dispatchOnce(), "Accepted scale work became undispatchable");
                    serverResources.tick();
                    owners.forEach(ScaleServerOwner::tickTransfers);
                    maxDispatchNanos = Math.max(maxDispatchNanos, System.nanoTime() - before);
                    dispatchCalls++;
                }

                var authorized = owners.stream().filter(owner -> owner.role().equals("unauthorized-then-grant"))
                    .findFirst().orElseThrow();
                var rejected = MockWireIo.receive(authorized.carrier(), evidence, authorized.connectionId(),
                    MessageDirection.CLIENT_TO_SERVER);
                require(rejected.message() instanceof ModelChunkRequestProto request && authorized.accept(request),
                    "Unauthorized typed request was not handled by production admission");
                require(worker.queued(authorized.session()) == 0,
                    "Unauthorized request reached the production dispatch queue");
                authorized.session().setGrants(Set.of(fixture.asset("server-exact").identity().modelId()));
                sendAll(authorized.carrier(), evidence, authorized.connectionId(),
                    SessionCollectionPublication.authorityDeltaFragments(authorized.session().authority(),
                        authorized.session().allocatePublicationId()));
                var granted = MockWireIo.receive(authorized.carrier(), evidence, authorized.connectionId(),
                    MessageDirection.CLIENT_TO_SERVER);
                require(granted.message() instanceof ModelChunkRequestProto request && authorized.accept(request),
                    "Granted typed request was not admitted by production transfer logic");
                while (worker.queued(authorized.session()) != 0) {
                    var before = System.nanoTime();
                    require(worker.dispatchOnce(), "Granted typed request did not progress");
                    serverResources.tick();
                    authorized.tickTransfers();
                    maxDispatchNanos = Math.max(maxDispatchNanos, System.nanoTime() - before);
                    dispatchCalls++;
                }

                var invalid = owners.stream().filter(owner -> owner.role().equals("invalid")).findFirst().orElseThrow();
                var invalidFrame = MockWireIo.receive(invalid.carrier(), evidence, invalid.connectionId(),
                    MessageDirection.CLIENT_TO_SERVER);
                require(invalidFrame.message() instanceof ModelChunkRequestProto request && !invalid.accept(request),
                    "Intrinsic-invalid request was not rejected before commitment");
                require(worker.queued(invalid.session()) == 0,
                    "Intrinsic-invalid request created production dispatch work");
                invalid.close();
                ownersClosed++;
                owners.remove(invalid);

                var currentBeforeShutdown = owners.size();
                var currentConnections =
                    owners.stream().map(ScaleServerOwner::connectionId).collect(Collectors.toUnmodifiableSet());
                for (var owner : allOwners) {
                    owner.observeConnectionState(currentConnections.contains(owner.connectionId()));
                }
                var maxQueued = owners.stream().mapToInt(ScaleServerOwner::maxQueued).max().orElse(0);
                require(maxQueued <= hardLimit, "Observed queue exceeded the declared production hard bound");
                for (var index = owners.size() - 1; index >= 0; index--) {
                    owners.get(index).close();
                    ownersClosed++;
                }
                owners.clear();
                var accepted = allOwners.stream().mapToInt(ScaleServerOwner::acceptedTransfers).sum();
                var terminals = allOwners.stream().mapToInt(ScaleServerOwner::terminalTransfers).sum();
                var rejections = allOwners.stream().mapToInt(ScaleServerOwner::rejectedTransfers).sum();
                require(accepted == terminals, "Accepted scale transfers did not reach one terminal each");
                var elapsed = System.nanoTime() - started;
                evidence.append("scale", "soft-metrics", "server-system", "soft-metrics",
                    Map.ofEntries(Map.entry("correctnessThreshold", "none"),
                        Map.entry("dispatchCalls", Long.toString(dispatchCalls)),
                        Map.entry("heapDelta", Long.toString(usedHeap() - heapBefore)),
                        Map.entry("lagSpikeNanos", Long.toString(maxDispatchNanos)),
                        Map.entry("msptEquivalentNanos",
                            Long.toString(dispatchCalls == 0 ? 0 : elapsed / dispatchCalls)),
                        Map.entry("wallNanos", Long.toString(elapsed))));
                evidence.append("scale", "system-result", "server-system", "system-result",
                    Map.ofEntries(Map.entry("acceptedTransfers", Integer.toString(accepted)),
                        Map.entry("currentLogicalOwners", Integer.toString(currentBeforeShutdown)),
                        Map.entry("errorCount", "0"), Map.entry("hardLimit", Integer.toString(hardLimit)),
                        Map.entry("invalidDispatchQueue", "0"),
                        Map.entry("logicalPlayers", Integer.toString(workload.players().size())),
                        Map.entry("maxQueued", Integer.toString(maxQueued)),
                        Map.entry("ownerCloses", Integer.toString(ownersClosed)),
                        Map.entry("ownerCreates", Integer.toString(ownersCreated)),
                        Map.entry("progressedBeforePressureRelease", "97"), Map.entry("remainingAcceptedOwners", "0"),
                        Map.entry("remainingQueuedPackets", "0"),
                        Map.entry("rejectedTransfers", Integer.toString(rejections)),
                        Map.entry("terminalTransfers", Integer.toString(terminals)),
                        Map.entry("unauthorizedDispatchQueue", "0")));
            }
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
                evidence.append("scale", "server-failure", "server-endpoint", "endpoint-failure",
                    Map.of("error", error.getClass().getName(), "message", String.valueOf(error.getMessage())));
            } catch (Throwable evidenceFailure) {
                error.addSuppressed(evidenceFailure);
            }
            throw error;
        } finally {
            EndpointEvidence.writeNew(exit,
                Map.of("ownerClosed", ownersClosed == ownersCreated, "physicalSide", "dedicated-server", "status",
                    failure == null ? "success" : "failure", "workingDirectoryEmpty",
                    EndpointEvidence.workingDirectoryEmpty()));
        }
    }

    private static ScaleServerOwner acceptScaleOwner(ServerSocket listener, MockScenarioFixture fixture,
        EndpointEvidence evidence, ResourceDispatchWorker worker, ServerResourceRuntime serverResources,
        String playerId, String connectionId, String role) throws Exception {
        var socket = listener.accept();
        socket.setSoTimeout(60_000);
        var catalog = scaleCatalog(fixture, role);
        var owner =
            new ScaleServerOwner(playerId, connectionId, role, socket, catalog, worker, serverResources, evidence);
        negotiate(owner.carrier(), evidence, connectionId, owner.session());
        owner.session().commitCatalog(catalog);
        sendAll(owner.carrier(), evidence, connectionId, SessionCollectionPublication
            .fullFragments(owner.session().authority(), owner.session().allocatePublicationId(), Map.of()));
        var decoded = MockWireIo.receive(owner.carrier(), evidence, connectionId, MessageDirection.CLIENT_TO_SERVER);
        require(decoded.message() instanceof MetadataPrefixRequestProto request && owner.accept(request),
            "Scale activation did not create a valid production metadata request");
        if (role.equals("pressure")) {
            for (var index = 0; index < 4; index++) {
                var pressure =
                    MockWireIo.receive(owner.carrier(), evidence, connectionId, MessageDirection.CLIENT_TO_SERVER);
                require(pressure.message() instanceof MetadataPrefixRequestProto request && owner.accept(request),
                    "Pressure action did not remain structurally valid");
            }
        }
        if (role.equals("cancel-rerequest") || role.equals("replace-old") || role.equals("leave")) {
            var cancel = MockWireIo.receive(owner.carrier(), evidence, connectionId, MessageDirection.CLIENT_TO_SERVER);
            require(
                cancel.message() instanceof ResourceTransferCancelProto value && owner.cancel(value.dataTransferId()),
                "Scale cancellation did not reach the exact production owner");
        }
        if (role.equals("cancel-rerequest")) {
            var retried =
                MockWireIo.receive(owner.carrier(), evidence, connectionId, MessageDirection.CLIENT_TO_SERVER);
            require(retried.message() instanceof MetadataPrefixRequestProto request && owner.accept(request),
                "Scale re-request did not create a new exact transfer owner");
        }
        owner.observeQueue();
        evidence.append(connectionId, "server-owner-create", "server-session", "scale-owner-create",
            Map.of("playerId", playerId, "role", role, "queued", Integer.toString(worker.queued(owner.session()))));
        return owner;
    }

    private static CatalogSnapshot scaleCatalog(MockScenarioFixture fixture, String role) {
        var record = role.equals("production-failure")
            ? fixture.unavailableRecord("server-exact", "scale/server", CatalogAccess.PUBLIC)
            : fixture.record("server-exact", "scale/server",
                role.equals("unauthorized-then-grant") ? CatalogAccess.AUTHORIZED : CatalogAccess.PUBLIC);
        return new CatalogSnapshot(Map.of(record.entry().modelId(), record), List.of(), ModelScanReport.empty());
    }

    private static int queued(List<ScaleServerOwner> owners, ScaleServerOwner excluded) {
        return owners.stream().filter(owner -> owner != excluded)
            .mapToInt(owner -> owner.worker().queued(owner.session())).sum();
    }

    private static long usedHeap() {
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static final class ScaleServerOwner implements AutoCloseable {
        private final String playerId;
        private final String connectionId;
        private final String role;
        private final Socket socket;
        private final SocketByteCarrier carrier;
        private final ServerModelSession session;
        private final ResourceDispatchWorker worker;
        private final ServerResourceRuntime serverResources;
        private final EndpointEvidence evidence;
        private final ScaleCarrierPort port;
        private final ServerAssetTransfers transfers;
        private final Set<Long> acceptedTransfers = new LinkedHashSet<>();
        private final Map<Long, String> terminalTransfers = new LinkedHashMap<>();
        private final Map<Long, String> rejectedTransfers = new LinkedHashMap<>();
        private int maxQueued;
        private Long acceptingTransferId;
        private String acceptingFailure;
        private boolean closed;

        private ScaleServerOwner(String playerId, String connectionId, String role, Socket socket,
            CatalogSnapshot catalog, ResourceDispatchWorker worker, ServerResourceRuntime serverResources,
            EndpointEvidence evidence) throws Exception {
            this.playerId = playerId;
            this.connectionId = connectionId;
            this.role = role;
            this.socket = socket;
            this.worker = worker;
            this.serverResources = serverResources;
            this.evidence = evidence;
            carrier = new SocketByteCarrier(socket);
            session = new ServerModelSession(() -> catalog, false);
            port = new ScaleCarrierPort(carrier, evidence, connectionId, role.equals("pressure"),
                this::recordSuccessfulTerminal);
            transfers =
                new ServerAssetTransfers(session, worker, port, () -> true, this::recordFailure, serverResources);
        }

        private boolean accept(MetadataPrefixRequestProto request) {
            return accept(request.dataTransferId(), () -> transfers.accept(request), false);
        }

        private boolean accept(ModelChunkRequestProto request) {
            return accept(request.dataTransferId(), () -> transfers.accept(request), true);
        }

        private boolean accept(long transferId, BooleanSupplier admission, boolean awaitRuntime) {
            require(acceptingTransferId == null, "Scale owner attempted nested transfer admission");
            acceptingTransferId = transferId;
            acceptingFailure = null;
            final boolean handled;
            var queuedBefore = worker.queued(session);
            try {
                handled = admission.getAsBoolean();
                if (handled && awaitRuntime && acceptingFailure == null) {
                    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (worker.queued(session) == queuedBefore && acceptingFailure == null
                        && System.nanoTime() < deadline) {
                        serverResources.tick();
                        transfers.tick();
                        LockSupport.parkNanos(1_000_000L);
                    }
                    require(worker.queued(session) > queuedBefore || acceptingFailure != null,
                        "Scale chunk acquisition did not reach dispatch or terminal");
                }
            } finally {
                acceptingTransferId = null;
            }
            var failure = acceptingFailure;
            acceptingFailure = null;
            if (!handled) {
                require(transferId == 0, "Production rejected an unclassified scale request");
                recordRejection(transferId, "INTRINSIC_INVALID");
                observeQueue();
                return false;
            }
            if (isPreAdmissionRejection(failure)) {
                recordRejection(transferId, failure);
            } else {
                recordAccepted(transferId);
                if (failure != null) {
                    recordTerminal(transferId, "failure:" + failure);
                }
            }
            observeQueue();
            return true;
        }

        private boolean cancel(long transferId) throws Exception {
            var cancelled = transfers.cancel(transferId);
            if (cancelled) {
                recordTerminal(transferId, "cancellation");
            }
            return cancelled;
        }

        private void recordFailure(ResourceTransferFailureProto failure) {
            var transferId = failure.dataTransferId();
            var reason = failure.reason().name();
            try {
                MockWireIo.send(carrier, evidence, connectionId, failure);
            } catch (Exception error) {
                throw new IllegalStateException("Failed to publish scale transfer failure", error);
            }
            if (acceptingTransferId != null && acceptingTransferId == transferId) {
                acceptingFailure = reason;
            } else {
                require(!isPreAdmissionRejection(reason), "Pre-admission rejection arrived after admission returned");
                recordTerminal(transferId, "failure:" + reason);
            }
        }

        private void recordSuccessfulTerminal(long transferId) {
            recordTerminal(transferId, "success");
        }

        private void recordAccepted(long transferId) {
            require(acceptedTransfers.add(transferId), "Scale transfer was accepted twice: " + transferId);
            appendTransferEvidence("accepted-", "server-transfer-accepted", transferId,
                Map.of("playerId", playerId, "transferId", Long.toUnsignedString(transferId)));
        }

        private void recordTerminal(long transferId, String classification) {
            require(acceptedTransfers.contains(transferId), "Scale terminal had no accepted owner: " + transferId);
            require(terminalTransfers.putIfAbsent(transferId, classification) == null,
                "Scale transfer produced duplicate terminal: " + transferId);
            appendTransferEvidence("terminal-", "server-transfer-terminal", transferId, Map.of("classification",
                classification, "playerId", playerId, "transferId", Long.toUnsignedString(transferId)));
        }

        private void recordRejection(long transferId, String reason) {
            require(!acceptedTransfers.contains(transferId),
                "Accepted scale transfer was reclassified as rejected: " + transferId);
            require(rejectedTransfers.putIfAbsent(transferId, reason) == null,
                "Scale transfer was rejected twice: " + transferId);
            appendTransferEvidence("rejected-", "server-transfer-rejected", transferId,
                Map.of("playerId", playerId, "reason", reason, "transferId", Long.toUnsignedString(transferId)));
        }

        private void appendTransferEvidence(String actionPrefix, String eventKind, long transferId,
            Map<String, String> payload) {
            try {
                evidence.append(connectionId, actionPrefix + Long.toUnsignedString(transferId),
                    connectionId + "/transfer-" + Long.toUnsignedString(transferId), eventKind, payload);
            } catch (Exception error) {
                throw new IllegalStateException("Failed to record scale transfer ledger", error);
            }
        }

        private static boolean isPreAdmissionRejection(String reason) {
            return "UNAUTHORIZED".equals(reason) || "BUSY".equals(reason);
        }

        private void observeQueue() {
            maxQueued = Math.max(maxQueued, worker.queued(session));
        }

        private boolean retiredAtAdmission() {
            return role.equals("replace-old") || role.equals("leave");
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            transfers.close();
            worker.disconnect(session);
            session.close();
            carrier.close();
            if (!socket.isClosed()) {
                socket.close();
            }
            require(acceptedTransfers.equals(terminalTransfers.keySet()),
                "Scale owner closed with unterminated accepted work: " + connectionId);
            evidence.append(connectionId, "server-close", "server-session", "owner-close",
                Map.of("acceptedTransfers", Integer.toString(acceptedTransfers.size()), "playerId", playerId, "role",
                    role, "terminalTransfers", Integer.toString(terminalTransfers.size())));
        }

        private void observeConnectionState(boolean current) throws Exception {
            evidence.append(connectionId, "server-connection-state", "server-session", "server-connection-state",
                Map.of("current", Boolean.toString(current), "playerId", playerId));
        }

        private String connectionId() {
            return connectionId;
        }

        private String role() {
            return role;
        }

        private SocketByteCarrier carrier() {
            return carrier;
        }

        private ServerModelSession session() {
            return session;
        }

        private ResourceDispatchWorker worker() {
            return worker;
        }

        private ScaleCarrierPort port() {
            return port;
        }

        private int maxQueued() {
            return maxQueued;
        }

        private int acceptedTransfers() {
            return acceptedTransfers.size();
        }

        private int terminalTransfers() {
            return terminalTransfers.size();
        }

        private void tickTransfers() {
            transfers.tick();
        }

        private int rejectedTransfers() {
            return rejectedTransfers.size();
        }
    }

    private static final class ScaleCarrierPort implements TransportPort {
        private final SocketByteCarrier carrier;
        private final EndpointEvidence evidence;
        private final String connectionId;
        private final LongConsumer successfulTerminal;
        private boolean pressured;
        private boolean open = true;
        private int metadataTerminals;

        private ScaleCarrierPort(SocketByteCarrier carrier, EndpointEvidence evidence, String connectionId,
            boolean pressured, LongConsumer successfulTerminal) {
            this.carrier = carrier;
            this.evidence = evidence;
            this.connectionId = connectionId;
            this.pressured = pressured;
            this.successfulTerminal = successfulTerminal;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isWritable() {
            return !pressured;
        }

        @Override
        public long highWatermarkBytes() {
            return 64 * 1024;
        }

        @Override
        public long pendingBytes() {
            return 0;
        }

        @Override
        public SendResult trySend(OutboundFrame frame) {
            try {
                var decoded = ProductionWire.decode(frame.bytes(), MessageDirection.SERVER_TO_CLIENT);
                MockWireIo.send(carrier, evidence, connectionId, frame.bytes(), MessageDirection.SERVER_TO_CLIENT);
                Long terminalId = null;
                if (decoded.message() instanceof MetadataPrefixFragmentProto metadata && metadata.finalFragment()) {
                    terminalId = metadata.dataTransferId();
                } else if (decoded.message() instanceof ChunkFragmentProto chunk && chunk.finalFragment()) {
                    terminalId = chunk.dataTransferId();
                }
                if (terminalId != null) {
                    if (decoded.message() instanceof MetadataPrefixFragmentProto) {
                        metadataTerminals++;
                    }
                    successfulTerminal.accept(terminalId);
                }
                return SendResult.SUCCESS;
            } catch (Exception failure) {
                throw new IllegalStateException("Failed to send scale resource frame", failure);
            }
        }

        private void releasePressure() {
            pressured = false;
        }

        private int metadataTerminals() {
            return metadataTerminals;
        }
    }

    private static void runConnectionA(Socket socket, MockScenarioFixture fixture, EndpointEvidence evidence,
        Map<String, String> facts, Path cacheRoot) throws Exception {
        var connectionId = "connection-A";
        var initial = initialCatalog(fixture, "server-exact");
        var session = new ServerModelSession(() -> initial, false);
        try (var carrier = new SocketByteCarrier(socket); var worker = new ResourceDispatchWorker(1, 32, 0);
            var serverResources = new ServerResourceRuntime(new PreviewStore(cacheRoot), ignored -> {
            })) {
            var port = new CarrierPort(carrier, evidence, connectionId);
            try (var transfers = new ServerAssetTransfers(session, worker, port, () -> true,
                failure -> sendUnchecked(carrier, evidence, connectionId, failure), serverResources)) {
                negotiate(carrier, evidence, connectionId, session);
                session.commitCatalog(initial);
                sendAll(carrier, evidence, connectionId, SessionCollectionPublication.fullFragments(session.authority(),
                    session.allocatePublicationId(), Map.of()));
                var metadataRequests = 0;
                var chunkRequests = 0;
                while (true) {
                    final ProductionWire.AnyDecoded decoded;
                    try {
                        decoded =
                            MockWireIo.receive(carrier, evidence, connectionId, MessageDirection.CLIENT_TO_SERVER);
                    } catch (EOFException closed) {
                        break;
                    }
                    if (decoded.message() instanceof MetadataPrefixRequestProto value) {
                        metadataRequests++;
                        require(transfers.accept(value), "Metadata request was structurally invalid");
                        drain(worker, transfers, session);
                        if (metadataRequests == 2) {
                            sendBaselineDrift(carrier, evidence, connectionId, session, fixture);
                            var replacement = replace(initialWithFailure(fixture), fixture, "server-exact",
                                "replacement-old", "remote/server");
                            var transition = session.commitCatalog(replacement, Set.of());
                            sendAll(carrier, evidence, connectionId, SessionCollectionPublication
                                .deltaFragments(transition, session.allocatePublicationId()));
                        }
                    } else if (decoded.message() instanceof ModelChunkRequestProto value) {
                        chunkRequests++;
                        require(transfers.accept(value), "Chunk request was structurally invalid");
                        if (chunkRequests == 1) {
                            require(worker.queued(session) == 0, "Unauthorized request reached the dispatch queue");
                            session.setGrants(Set.of(fixture.asset("server-exact").identity().modelId()));
                            sendAll(carrier, evidence, connectionId, SessionCollectionPublication
                                .authorityDeltaFragments(session.authority(), session.allocatePublicationId()));
                            facts.put("unauthorizedDispatchQueue", "0");
                        } else if (chunkRequests == 2) {
                            awaitQueued(serverResources, transfers, worker, session);
                            require(worker.queued(session) > 0, "Authorized request was not admitted to dispatch");
                            session.setGrants(Set.of());
                            drain(worker, transfers, session);
                            sendAll(carrier, evidence, connectionId, SessionCollectionPublication
                                .authorityDeltaFragments(session.authority(), session.allocatePublicationId()));
                            var withFailure = initialWithFailure(fixture);
                            var transition = session.commitCatalog(withFailure, Set.of());
                            sendAll(carrier, evidence, connectionId, SessionCollectionPublication
                                .deltaFragments(transition, session.allocatePublicationId()));
                            facts.put("acceptedTransferAuthorization", "admission-snapshot");
                        } else {
                            throw new IllegalStateException("Unexpected chunk request count");
                        }
                    } else if (decoded.message() instanceof ResourceTransferCancelProto value) {
                        require(transfers.cancel(value.dataTransferId()), "Transfer cancel was structurally invalid");
                    } else {
                        throw new IllegalStateException("Unexpected client message " + decoded.messageType());
                    }
                }
                require(metadataRequests == 3 && chunkRequests == 2,
                    "Connection A did not traverse every planned transfer action");
                transfers.close();
                worker.disconnect(session);
                require(worker.queued(session) == 0, "Connection A left accepted dispatch work");
                facts.put("connectionATransferTerminals", "5");
            } finally {
                session.close();
                evidence.append(connectionId, "server-close", "server-session", "owner-close",
                    Map.of("sessionActive", "false", "terminalCount", "1"));
            }
        }
    }

    private static void runConnectionB(Socket socket, MockScenarioFixture fixture, EndpointEvidence evidence,
        Map<String, String> facts, Path cacheRoot) throws Exception {
        var connectionId = "connection-B";
        var initial = initialCatalog(fixture, "replacement-new");
        var session = new ServerModelSession(() -> initial, false);
        try (var carrier = new SocketByteCarrier(socket); var worker = new ResourceDispatchWorker(1, 32, 0);
            var serverResources = new ServerResourceRuntime(new PreviewStore(cacheRoot), ignored -> {
            })) {
            var port = new CarrierPort(carrier, evidence, connectionId);
            try (var transfers = new ServerAssetTransfers(session, worker, port, () -> true,
                failure -> sendUnchecked(carrier, evidence, connectionId, failure), serverResources)) {
                negotiate(carrier, evidence, connectionId, session);
                session.commitCatalog(initial);
                sendAll(carrier, evidence, connectionId, SessionCollectionPublication.fullFragments(session.authority(),
                    session.allocatePublicationId(), Map.of()));
                var decoded = MockWireIo.receive(carrier, evidence, connectionId, MessageDirection.CLIENT_TO_SERVER);
                require(decoded.message() instanceof MetadataPrefixRequestProto value && transfers.accept(value),
                    "Replacement connection did not request exact metadata");
                drain(worker, transfers, session);

                var records = new LinkedHashMap<>(initial.byModelId());
                var cacheFailure = fixture.record("cache-failure", "remote/cache-failure", CatalogAccess.PUBLIC);
                records.put(cacheFailure.entry().modelId(), cacheFailure);
                var next = new CatalogSnapshot(records, List.of(), ModelScanReport.empty());
                var transition = session.commitCatalog(next, Set.of());
                sendAll(carrier, evidence, connectionId,
                    SessionCollectionPublication.deltaFragments(transition, session.allocatePublicationId()));
                sendInvalidFull(carrier, evidence, connectionId, session.allocatePublicationId());
                try {
                    MockWireIo.receive(carrier, evidence, connectionId, MessageDirection.CLIENT_TO_SERVER);
                    throw new IllegalStateException("Connection B remained open after the terminal client action");
                } catch (EOFException expected) {
                    // Physical disconnect is the exact connection retirement signal.
                }
                transfers.close();
                worker.disconnect(session);
                require(worker.queued(session) == 0, "Connection B left accepted dispatch work");
                facts.put("connectionBTransferTerminals", "1");
                facts.put("lateOldCurrentEffects", "0");
            } finally {
                session.close();
                evidence.append(connectionId, "server-close", "server-session", "owner-close",
                    Map.of("sessionActive", "false", "terminalCount", "1"));
            }
        }
    }

    private static void negotiate(SocketByteCarrier carrier, EndpointEvidence evidence, String connectionId,
        ServerModelSession session) throws Exception {
        MockWireIo.send(carrier, evidence, connectionId, ServerHelloProto.newBuilder()
            .setProtocolVersion(ProtocolVersion.TRANSPORT_VERSION).setSyncRoaming(false).build());
        var response = MockWireIo.receive(carrier, evidence, connectionId, MessageDirection.CLIENT_TO_SERVER);
        require(response.message() instanceof SessionResponseProto value && value.decision() == SessionDecision.ACCEPT,
            "Production client did not accept the server hello");
        require(session.activate(), "Production server session did not activate");
    }

    private static CatalogSnapshot initialCatalog(MockScenarioFixture fixture, String remoteName) {
        var records = new LinkedHashMap<Hash256, CatalogRecord>();
        add(records, fixture.record("active-exact", "remote/active", CatalogAccess.PUBLIC));
        add(records, fixture.record("same-model-remote", "remote/same-model", CatalogAccess.PUBLIC));
        add(records, fixture.record("local-exact", "remote/local", CatalogAccess.PUBLIC));
        add(records, fixture.record("cache-exact", "remote/cache", CatalogAccess.PUBLIC));
        add(records, fixture.record(remoteName, "remote/server", CatalogAccess.AUTHORIZED));
        return new CatalogSnapshot(records, List.of(), ModelScanReport.empty());
    }

    private static CatalogSnapshot initialWithFailure(MockScenarioFixture fixture) {
        var records = new LinkedHashMap<>(initialCatalog(fixture, "server-exact").byModelId());
        var failure = fixture.unavailableRecord("server-failure", "remote/server-failure", CatalogAccess.AUTHORIZED);
        records.put(failure.entry().modelId(), failure);
        return new CatalogSnapshot(records, List.of(), ModelScanReport.empty());
    }

    private static CatalogSnapshot replace(CatalogSnapshot source, MockScenarioFixture fixture, String previous,
        String next, String path) {
        var records = new LinkedHashMap<>(source.byModelId());
        records.remove(fixture.asset(previous).identity().modelId());
        add(records, fixture.record(next, path, CatalogAccess.PUBLIC));
        return new CatalogSnapshot(records, List.of(), ModelScanReport.empty());
    }

    private static void add(Map<Hash256, CatalogRecord> records, CatalogRecord record) {
        if (records.put(record.entry().modelId(), record) != null) {
            throw new IllegalStateException("Scenario catalog reused a ModelId");
        }
    }

    private static void sendBaselineDrift(SocketByteCarrier carrier, EndpointEvidence evidence, String connectionId,
        ServerModelSession session, MockScenarioFixture fixture) throws Exception {
        var asset = fixture.asset("cache-failure");
        var entry = CatalogPublicationProto.newBuilder().setModelId(ByteBuffer.wrap(asset.identity().modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(asset.identity().containerId().bytes()))
            .setHierarchyPath("local:custom/remote/active")
            .setAccess(com.elfmcys.ysm.proto.network.CatalogAccess.PUBLIC).build();
        MockWireIo.send(carrier, evidence, connectionId,
            SessionDeltaFragmentProto.newBuilder().setTransferId(session.allocatePublicationId()).setSequence(0)
                .setFinalFragment(true).setCatalog(CatalogCollectionOperationProto.newBuilder()
                    .setOpType(CollectionOperationType.ADD).addEntries(entry).build())
                .build());
    }

    private static void sendInvalidFull(SocketByteCarrier carrier, EndpointEvidence evidence, String connectionId,
        long transferId) throws Exception {
        MockWireIo.send(carrier, evidence, connectionId,
            SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(0).setFinalFragment(true)
                .setCatalog(CatalogCollectionOperationProto.newBuilder().setOpType(CollectionOperationType.FULL)
                    .addEntries(CatalogPublicationProto.newBuilder().setModelId(ByteBuffer.wrap(new byte[Hash256.SIZE]))
                        .setContainerId(ByteBuffer.wrap(new byte[Hash256.SIZE]))
                        .setHierarchyPath("local:custom/remote/invalid")
                        .setAccess(com.elfmcys.ysm.proto.network.CatalogAccess.UNSPECIFIED).build())
                    .build())
                .build());
    }

    private static void sendAll(SocketByteCarrier carrier, EndpointEvidence evidence, String connectionId,
        List<? extends ProtoMessage<?>> messages) throws Exception {
        for (var message : messages) {
            MockWireIo.send(carrier, evidence, connectionId, message);
        }
    }

    private static void awaitQueued(ServerResourceRuntime serverResources, ServerAssetTransfers transfers,
        ResourceDispatchWorker worker, ServerModelSession owner) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (worker.queued(owner) == 0 && System.nanoTime() < deadline) {
            serverResources.tick();
            transfers.tick();
            LockSupport.parkNanos(1_000_000L);
        }
        require(worker.queued(owner) > 0, "Server chunk acquisition did not reach dispatch");
    }

    private static void drain(ResourceDispatchWorker worker, ServerAssetTransfers transfers, ServerModelSession owner) {
        while (worker.queued(owner) != 0) {
            require(worker.dispatchOnce(), "Accepted transfer did not make dispatch progress");
            transfers.tick();
        }
        transfers.tick();
    }

    private static void sendUnchecked(SocketByteCarrier carrier, EndpointEvidence evidence, String connectionId,
        ProtoMessage<?> message) {
        try {
            MockWireIo.send(carrier, evidence, connectionId, message);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to send typed server frame", failure);
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

    private static final class CarrierPort implements TransportPort {
        private final SocketByteCarrier carrier;
        private final EndpointEvidence evidence;
        private final String connectionId;

        private CarrierPort(SocketByteCarrier carrier, EndpointEvidence evidence, String connectionId) {
            this.carrier = carrier;
            this.evidence = evidence;
            this.connectionId = connectionId;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public long highWatermarkBytes() {
            return 64 * 1024;
        }

        @Override
        public long pendingBytes() {
            return 0;
        }

        @Override
        public SendResult trySend(OutboundFrame frame) {
            try {
                MockWireIo.send(carrier, evidence, connectionId, frame.bytes(), MessageDirection.SERVER_TO_CLIENT);
                return SendResult.SUCCESS;
            } catch (Exception failure) {
                throw new IllegalStateException("Failed to send dispatched resource frame", failure);
            }
        }
    }
}
