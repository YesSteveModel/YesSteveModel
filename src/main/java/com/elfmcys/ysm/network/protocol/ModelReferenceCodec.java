package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.proto.network.ModelReferenceProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.nio.ByteBuffer;
import org.jetbrains.annotations.Nullable;

public final class ModelReferenceCodec {
    private ModelReferenceCodec() {}

    public static ModelReferenceProto create(PublishedModelId modelId) {
        var builder = ModelReferenceProto.newBuilder();
        if (modelId.isIntrinsicDefault()) {
            builder.setBuiltinDefault(true);
        } else {
            builder.setModelId(((PublishedModelId.Model)modelId).hash().toString());
        }
        return builder.build();
    }

    /** Returns null for the intrinsic builtin default. */
    public static @Nullable ModelId read(ModelReferenceProto value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing model reference");
        }
        if (value.hasBuiltinDefault() && value.builtinDefault()) {
            return null;
        }
        if (!value.hasModelId() || !ModelId.isValid(value.modelId())) {
            throw new IllegalArgumentException("Invalid model reference");
        }
        return ModelId.parse(value.modelId());
    }

    public static boolean valid(ModelReferenceProto value) {
        return value != null && (value.hasBuiltinDefault() && value.builtinDefault()
            || value.hasModelId() && ModelId.isValid(value.modelId()));
    }
}
