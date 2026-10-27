package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;

/** Entity-owned animation state exposed to one compiled invocation. */
public final class AnimationExecutionState {
    private SoundInstanceManager soundManager;
    private float animTime;

    public void setAnimTime(float animTime) {
        this.animTime = animTime;
    }

    public float animTime() {
        return animTime;
    }

    public SoundInstanceManager soundManager() {
        if (soundManager == null) {
            soundManager = new SoundInstanceManager();
        }
        return soundManager;
    }
}
