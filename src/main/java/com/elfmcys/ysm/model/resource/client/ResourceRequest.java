package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.expression.host.ExpressionBinding;

import java.util.Objects;

public record ResourceRequest(PublishedModelId modelId, String targetId, String requestedTexture,
    BakeProfile bakeProfile, ExpressionBinding expressionBinding, ModelResourcePurpose purpose) {
    public ResourceRequest(ModelId modelId, String targetId, String requestedTexture, BakeProfile bakeProfile,
        ExpressionBinding expressionBinding, ModelResourcePurpose purpose) {
        this(PublishedModelId.model(modelId), targetId, requestedTexture, bakeProfile, expressionBinding, purpose);
    }

    public ResourceRequest(ModelId modelId, String targetId, String requestedTexture, BakeProfile bakeProfile,
        ExpressionBinding expressionBinding) {
        this(PublishedModelId.model(modelId), targetId, requestedTexture, bakeProfile, expressionBinding,
            ModelResourcePurpose.FULL);
    }

    public ResourceRequest(ModelId modelId, String targetId, String requestedTexture, BakeProfile bakeProfile) {
        this(modelId, targetId, requestedTexture, bakeProfile, ExpressionBinding.standalone());
    }

    public ResourceRequest {
        Objects.requireNonNull(modelId, "modelId");
        targetId = Objects.requireNonNull(targetId, "targetId");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("Render target id cannot be blank");
        }
        requestedTexture = Objects.requireNonNullElse(requestedTexture, "");
        Objects.requireNonNull(bakeProfile, "bakeProfile");
        Objects.requireNonNull(expressionBinding, "expressionBinding");
        Objects.requireNonNull(purpose, "purpose");
    }
}
