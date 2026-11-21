package com.elfmcys.ysm.model.resource.client;

import java.util.Map;
import java.util.Objects;

public class CommonAsset implements AutoCloseable {
    private final Map<String, SoundSource> sounds;
    private final TargetSoundResources soundResources;
    private final boolean ownsSounds;

    public CommonAsset(Map<String, SoundSource> sounds, TargetSoundResources soundResources) {
        this(sounds, soundResources, true);
    }

    public CommonAsset(Map<String, SoundSource> sounds, TargetSoundResources soundResources, boolean ownsSounds) {
        this.sounds = Map.copyOf(sounds);
        this.soundResources = Objects.requireNonNull(soundResources, "soundResources");
        this.ownsSounds = ownsSounds;
    }

    public Map<String, SoundSource> sounds() {
        return sounds;
    }

    public TargetSoundResources soundResources() {
        return soundResources;
    }

    @Override
    public void close() {
        if (ownsSounds)
            soundResources.close();
    }

}
