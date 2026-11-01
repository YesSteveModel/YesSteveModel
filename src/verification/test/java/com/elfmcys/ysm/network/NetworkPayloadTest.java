package com.elfmcys.ysm.network;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPayloadTest {
    @Test
    void closingPayloadDropsOnlyItsReference() {
        var shared = SharedArrayBuffer.adopt(ArrayBuffer.move(new byte[] {1, 2, 3}));
        var payload = NetworkPayload.withRaw(MetadataPrefixRequestProto.newBuilder().build(), shared);
        var retained = payload.raw().orElseThrow();
        assertSame(shared, retained);
        payload.close();
        assertTrue(payload.raw().isEmpty());
        assertEquals(0, payload.rawSize());
        assertEquals(3, retained.size());
        assertEquals(2, retained.nio().get(1));
    }

    @Test
    void storageInputKeepsItsCloseRightAndCannotMutatePublishedBytes() {
        try (var storage = ArrayBuffer.move(new byte[] {1, 2, 3});
            var payload = NetworkPayload.withRaw(MetadataPrefixRequestProto.newBuilder().build(), storage)) {
            storage.array()[1] = 9;
            var retained = payload.raw().orElseThrow();
            assertEquals(2, retained.nio().get(1));
            payload.close();
            assertEquals(9, storage.nio().get(1));
            assertEquals(2, retained.nio().get(1));
        }
    }
}
