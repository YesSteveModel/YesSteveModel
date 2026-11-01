package com.elfmcys.ysm.api.molang;

import org.jetbrains.annotations.Nullable;

/** A snapshot of the current sampling position; target identities carry no control authority. */
public record MolangInputs(float partialTick, float renderTickDelta, @Nullable Controller controller,
    @Nullable Animation animation) {
    public interface Target {}

    public record Controller(Target target, boolean allAnimationsFinished, boolean anyAnimationFinished) {}

    public record Animation(Target target, float time) {}
}
