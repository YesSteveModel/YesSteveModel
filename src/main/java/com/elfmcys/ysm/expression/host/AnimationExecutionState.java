package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;

/** Entity-owned animation state exposed to one compiled invocation. */
public final class AnimationExecutionState {
    private final com.elfmcys.ysm.api.molang.MolangInputs.Target target = new Target(this);

    private record Target(AnimationExecutionState state) implements com.elfmcys.ysm.api.molang.MolangInputs.Target {}

    public com.elfmcys.ysm.api.molang.MolangInputs.Animation input() {
        return new com.elfmcys.ysm.api.molang.MolangInputs.Animation(target, animTime);
    }

    public static AnimationExecutionState resolve(com.elfmcys.ysm.api.molang.MolangInputs.Animation input) {
        return input == null ? null : ((Target)input.target()).state();
    }

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
