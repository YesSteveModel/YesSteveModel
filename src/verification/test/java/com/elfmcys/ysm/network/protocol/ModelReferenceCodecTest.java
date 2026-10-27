package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.network.ModelReferenceProto;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelReferenceCodecTest {
    @Test
    void distinguishesIntrinsicDefaultFromRegularHash() {
        var hash = hash(1);
        var regular = ModelReferenceCodec.create(hash, null);
        assertEquals(hash, ModelReferenceCodec.read(regular));

        var builtin = ModelReferenceCodec.create(null, hash);
        assertNull(ModelReferenceCodec.read(builtin));
        assertTrue(ModelReferenceCodec.valid(builtin));
    }

    @Test
    void rejectsAbsentFalseAndTruncatedReferences() {
        assertThrows(IllegalArgumentException.class, () -> ModelReferenceCodec.read(null));
        assertFalse(ModelReferenceCodec.valid(ModelReferenceProto.newBuilder().build()));
        assertFalse(ModelReferenceCodec.valid(ModelReferenceProto.newBuilder().setBuiltinDefault(false).build()));
        assertFalse(ModelReferenceCodec
            .valid(ModelReferenceProto.newBuilder().setModelHash(ByteBuffer.wrap(new byte[Hash256.SIZE - 1])).build()));
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[bytes.length - 1] = (byte)marker;
        return new Hash256(bytes);
    }
}
