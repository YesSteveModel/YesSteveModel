package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import org.jetbrains.annotations.Nullable;

/** Controller-owned state exposed to one compiled invocation. */
public final class ControllerExecutionState {
    private final boolean hasSoundManager;
    private SoundInstanceManager soundManager;
    private boolean allAnimationsFinished;
    private boolean anyAnimationFinished;

    public ControllerExecutionState(boolean hasSoundManager) {
        this.hasSoundManager = hasSoundManager;
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
