package com.elfmcys.ysm.geckolib3.core.builder.controller;

import com.elfmcys.ysm.geckolib3.core.controller.transition.IBlendTransition;
import com.elfmcys.ysm.util.StringPool;
import com.elfmcys.ysm.expression.aot.BooleanProgram;
import com.elfmcys.ysm.expression.aot.VoidProgram;
import it.unimi.dsi.fastutil.ints.IntReferenceImmutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import it.unimi.dsi.fastutil.objects.ReferenceLists;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

public class AnimationControllerState {
    private static final int BUILTIN_NAME = StringPool.computeIfAbsent("ysm-builtin");
    private static final String SUB_ENTRY_PREFIX = "ysm-entry-";

    private final String name;
    private final int pooledName;
    private final boolean builtin;
    @Nullable
    private final String subEntryName;
    private final List<Pair<String, @Nullable BooleanProgram>> animations;
    private final List<IntReferenceImmutablePair<BooleanProgram>> transitions;
    private final List<String> soundEffects;
    private final List<VoidProgram> onEntry;
    private final List<VoidProgram> onExit;
    private final IBlendTransition blendTransition;
    private final boolean blendViaShortestPath;

    @SuppressWarnings("unchecked")
    public AnimationControllerState(String name, Pair<String, BooleanProgram>[] animations,
        Pair<String, BooleanProgram>[] transitions, String[] soundEffects, VoidProgram[] onEntry, VoidProgram[] onExit,
        IBlendTransition blendTransition, boolean blendViaShortestPath) {
        this.name = name;
        this.pooledName = StringPool.computeIfAbsent(name);
        this.builtin = pooledName == BUILTIN_NAME;
        this.subEntryName = name.startsWith(SUB_ENTRY_PREFIX) ? name.substring(SUB_ENTRY_PREFIX.length()) : null;
        this.animations = ReferenceLists.unmodifiable(ReferenceArrayList.wrap(animations));
        this.transitions = ReferenceLists.unmodifiable((ReferenceList<
            IntReferenceImmutablePair<BooleanProgram>>)(Object)ReferenceArrayList.wrap(Arrays.stream(transitions)
                .map(p -> new IntReferenceImmutablePair<>(StringPool.computeIfAbsent(p.getKey()), p.getValue()))
                .toArray(IntReferenceImmutablePair[]::new)));
        this.soundEffects = ReferenceLists.unmodifiable(ReferenceArrayList.wrap(soundEffects));
        this.onEntry = ReferenceLists.unmodifiable(ReferenceArrayList.wrap(onEntry));
        this.onExit = ReferenceLists.unmodifiable(ReferenceArrayList.wrap(onExit));
        this.blendTransition = blendTransition;
        this.blendViaShortestPath = blendViaShortestPath;
    }

    public String name() {
        return name;
    }

    public int pooledName() {
        return pooledName;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    @Nullable
    public String subEntryName() {
        return subEntryName;
    }

    public List<Pair<String, @Nullable BooleanProgram>> animations() {
        return animations;
    }

    public List<IntReferenceImmutablePair<BooleanProgram>> transitions() {
        return transitions;
    }

    public List<String> soundEffects() {
        return soundEffects;
    }

    public List<VoidProgram> onEntry() {
        return onEntry;
    }

    public List<VoidProgram> onExit() {
        return onExit;
    }

    public IBlendTransition blendTransition() {
        return blendTransition;
    }

    public boolean blendViaShortestPath() {
        return blendViaShortestPath;
    }
}
