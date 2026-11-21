package com.elfmcys.ysm.model.domain;

import java.util.Objects;

/** Exact identity of one model file representation. */
public record ModelFileIdentity(ModelId modelId, Hash256 containerId) {
    public ModelFileIdentity {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(containerId, "containerId");
    }
}
