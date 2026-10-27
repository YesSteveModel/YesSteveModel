package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.EvidenceRun;
import com.elfmcys.ysm.mock.evidence.Exact100WorkloadInput;
import com.elfmcys.ysm.mock.evidence.Exact100WorkloadInput.ExpectedConnection;
import com.elfmcys.ysm.mock.evidence.Exact100WorkloadInput.ScenarioInput;
import com.elfmcys.ysm.mock.evidence.Exact100WorkloadInput.SessionPlan;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;
import com.elfmcys.ysm.mock.evidence.ScenarioEvidence;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.ServerConnectionRegistry;
import com.elfmcys.ysm.model.session.server.ServerConnectionRegistryFixture;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelManagementCoreWorkloadTest {
    private static final String SCENARIO_ID = "MMR-WORKLOAD-100-001";
    private static final int SESSION_COUNT = Exact100WorkloadInput.SESSION_COUNT;
    private static final int DISPATCH_GUARD = 20_000;

    @TempDir
    Path temp;

    @Test
    @Tag("model-management-mock")
    void hundredLogicalSessionsMakeBoundedProgressAndCloseEveryAcceptedOwner() throws Throwable {
        assumeTrue(EvidenceRun.isConfigured(), "Only the explicit mock task writes acceptance evidence");
        var input = Exact100WorkloadInput.create();
        var inputBytes = EvidenceJson.canonicalBytes(input);
        var repeatedInputBytes = EvidenceJson.canonicalBytes(Exact100WorkloadInput.create());
        require(Arrays.equals(inputBytes, repeatedInputBytes),
            "the repeated workload must regenerate the identical action stream");

        var run = EvidenceRun.openConfigured();
        var identity = run.identity(SCENARIO_ID, List.of("FA-006", "FA-007"),
            List.of("CO-007", "CO-010", "CO-012", "CO-013", "CO-016"), EvidenceRun.Radius.CORE_WORKLOAD, true,
            inputBytes);
        var evidence = run.scenario(identity, inputBytes);

        try {
            var catalog = catalogFixture();
            var first = execute(input, catalog, "repeat-a");
            append(evidence, first);
            var second = execute(input, catalog, "repeat-b");
            append(evidence, second);
            require(first.deterministic().equals(second.deterministic()),
                "repeated workload produced different correctness observations");
            evidence.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, "repeat-comparison", "core-workload", null,
                "action-stream", "repeat-identical",
                Map.of("inputSha256", EvidenceJson.sha256(inputBytes), "acceptedOwners",
                    Integer.toString(first.deterministic().acceptedOwners()), "terminalOwners",
                    Integer.toString(first.deterministic().terminalOwners()), "successfulFragments",
                    Integer.toString(first.deterministic().successfulFragments()))));
            evidence.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, "optional-characterization", "core-workload",
                null, "session-cardinality", "not-run", Map.of("200-session",
                    "Not run (future Bukkit characterization)", "300-session", "Not run (extreme characterization)")));
            evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS, List.of(
                "Two exact 100-session runs regenerated one identical fixed action stream and identical correctness observations",
                "All continuously ready survivors progressed while one transport remained blocked and one exact owner failed production",
                "Same-player replacement and leave closed old accepted work; stale generation gates produced no current effect",
                "Invalid and unauthorized requests created no accepted owner or queue entry",
                "Every accepted transfer reached exactly one success, failure, cancellation, or owner-shutdown terminal and every per-session queue closed within Settings hardLimit=4"),
                List.of(),
                Map.of("contractViolation", 0L, "unexpectedError", 0L, "expectedAcceptedProductionFailure", 2L,
                    "expectedPreAdmissionRejection", 6L),
                "Core-session composition uses production ServerModelSession, ServerConnectionRegistry, ServerAssetTransfers, typed chunk packets/sources, and ResourceDispatchWorker; it does not prove Forge transport, producer-thread scheduling, Minecraft clients, or physical reclamation. Wall time, dispatch-lag proxy, and heap trend are soft observations only."));
        } catch (Throwable failure) {
            completeFailure(evidence, failure);
            throw failure;
        }
    }

    private RunResult execute(ScenarioInput input, CatalogFixture catalog, String repeat) throws Exception {
        var settings = new ResourceDispatchWorker.Settings(input.settings().softLimit(), input.settings().hardLimit(),
            input.settings().bytesPerSecond());
        require(input.sessionCount() == SESSION_COUNT && input.sessions().size() == SESSION_COUNT,
            "the acceptance-eligible workload must contain exactly 100 session slots");
        var worker = new ResourceDispatchWorker(() -> settings);
        var serverResources = new ServerResourceRuntime(new PreviewStore(temp.resolve("preview-cache")), ignored -> {
        });
        var ledger = new WorkloadLedger(settings.hardLimit());
        ledger.event("capacity-config", null, null,
            Map.of("softLimit", Integer.toString(settings.softLimit()), "hardLimit",
                Integer.toString(settings.hardLimit()), "bytesPerSecond", Long.toString(settings.bytesPerSecond())));
        var fixtures = new ArrayList<ServerConnectionRegistryFixture<ConnectionOwner>>();
        var owners = new ArrayList<ConnectionOwner>();
        var generations = new ArrayList<GenerationObservation>();
        ConnectionOwner pressureOwner = null;
        var heapBefore = usedHeap();
        var started = System.nanoTime();
        try {
            for (var plan : input.sessions()) {
                var fixture = new ServerConnectionRegistryFixture<ConnectionOwner>(UUID.fromString(plan.playerId()));
                fixtures.add(fixture);
                switch (plan.role()) {
                    case "pressure" -> {
                        pressureOwner = owner(plan, plan.initialConnection(), catalog.publicCatalog(), Set.of(),
                            PortMode.PRESSURE, worker, serverResources, ledger);
                        owners.add(pressureOwner);
                        var generation = fixture.replace(plan.initialConnection(), pressureOwner);
                        generations.add(new GenerationObservation(fixture, generation, true));
                        for (var transferId = 1L; transferId <= 4; transferId++) {
                            acceptCurrent(fixture, generation, pressureOwner, request(transferId, catalog));
                        }
                        acceptCurrent(fixture, generation, pressureOwner, request(5, catalog));
                    }
                    case "production-failure" -> {
                        var current = owner(plan, plan.initialConnection(), catalog.publicCatalog(), Set.of(),
                            PortMode.PRODUCTION_FAILURE, worker, serverResources, ledger);
                        owners.add(current);
                        var generation = fixture.replace(plan.initialConnection(), current);
                        generations.add(new GenerationObservation(fixture, generation, true));
                        acceptCurrent(fixture, generation, current, request(1, catalog));
                    }
                    case "replace" -> {
                        var oldOwner = owner(plan, plan.initialConnection(), catalog.publicCatalog(), Set.of(),
                            PortMode.NORMAL, worker, serverResources, ledger);
                        owners.add(oldOwner);
                        var oldGeneration = fixture.replace(plan.initialConnection(), oldOwner);
                        generations.add(new GenerationObservation(fixture, oldGeneration, false));
                        acceptCurrent(fixture, oldGeneration, oldOwner, request(1, catalog));

                        var replacement = owner(plan, plan.replacementConnection(), catalog.publicCatalog(), Set.of(),
                            PortMode.NORMAL, worker, serverResources, ledger);
                        owners.add(replacement);
                        var newGeneration = fixture.replace(plan.replacementConnection(), replacement);
                        generations.add(new GenerationObservation(fixture, newGeneration, true));
                        require(oldOwner.closeCalls() == 1 && worker.queued(oldOwner.session()) == 0,
                            "replacement must close the old exact owner and its queue");
                        require(
                            !fixture.runIfCurrent(oldGeneration, () -> ledger.currentEffect(oldOwner.connectionId())),
                            "the stale replacement generation must fail the production gate");
                        acceptCurrent(fixture, newGeneration, replacement, request(1, catalog));
                        acceptCurrent(fixture, newGeneration, replacement, request(2, catalog));
                    }
                    case "leave" -> {
                        var current = owner(plan, plan.initialConnection(), catalog.publicCatalog(), Set.of(),
                            PortMode.NORMAL, worker, serverResources, ledger);
                        owners.add(current);
                        var generation = fixture.replace(plan.initialConnection(), current);
                        generations.add(new GenerationObservation(fixture, generation, false));
                        acceptCurrent(fixture, generation, current, request(1, catalog));
                        acceptCurrent(fixture, generation, current, request(2, catalog));
                        fixture.disconnect(generation);
                        require(current.closeCalls() == 1 && worker.queued(current.session()) == 0,
                            "leave must close the exact owner and accepted queue");
                        require(!fixture.runIfCurrent(generation, () -> ledger.currentEffect(current.connectionId())),
                            "a departed generation must fail the production gate");
                    }
                    case "cancel-rerequest" -> {
                        var current = owner(plan, plan.initialConnection(), catalog.publicCatalog(), Set.of(),
                            PortMode.NORMAL, worker, serverResources, ledger);
                        owners.add(current);
                        var generation = fixture.replace(plan.initialConnection(), current);
                        generations.add(new GenerationObservation(fixture, generation, true));
                        acceptCurrent(fixture, generation, current, request(1, catalog));
                        require(fixture.runIfCurrent(generation, () -> current.cancel(1)),
                            "current cancel must cross the production generation gate");
                        acceptCurrent(fixture, generation, current, request(2, catalog));
                    }
                    case "unauthorized-then-grant" -> {
                        var current = owner(plan, plan.initialConnection(), catalog.authCatalog(), Set.of(),
                            PortMode.NORMAL, worker, serverResources, ledger);
                        owners.add(current);
                        var generation = fixture.replace(plan.initialConnection(), current);
                        generations.add(new GenerationObservation(fixture, generation, true));
                        acceptCurrent(fixture, generation, current, request(1, catalog));
                        require(fixture.runIfCurrent(generation, () -> current.grant(catalog.identity().modelId())),
                            "grant change must cross the current exact generation gate");
                        acceptCurrent(fixture, generation, current, request(2, catalog));
                    }
                    case "invalid" -> {
                        var current = owner(plan, plan.initialConnection(), catalog.publicCatalog(), Set.of(),
                            PortMode.NORMAL, worker, serverResources, ledger);
                        owners.add(current);
                        var generation = fixture.replace(plan.initialConnection(), current);
                        generations.add(new GenerationObservation(fixture, generation, false));
                        acceptCurrent(fixture, generation, current, request(0, catalog));
                        fixture.disconnect(generation);
                    }
                    case "normal" -> {
                        var current = owner(plan, plan.initialConnection(), catalog.publicCatalog(), Set.of(),
                            PortMode.NORMAL, worker, serverResources, ledger);
                        owners.add(current);
                        var generation = fixture.replace(plan.initialConnection(), current);
                        generations.add(new GenerationObservation(fixture, generation, true));
                        acceptCurrent(fixture, generation, current, request(1, catalog));
                        acceptCurrent(fixture, generation, current, request(2, catalog));
                    }
                    default -> throw new AssertionError("unknown role " + plan.role());
                }
            }
            require(pressureOwner != null, "the action stream must contain transport pressure");
            assertExpectedConnections(input, generations, ledger);
            var blocked = pressureOwner;
            drain(worker, serverResources, owners, ledger, () -> owners.stream().filter(owner -> owner != blocked)
                .allMatch(owner -> worker.queued(owner.session()) == 0));
            for (var survivor : input.readySurvivors()) {
                require(ledger.progress(survivor) > 0,
                    "ready survivor made no progress while another session was blocked: " + survivor);
            }
            require(
                ledger.progress(blocked.connectionId()) == 0
                    && worker.queued(blocked.session()) == settings.hardLimit(),
                "the pressure owner must remain bounded and blocked before release");

            blocked.port().releasePressure();
            drain(worker, serverResources, owners, ledger,
                () -> owners.stream().allMatch(owner -> worker.queued(owner.session()) == 0));
            owners.forEach(owner -> {
                require(owner.activeTransfers() == 0,
                    "accepted transfer remained active after deterministic drain: " + owner.connectionId());
                require(worker.queued(owner.session()) == 0,
                    "packet remained queued after deterministic drain: " + owner.connectionId());
            });
            ledger.assertExpected(input);
        } finally {
            for (var index = fixtures.size() - 1; index >= 0; index--) {
                fixtures.get(index).close();
            }
            worker.close();
            serverResources.close();
        }
        owners.forEach(owner -> require(owner.closeCalls() == 1,
            "each connection state must be closed exactly once: " + owner.connectionId()));
        ledger.assertExpected(input);
        ledger.closureSummary(owners.size());
        var elapsed = System.nanoTime() - started;
        var metrics =
            new SoftMetrics(elapsed, ledger.dispatchCalls(), ledger.maxDispatchNanos(), heapBefore, usedHeap());
        ledger.event("soft-metrics", null, null,
            Map.of("wallNanos", Long.toString(metrics.wallNanos()), "dispatchCalls",
                Long.toString(metrics.dispatchCalls()), "msptEquivalentNanos",
                Long.toString(metrics.msptEquivalentNanos()), "lagSpikeNanos", Long.toString(metrics.lagSpikeNanos()),
                "heapUsedBefore", Long.toString(metrics.heapUsedBefore()), "heapUsedAfter",
                Long.toString(metrics.heapUsedAfter()), "heapDelta", Long.toString(metrics.heapDelta()),
                "correctnessThreshold", "none"));
        return new RunResult(repeat, ledger.deterministic(), List.copyOf(ledger.events()));
    }

    private static ConnectionOwner owner(SessionPlan plan, String connectionId, CatalogSnapshot catalog,
        Set<Hash256> grants, PortMode portMode, ResourceDispatchWorker worker, ServerResourceRuntime serverResources,
        WorkloadLedger ledger) {
        return new ConnectionOwner(plan.playerId(), connectionId, catalog, grants, portMode, worker, serverResources,
            ledger);
    }

    private static void acceptCurrent(ServerConnectionRegistryFixture<ConnectionOwner> fixture,
        ServerConnectionRegistryFixture.Generation<ConnectionOwner> generation, ConnectionOwner owner,
        ModelChunkRequestProto request) {
        var handled = new boolean[1];
        require(fixture.runIfCurrent(generation, () -> handled[0] = owner.accept(request)),
            "request must cross the current exact generation gate");
        if (request.dataTransferId() == 0) {
            require(!handled[0], "intrinsic invalid request must retire the model session");
            owner.ledger().rejection(owner.operation(0), "INTRINSIC_INVALID");
        } else {
            require(handled[0], "valid typed request must be handled by its session owner");
        }
    }

    private static void assertExpectedConnections(ScenarioInput input, List<GenerationObservation> generations,
        WorkloadLedger ledger) {
        var expectedCurrent = input.expectedCurrent().stream().filter(ExpectedConnection::current)
            .map(ExpectedConnection::connectionId).collect(Collectors.toUnmodifiableSet());
        for (var observation : generations) {
            var fixedExpectation = expectedCurrent.contains(observation.generation().connectionId());
            require(fixedExpectation == observation.expectedCurrent(),
                "runtime setup diverged from the fixed current-connection map");
            var effectsBefore = ledger.currentEffects();
            var current = observation.fixture().runIfCurrent(observation.generation(),
                () -> ledger.currentEffect(observation.generation().connectionId()));
            require(current == observation.expectedCurrent(),
                "production current-generation result differed from the fixed map for "
                    + observation.generation().connectionId());
            require(ledger.currentEffects() - effectsBefore == (current ? 1 : 0),
                "stale exact generation produced a current effect");
        }
        require(ledger.currentEffects() == expectedCurrent.size(),
            "current-effect count differs from the fixed exact-connection map");
    }

    private static void drain(ResourceDispatchWorker worker, ServerResourceRuntime serverResources,
        List<ConnectionOwner> owners, WorkloadLedger ledger, BooleanSupplier complete) {
        for (var attempts = 0; attempts < DISPATCH_GUARD; attempts++) {
            serverResources.tick();
            owners.forEach(ConnectionOwner::tick);
            if (complete.getAsBoolean()) {
                return;
            }
            var started = System.nanoTime();
            var selected = worker.dispatchOnce();
            ledger.dispatch(System.nanoTime() - started);
            require(selected, "accepted queues became undispatchable before terminal closure");
        }
        throw new AssertionError("deterministic dispatch action guard exhausted");
    }

    private CatalogFixture catalogFixture() throws Exception {
        var manifest = ModelManagementCoreWorkloadTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        final Path file;
        try (var vfs = new Directory(source)) {
            file = ModelParser.parse(vfs, Files.createDirectories(temp.resolve("model")),
                DefaultAnimationFilter.keepAll());
        }
        final ModelFileIdentity identity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        var publicLocation = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("workload/public"));
        var content = ManagedContainer.openIndexed(new CatalogIndexEntry(identity, publicLocation, file));
        var publicRecord = new CatalogRecord(publicLocation, new CatalogContentBinding(identity.modelId(), content));
        var authLocation = new CatalogLocation(CatalogRootKind.AUTH, new ModelPath("workload/auth"));
        var authRecord = new CatalogRecord(authLocation, new CatalogContentBinding(identity.modelId(), content));
        var chunk = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)).findFirst()
            .orElseThrow();
        return new CatalogFixture(identity,
            new CatalogSnapshot(Map.of(identity.modelId(), publicRecord), List.of(), ModelScanReport.empty()),
            new CatalogSnapshot(Map.of(identity.modelId(), authRecord), List.of(), ModelScanReport.empty()), chunk);
    }

    private static ModelChunkRequestProto request(long transferId, CatalogFixture fixture) {
        var chunk = fixture.chunk();
        return ModelChunkRequestProto.newBuilder().setDataTransferId(transferId)
            .setModelId(ByteBuffer.wrap(fixture.identity().modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(fixture.identity().containerId().bytes()))
            .addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build())
            .build();
    }

    private static long usedHeap() {
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void append(ScenarioEvidence evidence, RunResult result) throws Exception {
        var sequence = 0;
        for (var event : result.events()) {
            evidence
                .append(new JsonlEvidenceSink.Observation(SCENARIO_ID, "%s-%04d".formatted(result.repeat(), sequence++),
                    result.repeat(), event.connectionId(), event.operationId(), event.kind(), event.payload()));
        }
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
            List.of("The exact 100-session workload terminated at a machine assertion or production boundary"),
            List.of(failure.getClass().getName() + ": " + Objects.toString(failure.getMessage(), "(no message)")),
            Map.of("contractViolation", 1L, "unexpectedError", 1L),
            "Failure is scoped to the lightweight production session/admission/dispatch/owner composition"));
    }

    private enum PortMode {
        NORMAL, PRESSURE, PRODUCTION_FAILURE
    }

    private static final class ConnectionOwner implements AutoCloseable {
        private final String connectionId;
        private final ResourceDispatchWorker worker;
        private final WorkloadLedger ledger;
        private final ServerResourceRuntime serverResources;
        private final ServerModelSession session;
        private final RecordingPort port;
        private final ServerAssetTransfers transfers;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private ConnectionOwner(String playerId, String connectionId, CatalogSnapshot catalog, Set<Hash256> grants,
            PortMode portMode, ResourceDispatchWorker worker, ServerResourceRuntime serverResources,
            WorkloadLedger ledger) {
            this.connectionId = connectionId;
            this.worker = worker;
            this.serverResources = serverResources;
            this.ledger = ledger;
            session = new ServerModelSession(() -> catalog, false);
            require(session.activate(), "session must activate exactly once");
            session.commitCatalog(catalog, grants);
            port = new RecordingPort(connectionId, portMode, ledger);
            transfers = new ServerAssetTransfers(session, worker, port, () -> true,
                failure -> ledger.failure(operation(failure.dataTransferId()), failure.reason().name()),
                serverResources);
            ledger.event("session-published", connectionId, null, Map.of("playerId", playerId, "grants",
                Integer.toString(grants.size()), "publicationCommitted", "true"));
        }

        private boolean accept(ModelChunkRequestProto request) {
            var activeBefore = transfers.activeCount();
            var queuedBefore = worker.queued(session);
            var handled = transfers.accept(request);
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (transfers.activeCount() > activeBefore && worker.queued(session) == queuedBefore
                && System.nanoTime() < deadline) {
                serverResources.tick();
                transfers.tick();
                Thread.yield();
            }
            var activeAfter = transfers.activeCount();
            var queuedAfter = worker.queued(session);
            require(activeAfter <= activeBefore || queuedAfter > queuedBefore,
                "chunk acquisition did not reach dispatch or a rejection terminal");
            if (activeAfter > activeBefore && queuedAfter > queuedBefore) {
                ledger.accepted(operation(request.dataTransferId()), connectionId, queuedAfter, activeAfter);
            }
            ledger.observeCapacity(connectionId, queuedAfter, activeAfter);
            return handled;
        }

        private void cancel(long transferId) {
            require(transfers.cancel(transferId), "cancel request must be structurally valid");
            ledger.terminal(operation(transferId), "cancellation");
            ledger.observeCapacity(connectionId, worker.queued(session), transfers.activeCount());
        }

        private void grant(Hash256 modelId) {
            session.setGrants(Set.of(modelId));
            ledger.event("authorization-change", connectionId, null, Map.of("grants", "1"));
        }

        private String operation(long transferId) {
            return connectionId + "/transfer-" + Long.toUnsignedString(transferId);
        }

        @Override
        public void close() {
            require(closeCalls.incrementAndGet() == 1, "connection state received duplicate close: " + connectionId);
            transfers.close();
            worker.disconnect(session);
            session.close();
            ledger.shutdownOutstanding(connectionId);
            ledger.event("owner-closed", connectionId, null, Map.of("activeTransfers",
                Integer.toString(transfers.activeCount()), "queuedPackets", Integer.toString(worker.queued(session))));
        }

        private String connectionId() {
            return connectionId;
        }

        private ServerModelSession session() {
            return session;
        }

        private RecordingPort port() {
            return port;
        }

        private int activeTransfers() {
            return transfers.activeCount();
        }

        private int closeCalls() {
            return closeCalls.get();
        }

        private WorkloadLedger ledger() {
            return ledger;
        }

        private void tick() {
            transfers.tick();
        }
    }

    private static final class RecordingPort implements TransportPort {
        private static final long NORMAL_HIGH_WATERMARK = 1024 * 1024;

        private final String connectionId;
        private final PortMode mode;
        private final WorkloadLedger ledger;
        private int highWatermarkReads;
        private boolean pressureReleased;

        private RecordingPort(String connectionId, PortMode mode, WorkloadLedger ledger) {
            this.connectionId = connectionId;
            this.mode = mode;
            this.ledger = ledger;
        }

        private void releasePressure() {
            require(mode == PortMode.PRESSURE, "only the pressure port has a release action");
            pressureReleased = true;
            ledger.event("transport-pressure-released", connectionId, null, Map.of());
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isWritable() {
            return mode != PortMode.PRESSURE || pressureReleased;
        }

        @Override
        public long pendingBytes() {
            return 0;
        }

        @Override
        public long highWatermarkBytes() {
            if (mode != PortMode.PRODUCTION_FAILURE) {
                return NORMAL_HIGH_WATERMARK;
            }
            return highWatermarkReads++ == 0 ? NORMAL_HIGH_WATERMARK : 1;
        }

        @Override
        public SendResult trySend(OutboundFrame frame) {
            try (var decoded = FrameCodec.decode(frame.bytes(), id -> id == ProtocolMessages.CHUNK_FRAGMENT_ID)) {
                var message = ProtocolBuffer.parse(decoded.protobuf(), ChunkFragmentProto::parseFrom);
                ledger.fragment(connectionId, message.dataTransferId(), message.offset(), message.finalFragment(),
                    decoded.attachment().size());
                return SendResult.SUCCESS;
            }
        }
    }

    private static final class WorkloadLedger {
        private final int hardLimit;
        private final Set<String> accepted = new LinkedHashSet<>();
        private final Map<String, String> terminals = new TreeMap<>();
        private final Map<String, String> rejections = new TreeMap<>();
        private final Map<String, Integer> progress = new TreeMap<>();
        private final Map<String, Integer> maxQueued = new TreeMap<>();
        private final Map<String, Integer> maxActive = new TreeMap<>();
        private final List<String> fragmentSequence = new ArrayList<>();
        private final List<LedgerEvent> events = new ArrayList<>();
        private long dispatchCalls;
        private long maxDispatchNanos;
        private int currentEffects;

        private WorkloadLedger(int hardLimit) {
            this.hardLimit = hardLimit;
        }

        private void accepted(String operation, String connectionId, int queued, int activeCount) {
            require(accepted.add(operation), "operation was accepted twice: " + operation);
            observeCapacity(connectionId, queued, activeCount);
            event("accepted", connectionId, operation,
                Map.of("queuedPackets", Integer.toString(queued), "activeTransfers", Integer.toString(activeCount)));
        }

        private void failure(String operation, String reason) {
            if (accepted.contains(operation)) {
                terminal(operation, "failure:" + reason);
            } else {
                rejection(operation, reason);
            }
        }

        private void rejection(String operation, String reason) {
            require(!accepted.contains(operation),
                "accepted owner was misclassified as pre-admission rejection: " + operation);
            require(rejections.putIfAbsent(operation, reason) == null, "request was rejected twice: " + operation);
            event("rejected-before-commitment", connection(operation), operation, Map.of("reason", reason));
        }

        private void fragment(String connectionId, long transferId, long offset, boolean last, int bytes) {
            var operation = connectionId + "/transfer-" + Long.toUnsignedString(transferId);
            require(accepted.contains(operation), "fragment was sent without an accepted exact owner: " + operation);
            progress.merge(connectionId, 1, Integer::sum);
            fragmentSequence.add(operation + "@" + offset + "+" + bytes + ":" + last);
            event("fragment-success", connectionId, operation, Map.of("offset", Long.toString(offset), "bytes",
                Integer.toString(bytes), "final", Boolean.toString(last)));
            if (last) {
                terminal(operation, "success");
            }
        }

        private void terminal(String operation, String terminal) {
            require(accepted.contains(operation), "terminal has no accepted exact owner: " + operation);
            require(terminals.putIfAbsent(operation, terminal) == null,
                "exact owner produced duplicate terminal: " + operation);
            event("terminal", connection(operation), operation, Map.of("classification", terminal));
        }

        private void shutdownOutstanding(String connectionId) {
            var prefix = connectionId + "/";
            accepted.stream().filter(operation -> operation.startsWith(prefix))
                .filter(operation -> !terminals.containsKey(operation)).toList()
                .forEach(operation -> terminal(operation, "owner-shutdown"));
        }

        private void observeCapacity(String connectionId, int queued, int activeCount) {
            require(queued >= 0 && queued <= hardLimit,
                "per-session queue exceeded production hard bound: " + connectionId);
            require(activeCount >= 0 && activeCount <= hardLimit,
                "per-session accepted owners exceeded production hard bound: " + connectionId);
            maxQueued.merge(connectionId, queued, Math::max);
            maxActive.merge(connectionId, activeCount, Math::max);
        }

        private void dispatch(long elapsedNanos) {
            dispatchCalls++;
            maxDispatchNanos = Math.max(maxDispatchNanos, elapsedNanos);
        }

        private void currentEffect(String connectionId) {
            currentEffects++;
            event("current-effect", connectionId, null, Map.of());
        }

        private void assertExpected(ScenarioInput input) {
            var expectedTerminals = new TreeMap<String, String>();
            input.acceptedWork().forEach(work -> expectedTerminals
                .put(work.connectionId() + "/transfer-" + Long.toUnsignedString(work.transferId()), work.terminal()));
            var expectedRejections = new TreeMap<String, String>();
            input.rejectedWork().forEach(work -> expectedRejections
                .put(work.connectionId() + "/transfer-" + Long.toUnsignedString(work.transferId()), work.reason()));
            require(accepted.equals(expectedTerminals.keySet()),
                "accepted exact-owner set differs from the fixed action stream");
            require(terminals.equals(expectedTerminals), "terminal ledger differs from the fixed action stream");
            require(rejections.equals(expectedRejections),
                "pre-admission rejection ledger differs from the fixed action stream");
        }

        private DeterministicSummary deterministic() {
            return new DeterministicSummary(accepted.size(), terminals.size(), fragmentSequence.size(),
                List.copyOf(fragmentSequence), Map.copyOf(terminals), Map.copyOf(rejections), Map.copyOf(progress),
                Map.copyOf(maxQueued), Map.copyOf(maxActive), currentEffects);
        }

        private void closureSummary(int connectionOwners) {
            event("resource-closure-summary", null, null,
                Map.of("acceptedPacketSourceCreates", Integer.toString(accepted.size()), "acceptedPacketSourceCloses",
                    Integer.toString(terminals.size()), "connectionOwnerCreates", Integer.toString(connectionOwners),
                    "connectionOwnerCloses", Integer.toString(connectionOwners), "remainingAcceptedOwners", "0",
                    "remainingQueuedPackets", "0", "hardLimit", Integer.toString(hardLimit)));
        }

        private int progress(String connectionId) {
            return progress.getOrDefault(connectionId, 0);
        }

        private void event(String kind, String connectionId, String operationId, Map<String, String> payload) {
            events.add(new LedgerEvent(kind, connectionId, operationId, payload));
        }

        private static String connection(String operation) {
            return operation.substring(0, operation.indexOf('/'));
        }

        private long dispatchCalls() {
            return dispatchCalls;
        }

        private long maxDispatchNanos() {
            return maxDispatchNanos;
        }

        private int currentEffects() {
            return currentEffects;
        }

        private List<LedgerEvent> events() {
            return events;
        }
    }

    private record CatalogFixture(ModelFileIdentity identity, CatalogSnapshot publicCatalog,
        CatalogSnapshot authCatalog, AssetContainerView.ChunkInfo chunk) {
    }

    private record GenerationObservation(ServerConnectionRegistryFixture<ConnectionOwner> fixture,
        ServerConnectionRegistryFixture.Generation<ConnectionOwner> generation, boolean expectedCurrent) {
    }

    private record RunResult(String repeat, DeterministicSummary deterministic, List<LedgerEvent> events) {
    }

    private record DeterministicSummary(int acceptedOwners, int terminalOwners, int successfulFragments,
        List<String> fragmentSequence, Map<String, String> terminals, Map<String, String> rejections,
        Map<String, Integer> progress, Map<String, Integer> maxQueued, Map<String, Integer> maxActive,
        int currentEffects) {
    }

    private record SoftMetrics(long wallNanos, long dispatchCalls, long lagSpikeNanos, long heapUsedBefore,
        long heapUsedAfter) {
        private long msptEquivalentNanos() {
            return dispatchCalls == 0 ? 0 : wallNanos / dispatchCalls;
        }

        private long heapDelta() {
            return heapUsedAfter - heapUsedBefore;
        }
    }

    private record LedgerEvent(String kind, String connectionId, String operationId, Map<String, String> payload) {
        private LedgerEvent {
            payload = Map.copyOf(payload);
        }
    }

}
