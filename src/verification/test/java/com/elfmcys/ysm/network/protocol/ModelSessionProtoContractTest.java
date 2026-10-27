package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.SessionDecision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelSessionProtoContractTest {
    private static final Path PROTO = Path.of("src/main/proto/network/model_session.proto");

    @Test
    void messagesMatchTheNormativeProtocolSnapshot() throws IOException {
        var snapshot = Path.of("docs/standards/protocol-v1/proto/network/model_session.proto");
        assertEquals(normalized(snapshot), normalized(PROTO));
    }

    @Test
    void protocolEnumsKeepTheirExactWireNumbers() {
        assertEquals(0, SessionDecision.UNSPECIFIED.getNumber());
        assertEquals(1, SessionDecision.ACCEPT.getNumber());
        assertEquals(2, SessionDecision.DECLINE.getNumber());
        assertEquals(0, com.elfmcys.ysm.proto.network.CollectionOperationType.UNSPECIFIED.getNumber());
        assertEquals(1, com.elfmcys.ysm.proto.network.CollectionOperationType.FULL.getNumber());
        assertEquals(2, com.elfmcys.ysm.proto.network.CollectionOperationType.ADD.getNumber());
        assertEquals(3, com.elfmcys.ysm.proto.network.CollectionOperationType.REMOVE.getNumber());
        assertEquals(4, com.elfmcys.ysm.proto.network.CollectionOperationType.CLEAR.getNumber());
        assertEquals(6, com.elfmcys.ysm.proto.network.ResourceFailureReason.UNAVAILABLE.getNumber());
        assertEquals(4, com.elfmcys.ysm.proto.network.SelectionStatus.INVALID_REQUEST.getNumber());
        assertEquals(2, com.elfmcys.ysm.proto.network.PresentationDelivery.UNAVAILABLE.getNumber());
        assertEquals(3, com.elfmcys.ysm.proto.network.PresentationDelivery.ABSENT.getNumber());
    }

    private static String normalized(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").stripTrailing();
    }
}
