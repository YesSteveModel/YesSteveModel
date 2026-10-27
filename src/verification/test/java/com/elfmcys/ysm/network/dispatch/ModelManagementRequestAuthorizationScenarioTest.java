package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.EvidenceRun;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;
import com.elfmcys.ysm.mock.evidence.ScenarioEvidence;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelManagementRequestAuthorizationScenarioTest {
    private static final String SCENARIO_ID = "MMR-DOM-REMOTE-AUTH-001";

    @TempDir
    Path temp;

    @Test
    @Tag("model-management-mock")
    void requestAdmissionFreezesCurrentAuthorizationForTheAcceptedOwner() throws Throwable {
        assumeTrue(EvidenceRun.isConfigured(), "Only the explicit mock task writes acceptance evidence");
        var fixture = fixture();
        var input = new ScenarioInput(identity(fixture.identity()),
            List.of(new ActionInput("grant", 1, true, true, "accepted"),
                new ActionInput("revoke", 0, false, true, "authority changed"),
                new ActionInput("new-request-after-revoke", 2, false, true, "unauthorized"),
                new ActionInput("drain-accepted-after-revoke", 1, false, true, "success"),
                new ActionInput("unrestricted-new-request", 3, false, false, "accepted")));
        var inputBytes = EvidenceJson.canonicalBytes(input);
        var run = EvidenceRun.openConfigured();
        var identity = run.identity(SCENARIO_ID, List.of("FA-002", "FA-007"),
            List.of("CO-004", "CO-007", "CO-011", "CO-013", "CO-016"), EvidenceRun.Radius.DETERMINISTIC_COMPOSITION,
            true, inputBytes);
        var evidence = run.scenario(identity, inputBytes);

        try {
            execute(fixture, evidence);
            evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS,
                List.of("A protected exact chunk request was admitted only while its current grant was present",
                    "Grant revocation rejected the next request before a transfer owner or queue entry was created",
                    "The previously accepted transfer completed exactly once after revocation without re-authorization",
                    "Changing current restricted-auth policy affected only a subsequent admission"),
                List.of(), Map.of("contractViolation", 0L, "unexpectedError", 0L, "expectedUnauthorized", 1L),
                "Deterministic server session/transfer/dispatch composition with a test TransportPort; no Forge or real network ordering claim"));
        } catch (Throwable failure) {
            completeFailure(evidence, failure);
            throw failure;
        }
    }

    private void execute(Fixture fixture, ScenarioEvidence evidence) throws Exception {
        var catalog = new CatalogSnapshot(Map.of(fixture.identity().modelId(), fixture.record()), List.of(),
            ModelScanReport.empty());
        var owner = new ServerModelSession(() -> catalog, false);
        require(owner.activate(), "server model session must activate once");
        owner.commitCatalog(catalog, Set.of(fixture.identity().modelId()));
        var restricted = new AtomicBoolean(true);
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new RecordingPort();
        try (var runtime = new ServerResourceRuntime(new PreviewStore(temp.resolve("preview-cache")), ignored -> {
        }); var worker = new ResourceDispatchWorker(1, 8, 0); var transfers =
            new ServerAssetTransfers(owner, worker, port, restricted::get, failures::add, runtime, ignored -> temp)) {
            var first = request(1, fixture);
            require(transfers.accept(first), "granted request must be structurally accepted");
            awaitQueued(runtime, transfers, worker, owner);
            require(transfers.activeCount() == 1 && worker.queued(owner) == 1,
                "accepted request must own one transfer and one packet");
            record(evidence, "grant", 1, "admission",
                Map.of("authorized", "true", "activeTransfers", "1", "queuedPackets", "1"));

            owner.setGrants(Set.of());
            record(evidence, "revoke", 0, "authority-change",
                Map.of("currentGrants", "0", "acceptedTransferStillActive", Integer.toString(transfers.activeCount())));

            var activeBeforeRejection = transfers.activeCount();
            var queuedBeforeRejection = worker.queued(owner);
            require(transfers.accept(request(2, fixture)),
                "valid unauthorized request must be handled without retiring the session");
            require(
                failures.size() == 1 && failures.get(0).dataTransferId() == 2
                    && failures.get(0).reason() == ResourceFailureReason.UNAUTHORIZED,
                "new admission must read the revoked current authorization");
            require(transfers.activeCount() == activeBeforeRejection && worker.queued(owner) == queuedBeforeRejection,
                "authorization rejection must create no transfer owner or queued work");
            record(evidence, "new-request-after-revoke", 2, "admission-rejected",
                Map.of("reason", failures.get(0).reason().name(), "newOwners", "0", "newQueuedPackets", "0"));

            drain(worker, transfers, owner);
            require(transfers.activeCount() == 0, "accepted transfer must reach one completed terminal");
            var firstDelivery = decode(port.take());
            require(firstDelivery.transferId() == 1 && firstDelivery.payloadBytes() == fixture.chunk().size(),
                "accepted request must finish with its exact validated chunk bytes");
            record(evidence, "drain-accepted-after-revoke", 1, "transfer-terminal",
                Map.of("terminal", "success", "payloadBytes", Integer.toString(firstDelivery.payloadBytes()),
                    "activeTransfers", "0", "queuedPackets", "0"));

            restricted.set(false);
            require(transfers.accept(request(3, fixture)), "unrestricted current policy must admit the next request");
            awaitQueued(runtime, transfers, worker, owner);
            require(transfers.activeCount() == 1, "new unrestricted admission must create its own exact owner");
            drain(worker, transfers, owner);
            var thirdDelivery = decode(port.take());
            require(thirdDelivery.transferId() == 3 && thirdDelivery.payloadBytes() == fixture.chunk().size(),
                "subsequent admission must use current policy and exact source");
            require(transfers.activeCount() == 0 && worker.queued(owner) == 0,
                "all accepted transfers must be terminal at scenario end");
            record(evidence, "unrestricted-new-request", 3, "transfer-terminal",
                Map.of("restrictedAuth", "false", "terminal", "success", "activeTransfers", "0", "queuedPackets", "0"));
        } finally {
            owner.close();
        }
    }

    private static void awaitQueued(ServerResourceRuntime runtime, ServerAssetTransfers transfers,
        ResourceDispatchWorker worker, ServerModelSession owner) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (worker.queued(owner) == 0 && System.nanoTime() < deadline) {
            runtime.tick();
            transfers.tick();
            Thread.yield();
        }
        require(worker.queued(owner) > 0, "chunk acquisition did not reach dispatch");
    }

    private Fixture fixture() throws Exception {
        var manifest = ModelManagementRequestAuthorizationScenarioTest.class
            .getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
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
        var location = new CatalogLocation(CatalogRootKind.AUTH, new ModelPath("protected"));
        var content = ManagedContainer.openIndexed(new CatalogIndexEntry(identity, location, file));
        var record = new CatalogRecord(location, new CatalogContentBinding(identity.modelId(), content));
        var chunk = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)).findFirst()
            .orElseThrow();
        return new Fixture(identity, record, chunk);
    }

    private static ModelChunkRequestProto request(long transferId, Fixture fixture) {
        var chunk = fixture.chunk();
        return ModelChunkRequestProto.newBuilder().setDataTransferId(transferId)
            .setModelId(ByteBuffer.wrap(fixture.identity().modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(fixture.identity().containerId().bytes()))
            .addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build())
            .build();
    }

    private static void drain(ResourceDispatchWorker worker, ServerAssetTransfers transfers, ServerModelSession owner) {
        while (worker.queued(owner) != 0) {
            require(worker.dispatchOnce(), "queued accepted work must make deterministic progress");
            transfers.tick();
        }
        transfers.tick();
    }

    private static Delivery decode(List<byte[]> frames) {
        require(!frames.isEmpty(), "accepted transfer must produce typed frames");
        long transferId = 0;
        var payloadBytes = 0;
        for (var bytes : frames) {
            try (var decoded = FrameCodec.decode(bytes, id -> id == ProtocolMessages.CHUNK_FRAGMENT_ID)) {
                var message = ProtocolBuffer.parse(decoded.protobuf(), ChunkFragmentProto::parseFrom);
                if (transferId == 0) {
                    transferId = message.dataTransferId();
                }
                require(transferId == message.dataTransferId(), "one transfer ledger cannot mix operation identities");
                payloadBytes += decoded.attachment().size();
            }
        }
        return new Delivery(transferId, payloadBytes);
    }

    private static void record(ScenarioEvidence evidence, String action, long transferId, String kind,
        Map<String, String> payload) throws Exception {
        evidence.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, action, "server-authorization",
            "connection-server-A", transferId == 0 ? "server-authority" : "transfer-" + transferId, kind, payload));
    }

    private static String identity(ModelFileIdentity identity) {
        return identity.modelId() + ":" + identity.containerId();
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
            List.of("The request-time authorization scenario terminated at a machine assertion or production boundary"),
            List.of(failure.getClass().getName() + ": " + Objects.toString(failure.getMessage(), "(no message)")),
            Map.of("contractViolation", 1L, "unexpectedError", 1L),
            "Failure is scoped to deterministic server session/transfer/dispatch composition"));
    }

    private record Fixture(ModelFileIdentity identity, CatalogRecord record, AssetContainerView.ChunkInfo chunk) {
    }

    private record Delivery(long transferId, int payloadBytes) {
    }

    private record ScenarioInput(String identity, List<ActionInput> actions) {
    }

    private record ActionInput(String actionId, long transferId, boolean granted, boolean restrictedAuth,
        String expectedResult) {
    }

    private static final class RecordingPort implements TransportPort {
        private final List<byte[]> frames = new ArrayList<>();

        private List<byte[]> take() {
            var result = List.copyOf(frames);
            frames.clear();
            return result;
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
            frames.add(frame.bytes());
            return SendResult.SUCCESS;
        }
    }
}
