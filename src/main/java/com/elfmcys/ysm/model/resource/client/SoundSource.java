package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.PublishedModelId;

import java.util.Objects;

/** Qualified locator for one sound in an exact ready render target. */
public final class SoundSource {
    private final ModelFileIdentity representation;
    private final PublishedModelId modelId;
    private final SoundMedia media;
    private final TargetSoundResources targetResources;

    SoundSource(ModelFileIdentity representation, PublishedModelId modelId, SoundMedia media,
        TargetSoundResources targetResources) {
        this.representation = Objects.requireNonNull(representation, "representation");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.media = Objects.requireNonNull(media, "media");
        this.targetResources = Objects.requireNonNull(targetResources, "targetResources");
    }

    public ModelFileIdentity representation() {
        return representation;
    }

    public PublishedModelId modelId() {
        return modelId;
    }

    public SoundMedia media() {
        return media;
    }

    public SharedBuffer acquireFirstChunk() throws AssetLoadException {
        return targetResources.acquireFirstChunk(representation, media);
    }
}
