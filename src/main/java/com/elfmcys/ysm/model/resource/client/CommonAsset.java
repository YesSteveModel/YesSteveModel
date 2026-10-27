package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.expression.aot.UserFunctionProgram;
import com.elfmcys.ysm.expression.aot.EventProgram;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CommonAsset implements AutoCloseable {
    private final Map<String, SoundSource> sounds;
    private final TargetSoundResources soundResources;
    private final Object2ReferenceOpenHashMap<String, UserFunctionProgram> userFunctions;
    private final Object2ReferenceOpenHashMap<String, List<EventProgram>> eventHandlers;
    private final boolean ownsSounds;

    public CommonAsset(Map<String, SoundSource> sounds, TargetSoundResources soundResources,
        Object2ReferenceOpenHashMap<String, UserFunctionProgram> userFunctions,
        Object2ReferenceOpenHashMap<String, List<EventProgram>> eventHandlers) {
        this(sounds, soundResources, userFunctions, eventHandlers, true);
    }

    public CommonAsset(Map<String, SoundSource> sounds, TargetSoundResources soundResources,
        Object2ReferenceOpenHashMap<String, UserFunctionProgram> userFunctions,
        Object2ReferenceOpenHashMap<String, List<EventProgram>> eventHandlers, boolean ownsSounds) {
        this.sounds = Map.copyOf(sounds);
        this.soundResources = Objects.requireNonNull(soundResources, "soundResources");
        this.userFunctions = Objects.requireNonNull(userFunctions, "userFunctions");
        this.eventHandlers = Objects.requireNonNull(eventHandlers, "eventHandlers");
        this.ownsSounds = ownsSounds;
    }

    public Map<String, SoundSource> sounds() {
        return sounds;
    }

    public Object2ReferenceOpenHashMap<String, UserFunctionProgram> userFunctions() {
        return userFunctions;
    }

    public Object2ReferenceOpenHashMap<String, List<EventProgram>> eventHandlers() {
        return eventHandlers;
    }

    @Override
    public void close() {
        if (ownsSounds)
            soundResources.close();
    }

}
