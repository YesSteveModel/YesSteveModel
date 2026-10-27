package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.network.ModelReferenceProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.nio.ByteBuffer;
import org.jetbrains.annotations.Nullable;

public final class ModelReferenceCodec {
    private ModelReferenceCodec() {}

    public static ModelReferenceProto create(@Nullable Hash256 hash, @Nullable Hash256 builtinDefaultHash) {
        var builder = ModelReferenceProto.newBuilder();
        if (hash == null || hash.equals(builtinDefaultHash)) {
            builder.setBuiltinDefault(true);
        } else {
            builder.setModelHash(ByteBuffer.wrap(hash.bytes()));
        }
        return builder.build();
    }

    /** Returns null for the intrinsic builtin default. */
    public static @Nullable Hash256 read(ModelReferenceProto value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing model reference");
        }
        if (value.hasBuiltinDefault() && value.builtinDefault()) {
            return null;
        }
        if (!value.hasModelHash() || value.modelHash().remaining() != Hash256.SIZE) {
            throw new IllegalArgumentException("Invalid model reference");
        }
        return new Hash256(ProtoBytes.copy(value.modelHash()));
    }

    public static boolean valid(ModelReferenceProto value) {
        return value != null && (value.hasBuiltinDefault() && value.builtinDefault()
            || value.hasModelHash() && value.modelHash().remaining() == Hash256.SIZE);
    }
}
