package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.mock.classpath.EndpointEvidence;
import com.elfmcys.ysm.mock.classpath.ProductionWire;
import com.elfmcys.ysm.mock.classpath.SocketByteCarrier;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.network.session.SessionMode;
import us.hebi.quickbuf.ProtoMessage;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;

/** Ordinary-JVM client endpoint for the side-specific classpath smoke. */
public final class MockClientEndpoint {
    private static final String SIDE = "client";

    private MockClientEndpoint() {}

    public static void main(String[] args) throws Throwable {
        if (args.length == 9 && ("full".equals(args[5]) || "exact-100".equals(args[5]))) {
            MockClientSystem.run(args);
            return;
        }
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected port, ready path, exit path, events path, and scenario id");
        }
        var port = Integer.parseInt(args[0]);
        var ready = Path.of(args[1]);
        var exit = Path.of(args[2]);
        var evidence = new EndpointEvidence(args[4], SIDE, Path.of(args[3]));
        ClientModelSession session = null;
        Throwable failure = null;
        try {
            require(EndpointEvidence.workingDirectoryEmpty(), "Client working directory was not empty at process load");
            var messageCount = ProductionWire.registeredMessageCount();
            require(messageCount > 0, "Production protocol registry was empty");
            evidence.append("client-load", "client-endpoint", "endpoint-load",
                Map.of("bootstrapMainClass", MockClientEndpoint.class.getName(), "childProcessCount",
                    Long.toString(ProcessHandle.current().descendants().count()), "headless",
                    System.getProperty("java.awt.headless"), "physicalSide", SIDE, "protocolMessageCount",
                    Integer.toString(messageCount), "workingDirectory",
                    Path.of("").toAbsolutePath().normalize().toString(), "workingDirectoryEmpty", "true"));

            var socket = new Socket();
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 10_000);
            socket.setSoTimeout(15_000);
            try (socket; var carrier = new SocketByteCarrier(socket)) {
                EndpointEvidence.writeNew(ready,
                    Map.of("bootstrap", "ordinary-java-main", "headless", true, "physicalSide", SIDE, "pid",
                        ProcessHandle.current().pid(), "port", port, "workingDirectory",
                        Path.of("").toAbsolutePath().normalize().toString(), "workingDirectoryEmpty", true));
                evidence.append("client-ready", "client-endpoint", "endpoint-ready",
                    Map.of("physicalSide", SIDE, "port", Integer.toString(port)));

                session = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
                var hello =
                    receive(carrier, evidence, "hello", ServerHelloProto.class, MessageDirection.SERVER_TO_CLIENT)
                        .message();
                var response = session.onServerHello(hello.protocolVersion(), hello.syncRoaming()).orElseThrow();
                require(response.accepted(), "Production client declined the server hello");
                send(carrier, evidence, "session-response",
                    SessionResponseProto.newBuilder().setDecision(SessionDecision.ACCEPT).build());

                var receiver = new SessionCollectionPublication.Receiver(Map.of());
                RemotePublicationSnapshot publication = null;
                for (var sequence = 0; sequence < 4; sequence++) {
                    var fragment = receive(carrier, evidence, "publication-" + sequence, SessionFullFragmentProto.class,
                        MessageDirection.SERVER_TO_CLIENT).message();
                    var result = receiver.acceptFull(fragment, publication);
                    if (sequence < 3) {
                        require(result.status() == SessionCollectionPublication.Status.PENDING,
                            "Publication completed before all collection parts arrived");
                    } else {
                        require(result.status() == SessionCollectionPublication.Status.FULL,
                            "Production publication receiver rejected the full transaction");
                        publication = result.publication();
                    }
                }
                session.publishFull(publication);
                session.publishActivation(new ActivationSnapshot(publication, Map.of()));
                require(session.state() == ClientModelSession.State.ACTIVE,
                    "Production client session did not become active");
                evidence.append("publication-commit", "client-session", "session-publication-active",
                    Map.of("activationTerminal", "true", "publishedEntries",
                        Integer.toString(publication.entries().size()), "sessionState", session.state().name()));

                send(carrier, evidence, "select-request",
                    SelectModelRequestProto.newBuilder().setIntrinsicDefault(true).setTextureId("").build());
                var result = receive(carrier, evidence, "select-response", SelectModelResultProto.class,
                    MessageDirection.SERVER_TO_CLIENT).message();
                require(result.status() == SelectionStatus.ACCEPTED,
                    "Production server rejected the intrinsic-default request");
            }
            session.close();
            require(session.state() == ClientModelSession.State.CLOSED, "Production client session did not close");
            evidence.append("client-close", "client-session", "owner-close",
                Map.of("sessionState", session.state().name(), "terminalCount", "1"));
        } catch (Throwable error) {
            failure = error;
            try {
                evidence.append("client-failure", "client-endpoint", "endpoint-failure",
                    Map.of("error", error.getClass().getName(), "message", String.valueOf(error.getMessage())));
            } catch (Throwable evidenceFailure) {
                error.addSuppressed(evidenceFailure);
            }
            throw error;
        } finally {
            if (session != null && session.state() != ClientModelSession.State.CLOSED) {
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
            var closed = session != null && session.state() == ClientModelSession.State.CLOSED;
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
