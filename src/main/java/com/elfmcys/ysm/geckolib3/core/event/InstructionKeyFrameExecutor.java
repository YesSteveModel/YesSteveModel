package com.elfmcys.ysm.geckolib3.core.event;

import com.elfmcys.ysm.geckolib3.core.keyframe.event.EventKeyFrame;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.VoidProgram;

import java.util.List;

public class InstructionKeyFrameExecutor {
    private final List<EventKeyFrame<VoidProgram[]>> list;
    private int nextIndex = 0;

    public InstructionKeyFrameExecutor(List<EventKeyFrame<VoidProgram[]>> list) {
        this.list = list;
    }

    private void evalValues(EntityExpressionRuntime.Evaluation evaluation, VoidProgram[] values) {
        for (VoidProgram value : values) {
            evaluation.execute(value);
        }
    }

    public void executeTo(EntityExpressionRuntime.Evaluation evaluation, float currentTick) {
        while (!reachEnd()) {
            EventKeyFrame<VoidProgram[]> keyFrame = list.get(nextIndex);
            if (keyFrame.getStartTick() > currentTick) {
                break;
            }
            evalValues(evaluation, keyFrame.getEventData());
            nextIndex++;
        }
    }

    public void executeRemaining(EntityExpressionRuntime.Evaluation evaluation) {
        for (int i = nextIndex; i < list.size(); i++) {
            evalValues(evaluation, list.get(i).getEventData());
        }
        nextIndex = list.size();
    }

    public boolean reachEnd() {
        return nextIndex >= list.size();
    }

    public void reset() {
        nextIndex = 0;
    }
}
