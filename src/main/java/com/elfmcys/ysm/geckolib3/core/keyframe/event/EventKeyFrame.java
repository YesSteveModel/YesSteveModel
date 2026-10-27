/*
 * Copyright (c) 2020. Author: Bernie G. (Gecko)
 */

package com.elfmcys.ysm.geckolib3.core.keyframe.event;

public class EventKeyFrame<T> {
    private final T eventData;
    private final float startTick;

    public EventKeyFrame(double startTick, T eventData) {
        this.startTick = (float)startTick;
        this.eventData = eventData;
    }

    public T getEventData() {
        return eventData;
    }

    public float getStartTick() {
        return startTick;
    }
}
