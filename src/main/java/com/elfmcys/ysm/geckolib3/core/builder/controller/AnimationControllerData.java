package com.elfmcys.ysm.geckolib3.core.builder.controller;

import com.elfmcys.ysm.util.StringPool;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMaps;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;

import java.util.Arrays;
import java.util.stream.Collectors;

public class AnimationControllerData {
    private final int initialState;
    private final Int2ReferenceMap<AnimationControllerState> states;

    public AnimationControllerData(String initialState, AnimationControllerState[] states) {
        this.initialState = StringPool.computeIfAbsent(initialState);
        this.states = Int2ReferenceMaps.unmodifiable(new Int2ReferenceOpenHashMap<>(
            Arrays.stream(states).collect(Collectors.toMap(AnimationControllerState::pooledName, state -> state))));
    }

    public int initialState() {
        return initialState;
    }

    public Int2ReferenceMap<AnimationControllerState> states() {
        return states;
    }
}
