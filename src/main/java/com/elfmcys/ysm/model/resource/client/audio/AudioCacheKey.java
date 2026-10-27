package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.SoundSource;

import java.util.Objects;

/** Source channels remain part of exact identity; output format version describes the default mono PCM contract. */
record AudioCacheKey(ModelFileIdentity representation, Hash256 mediaIdentity, int channels, long frames, int preSkip,
    int outputGain, int outputFormatVersion) {
    AudioCacheKey {
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(mediaIdentity, "mediaIdentity");
    }

    static AudioCacheKey from(SoundSource source) {
        var media = source.media();
        return new AudioCacheKey(source.representation(), media.mediaIdentity(), media.channels(), media.frames(),
            media.preSkip(), media.outputGain(), 1);
    }
}
