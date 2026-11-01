package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import org.jetbrains.annotations.Nullable;

/** Controller-owned state exposed to one compiled invocation. */
public final class ControllerExecutionState {
    private final com.elfmcys.ysm.api.molang.MolangInputs.Target target = new Target(this);

    private record Target(ControllerExecutionState state) implements com.elfmcys.ysm.api.molang.MolangInputs.Target {}

    public com.elfmcys.ysm.api.molang.MolangInputs.Controller input() {
        return new com.elfmcys.ysm.api.molang.MolangInputs.Controller(target, allAnimationsFinished, anyAnimationFinished);
    }

    public static ControllerExecutionState resolve(com.elfmcys.ysm.api.molang.MolangInputs.Controller input) {
        return input == null ? null : ((Target)input.target()).state();
    }

    private final boolean hasSoundManager;
    private final com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController<?> codedController;

    public com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController<?> codedController() {
        return codedController;
    }
    private SoundInstanceManager soundManager;
    private boolean allAnimationsFinished;
    private boolean anyAnimationFinished;

    public ControllerExecutionState(boolean hasSoundManager) {
        this(hasSoundManager, null);
    }

    public ControllerExecutionState(boolean hasSoundManager,
        com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController<?> codedController) {
        this.hasSoundManager = hasSoundManager;
        this.codedController = codedController;
    }

    public void setAllAnimationsFinished(boolean value) {
        allAnimationsFinished = value;
    }

    public void setAnyAnimationFinished(boolean value) {
        anyAnimationFinished = value;
    }

    public boolean isAllAnimationsFinished() {
        return allAnimationsFinished;
    }

    public boolean isAnyAnimationFinished() {
        return anyAnimationFinished;
    }

    public @Nullable SoundInstanceManager soundManager() {
        if (!hasSoundManager) {
            return null;
        }
        if (soundManager == null) {
            soundManager = new SoundInstanceManager();
        }
        return soundManager;
    }
}
