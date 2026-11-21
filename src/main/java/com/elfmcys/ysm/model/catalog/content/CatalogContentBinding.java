package com.elfmcys.ysm.model.catalog.content;

import com.elfmcys.ysm.model.domain.ModelId;

import java.util.Objects;

public record CatalogContentBinding(ModelId modelId, ModelContent content) implements ContentBinding {
    public CatalogContentBinding {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(content, "content");
        if (!modelId.equals(content.modelId())) {
            throw new IllegalArgumentException("Binding model id does not match its content");
        }
    }
}
