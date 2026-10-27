package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.expression.host.ExpressionBinding;

import java.util.Objects;

public record RenderTargetKey(String targetId, String selectedTexture, BakeProfile bakeProfile,
    ExpressionBinding expressionBinding, ModelResourcePurpose purpose) {
    public RenderTargetKey(String targetId, String selectedTexture, BakeProfile bakeProfile,
        ExpressionBinding expressionBinding) {
        this(targetId, selectedTexture, bakeProfile, expressionBinding, ModelResourcePurpose.FULL);
    }

    public RenderTargetKey(String targetId, String selectedTexture, BakeProfile bakeProfile) {
        this(targetId, selectedTexture, bakeProfile, ExpressionBinding.standalone());
    }

    public RenderTargetKey {
        targetId = Objects.requireNonNull(targetId, "targetId");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("Render target id cannot be blank");
        }
        selectedTexture = Objects.requireNonNullElse(selectedTexture, "");
        Objects.requireNonNull(bakeProfile, "bakeProfile");
        Objects.requireNonNull(expressionBinding, "expressionBinding");
        Objects.requireNonNull(purpose, "purpose");
    }

    public StateKey stateKey() {
        return new StateKey(targetId, bakeProfile, expressionBinding, purpose);
    }

    public record StateKey(String targetId, BakeProfile bakeProfile, ExpressionBinding expressionBinding,
        ModelResourcePurpose purpose) {
        public StateKey {
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(bakeProfile, "bakeProfile");
            Objects.requireNonNull(expressionBinding, "expressionBinding");
            Objects.requireNonNull(purpose, "purpose");
        }
    }
}
