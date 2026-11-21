package com.elfmcys.ysm.api.molang;

import org.jetbrains.annotations.Nullable;

/** Immutable query values. Absent numeric inputs are NaN; absent classifications are null. */
public interface SubjectSampleView {
    float position(int axis);

    float positionDelta(int axis);

    float sampleSpan();

    float trackedGroundSpeed();

    float verticalSpeed();

    float inputHorizontal();

    float inputVertical();

    float bodyYaw();

    float bodyPitch();

    float eyePitch();

    float headYaw();

    float headPitch();

    float eyeYaw();

    float yawSpeed();

    float melodyDelta();

    float melodyPitch();

    float melodyVolume();

    float melodyCurrent();

    float melodyTime();

    @Nullable
    Boolean closeEyes();

    @Nullable
    String selectedControl();
}
