package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.ModelId;
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
    void maximumNamedIdentityUsesTheCompleteStringWireField() throws Exception {
        var text = "o".repeat(32) + "/" + "n".repeat(32) + "@1.0.0+" + "a".repeat(122);
        var expected = new byte[197];
        expected[0] = 0x0a;
        expected[1] = (byte)0xc2;
        expected[2] = 0x01;
        System.arraycopy(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, expected, 3, 194);
        var reference = ModelReferenceCodec.create(PublishedModelId.model(ModelId.parse(text)));
        assertTrue(ModelReferenceCodec.valid(reference));
        assertEquals(ModelId.parse(text), ModelReferenceCodec.read(ModelReferenceProto.parseFrom(expected)));
        assertEquals(java.util.HexFormat.of().formatHex(expected),
            java.util.HexFormat.of().formatHex(com.elfmcys.ysm.util.ProtoUtil.serializeToArray(reference)));
    }

    @Test
    void distinguishesIntrinsicDefaultFromRegularHash() {
        var hash = hash(1);
        var regular = ModelReferenceCodec.create(PublishedModelId.model(hash));
        assertEquals(hash, ModelReferenceCodec.read(regular));
        assertTrue(regular.hasModelId());
        assertFalse(regular.hasBuiltinDefault());
        assertEquals(hash.toString(), regular.modelId());

        var builtin = ModelReferenceCodec.create(PublishedModelId.INTRINSIC_DEFAULT);
        assertNull(ModelReferenceCodec.read(builtin));
        assertTrue(builtin.hasBuiltinDefault());
        assertTrue(builtin.builtinDefault());
        assertFalse(builtin.hasModelId());
        assertTrue(ModelReferenceCodec.valid(builtin));
    }

    @Test
    void rejectsAbsentFalseAndTruncatedReferences() {
        assertThrows(IllegalArgumentException.class, () -> ModelReferenceCodec.read(null));
        assertFalse(ModelReferenceCodec.valid(ModelReferenceProto.newBuilder().build()));
        assertFalse(ModelReferenceCodec.valid(ModelReferenceProto.newBuilder().setBuiltinDefault(false).build()));
        assertFalse(ModelReferenceCodec.valid(ModelReferenceProto.newBuilder()
            .setModelId("hash:" + java.util.HexFormat.of().formatHex(new byte[Hash256.SIZE - 1])).build()));
    }

    private static ModelId hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[bytes.length - 1] = (byte)marker;
        return ModelId.fromHash(new Hash256(bytes));
    }
}
