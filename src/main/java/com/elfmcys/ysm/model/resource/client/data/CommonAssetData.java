package com.elfmcys.ysm.model.resource.client.data;

import com.elfmcys.ysm.expression.aot.UserFunctionProgram;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;

import java.util.Map;
import java.util.List;
import java.util.Objects;

public record CommonAssetData(Map<String, SoundSource> sounds, TargetSoundResources soundResources,
    Map<String, UserFunctionProgram> userFunctions, Map<String, List<EventProgram>> eventHandlers) {
    public CommonAssetData {
        Objects.requireNonNull(sounds, "sounds");
        Objects.requireNonNull(soundResources, "soundResources");
        Objects.requireNonNull(userFunctions, "userFunctions");
        Objects.requireNonNull(eventHandlers, "eventHandlers");
        sounds = Map.copyOf(sounds);
        userFunctions = Map.copyOf(userFunctions);
        eventHandlers = Map.copyOf(eventHandlers);
    }
}
