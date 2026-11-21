package com.elfmcys.ysm.model.session.server.state;

import com.elfmcys.ysm.model.domain.ModelId;

import java.util.Objects;

public sealed interface Selection permits Selection.IntrinsicDefault, Selection.Model {
    record IntrinsicDefault() implements Selection {
    }

    record Model(ModelId modelId, String textureId) implements Selection {
        public Model {
            Objects.requireNonNull(modelId, "modelId");
            textureId = Objects.requireNonNullElse(textureId, "");
            if (textureId.length() > 256) {
                throw new IllegalArgumentException("Texture id is too long");
            }
        }

        public Model(ModelId modelId) {
            this(modelId, "");
        }
    }
}
