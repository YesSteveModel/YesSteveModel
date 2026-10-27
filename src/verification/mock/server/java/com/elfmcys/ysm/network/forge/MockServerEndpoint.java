package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.mock.classpath.EndpointEvidence;
import com.elfmcys.ysm.mock.classpath.ProductionWire;
import com.elfmcys.ysm.mock.classpath.SocketByteCarrier;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import us.hebi.quickbuf.ProtoMessage;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Ordinary-JVM dedicated-server endpoint for the side-specific classpath smoke. */
public final class MockServerEndpoint {
    private static final String SIDE = "dedicated-server";

    private MockServerEndpoint() {}

    public static void main(String[] args) throws Throwable {
        if (args.length == 9 && ("full".equals(args[5]) || "exact-100".equals(args[5]))) {
            MockServerSystem.run(args);
            return;
        }
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected port, ready path, exit path, events path, and scenario id");
        }
        var requestedPort = Integer.parseInt(args[0]);
        var ready = Path.of(args[1]);
        var exit = Path.of(args[2]);
        var evidence = new EndpointEvidence(args[4], SIDE, Path.of(args[3]));
        ServerModelSession session = null;
        Throwable failure = null;
        try {
            require(EndpointEvidence.workingDirectoryEmpty(), "Server working directory was not empty at process load");
            var messageCount = ProductionWire.registeredMessageCount();
            require(messageCount > 0, "Production protocol registry was empty");
            evidence.append("server-load", "server-endpoint", "endpoint-load",
                Map.of("bootstrapMainClass", MockServerEndpoint.class.getName(), "childProcessCount",
                    Long.toString(ProcessHandle.current().descendants().count()), "headless",
                    System.getProperty("java.awt.headless"), "physicalSide", SIDE, "protocolMessageCount",
                    Integer.toString(messageCount), "workingDirectory",
                    Path.of("").toAbsolutePath().normalize().toString(), "workingDirectoryEmpty", "true"));

            var listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort));
            listener.setSoTimeout(15_000);
            session = new ServerModelSession(CatalogSnapshot::empty, false);
            try (listener) {
                var port = listener.getLocalPort();
                EndpointEvidence.writeNew(ready,
                    Map.of("bootstrap", "ordinary-java-main", "headless", true, "physicalSide", SIDE, "pid",
                        ProcessHandle.current().pid(), "port", port, "workingDirectory",
                        Path.of("").toAbsolutePath().normalize().toString(), "workingDirectoryEmpty", true));
                evidence.append("server-ready", "server-endpoint", "endpoint-ready",
                    Map.of("physicalSide", SIDE, "port", Integer.toString(port)));

                var socket = listener.accept();
                socket.setSoTimeout(15_000);
                try (socket; var carrier = new SocketByteCarrier(socket)) {
                    send(carrier, evidence, "hello", ServerHelloProto.newBuilder()
                        .setProtocolVersion(ProtocolVersion.TRANSPORT_VERSION).setSyncRoaming(false).build());
                    var response = receive(carrier, evidence, "session-response", SessionResponseProto.class,
                        MessageDirection.CLIENT_TO_SERVER).message();
                    require(response.decision() == SessionDecision.ACCEPT,
                        "Production client did not accept the server hello");
                    require(session.activate(), "Production server session did not activate");
                    session.commitCatalog(CatalogSnapshot.empty());

                    var transferId = session.allocatePublicationId();
                    var fragments = emptyPublication(transferId);
                    for (var sequence = 0; sequence < fragments.size(); sequence++) {
                        send(carrier, evidence, "publication-" + sequence, fragments.get(sequence));
                    }
                    evidence.append("publication-commit", "server-session", "session-publication-committed",
                        Map.of("publishedEntries", "0", "publicationId", Long.toUnsignedString(transferId),
                            "sessionActive", Boolean.toString(session.active())));

                    var request = receive(carrier, evidence, "select-request", SelectModelRequestProto.class,
                        MessageDirection.CLIENT_TO_SERVER).message();
                    require(request.hasIntrinsicDefault() && request.intrinsicDefault(),
                        "Smoke request was not intrinsic-default selection");
                    var result = session.select(new Selection.IntrinsicDefault());
                    require(result == ServerModelSession.SelectionResult.ACCEPTED,
                        "Production server session rejected intrinsic-default selection");
                    send(carrier, evidence, "select-response",
                        SelectModelResultProto.newBuilder().setStatus(SelectionStatus.ACCEPTED).build());
                }
            }
            session.close();
            require(!session.active(), "Production server session did not close");
            evidence.append("server-close", "server-session", "owner-close",
                Map.of("sessionActive", "false", "terminalCount", "1"));
        } catch (Throwable error) {
            failure = error;
            try {
                evidence.append("server-failure", "server-endpoint", "endpoint-failure",
                    Map.of("error", error.getClass().getName(), "message", String.valueOf(error.getMessage())));
            } catch (Throwable evidenceFailure) {
                error.addSuppressed(evidenceFailure);
            }
            throw error;
        } finally {
            if (session != null && (session.active() || session.pending())) {
                try {
                    session.close();
                } catch (Throwable closeFailure) {
                    if (failure != null) {
                        failure.addSuppressed(closeFailure);
                    } else {
                        throw closeFailure;
                    }
                }
            }
            var closed = session != null && !session.active() && !session.pending();
            try {
                EndpointEvidence.writeNew(exit,
                    Map.of("ownerClosed", closed, "physicalSide", SIDE, "status",
                        failure == null ? "success" : "failure", "workingDirectoryEmpty",
                        EndpointEvidence.workingDirectoryEmpty()));
            } catch (Throwable exitFailure) {
                if (failure != null) {
                    failure.addSuppressed(exitFailure);
                } else {
                    throw exitFailure;
                }
            }
        }
    }

    private static List<SessionFullFragmentProto> emptyPublication(long transferId) {
        var full = CollectionOperationType.FULL;
        return List.of(
            SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(0).setFinalFragment(false)
                .setCatalog(CatalogCollectionOperationProto.newBuilder().setOpType(full).build()).build(),
            SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(1).setFinalFragment(false)
                .setGrants(GrantCollectionOperationProto.newBuilder().setOpType(full).build()).build(),
            SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(2).setFinalFragment(false)
                .setPackPresentations(PackPresentationCollectionOperationProto.newBuilder().setOpType(full).build())
                .build(),
            SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(3).setFinalFragment(true)
                .setDefaultAnimations(DefaultAnimationCollectionOperationProto.newBuilder().setOpType(full).build())
                .build());
    }

    private static void send(SocketByteCarrier carrier, EndpointEvidence evidence, String actionId,
        ProtoMessage<?> message) throws Exception {
        var encoded = ProductionWire.encode(message, null);
        evidence.append(actionId, "typed-frame-" + actionId, "typed-frame-send", framePayload(encoded.messageId(),
            encoded.direction().name(), encoded.messageType(), encoded.bytes(), encoded.attachmentBytes()));
        carrier.send(encoded.bytes());
    }

    private static <T extends ProtoMessage<T>> ProductionWire.Decoded<T> receive(SocketByteCarrier carrier,
        EndpointEvidence evidence, String actionId, Class<T> type, MessageDirection direction) throws Exception {
        var bytes = carrier.receive();
        var decoded = ProductionWire.decode(bytes, type, direction);
        evidence.append(actionId, "typed-frame-" + actionId, "typed-frame-receive", framePayload(decoded.messageId(),
            decoded.direction().name(), decoded.messageType(), bytes, decoded.attachment().length));
        return decoded;
    }

    private static Map<String, String> framePayload(int id, String direction, String type, byte[] bytes,
        int attachmentBytes) {
        return Map.of("attachmentBytes", Integer.toString(attachmentBytes), "codec",
            "ProtocolMessages+ProtocolBuffer+FrameCodec", "direction", direction, "frameBytes",
            Integer.toString(bytes.length), "messageId", Integer.toString(id), "messageType", type, "sha256",
            EvidenceJson.sha256(bytes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
