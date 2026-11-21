package com.elfmcys.ysm.model.resource.client.data;

import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;

import java.util.Map;
import java.util.Objects;

public record CommonAssetData(Map<String, SoundSource> sounds, TargetSoundResources soundResources) {
    public CommonAssetData {
        Objects.requireNonNull(sounds, "sounds");
        Objects.requireNonNull(soundResources, "soundResources");
        sounds = Map.copyOf(sounds);
    }
}
