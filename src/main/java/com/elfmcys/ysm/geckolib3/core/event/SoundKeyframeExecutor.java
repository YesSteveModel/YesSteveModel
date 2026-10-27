/*
 * Copyright (c) 2020. Author: Bernie G. (Gecko)
 */

package com.elfmcys.ysm.geckolib3.core.event;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.expression.host.ModelSoundId;
import com.elfmcys.ysm.geckolib3.core.keyframe.event.EventKeyFrame;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import net.minecraft.util.StringUtil;

import java.util.List;

public class SoundKeyframeExecutor {
    private final List<EventKeyFrame<String>> list;
    private final SoundInstanceManager soundManager;
    private int nextIndex = 0;

    public SoundKeyframeExecutor(List<EventKeyFrame<String>> list, SoundInstanceManager soundManager) {
        this.list = list;
        this.soundManager = soundManager;
    }

    public void executeTo(AnimatableEntity<?> animatable, float currentTick, boolean allowEmitting) {
        while (!reachEnd()) {
            EventKeyFrame<String> keyFrame = list.get(nextIndex);
            if (keyFrame.getStartTick() > currentTick) {
                return;
            }
            nextIndex++;
            if (allowEmitting && !StringUtil.isNullOrEmpty(keyFrame.getEventData())) {
                soundManager.playSound(animatable, ModelSoundId.numeric(0), keyFrame.getEventData(), false, null);
            }
        }
    }

    public void reset() {
        nextIndex = 0;
        soundManager.stopAllPlayingSounds();
    }

    public void stopAll() {
        soundManager.stopAllPlayingSounds();
    }

    public boolean reachEnd() {
        return nextIndex >= list.size();
    }
}
