package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.SubjectSampleView;
import org.jetbrains.annotations.Nullable;

/** Pure values shared by private root inputs and the entity's completed world publication. */
public final class SubjectSample implements SubjectSampleView {
    public static final SubjectSample MISSING = new SubjectSample(new Draft());

    private final float positionX;
    private final float positionY;
    private final float positionZ;
    private final float deltaX;
    private final float deltaY;
    private final float deltaZ;
    private final float sampleSpan;
    private final float trackedGroundSpeed;
    private final float verticalSpeed;
    private final float inputHorizontal;
    private final float inputVertical;
    private final float bodyYaw;
    private final float bodyPitch;
    private final float eyePitch;
    private final float headYaw;
    private final float headPitch;
    private final float eyeYaw;
    private final float yawSpeed;
    private final float melodyDelta;
    private final float melodyPitch;
    private final float melodyVolume;
    private final float melodyCurrent;
    private final float melodyTime;
    private final @Nullable String selectedControl;
    private final @Nullable Boolean closeEyes;

    private SubjectSample(Draft draft) {
        positionX = draft.positionX;
        positionY = draft.positionY;
        positionZ = draft.positionZ;
        deltaX = draft.deltaX;
        deltaY = draft.deltaY;
        deltaZ = draft.deltaZ;
        sampleSpan = draft.sampleSpan;
        trackedGroundSpeed = draft.trackedGroundSpeed;
        verticalSpeed = draft.verticalSpeed;
        inputHorizontal = draft.inputHorizontal;
        inputVertical = draft.inputVertical;
        bodyYaw = draft.bodyYaw;
        bodyPitch = draft.bodyPitch;
        eyePitch = draft.eyePitch;
        headYaw = draft.headYaw;
        headPitch = draft.headPitch;
        eyeYaw = draft.eyeYaw;
        yawSpeed = draft.yawSpeed;
        melodyDelta = draft.melodyDelta;
        melodyPitch = draft.melodyPitch;
        melodyVolume = draft.melodyVolume;
        melodyCurrent = draft.melodyCurrent;
        melodyTime = draft.melodyTime;
        selectedControl = draft.selectedControl;
        closeEyes = draft.closeEyes;
    }

    public float position(int axis) {
        return switch (axis) {
            case 0 -> positionX;
            case 1 -> positionY;
            case 2 -> positionZ;
            default -> Float.NaN;
        };
    }

    public float positionDelta(int axis) {
        return switch (axis) {
            case 0 -> deltaX;
            case 1 -> deltaY;
            case 2 -> deltaZ;
            default -> Float.NaN;
        };
    }

    public float sampleSpan() {
        return sampleSpan;
    }

    public float trackedGroundSpeed() {
        return trackedGroundSpeed;
    }

    public float verticalSpeed() {
        return verticalSpeed;
    }

    public float inputHorizontal() {
        return inputHorizontal;
    }

    public float inputVertical() {
        return inputVertical;
    }

    public float bodyPitch() {
        return bodyPitch;
    }

    public float eyePitch() {
        return eyePitch;
    }

    public float bodyYaw() {
        return bodyYaw;
    }

    public float headYaw() {
        return headYaw;
    }

    public float headPitch() {
        return headPitch;
    }

    public float eyeYaw() {
        return eyeYaw;
    }

    public float yawSpeed() {
        return yawSpeed;
    }

    public float melodyDelta() {
        return melodyDelta;
    }

    public float melodyPitch() {
        return melodyPitch;
    }

    public float melodyVolume() {
        return melodyVolume;
    }

    public float melodyCurrent() {
        return melodyCurrent;
    }

    public float melodyTime() {
        return melodyTime;
    }

    public @Nullable Boolean closeEyes() {
        return closeEyes;
    }

    public @Nullable String selectedControl() {
        return selectedControl;
    }

    /** Owner-private write target, borrowed by exact producers during their existing input hooks. */
    public static final class Draft {
        private float positionX = Float.NaN;
        private float positionY = Float.NaN;
        private float positionZ = Float.NaN;
        private float deltaX = Float.NaN;
        private float deltaY = Float.NaN;
        private float deltaZ = Float.NaN;
        private float sampleSpan = Float.NaN;
        private float trackedGroundSpeed = Float.NaN;
        private float verticalSpeed = Float.NaN;
        private float inputHorizontal = Float.NaN;
        private float inputVertical = Float.NaN;
        private float bodyYaw = Float.NaN;
        private float bodyPitch = Float.NaN;
        private float eyePitch = Float.NaN;
        private float headYaw = Float.NaN;
        private float headPitch = Float.NaN;
        private float eyeYaw = Float.NaN;
        private float yawSpeed = Float.NaN;
        private float melodyDelta = Float.NaN;
        private float melodyPitch = Float.NaN;
        private float melodyVolume = Float.NaN;
        private float melodyCurrent = Float.NaN;
        private float melodyTime = Float.NaN;
        private @Nullable String selectedControl;
        private @Nullable Boolean closeEyes;

        public void position(float x, float y, float z, float dx, float dy, float dz, float span, float groundSpeed,
            float vertical, float horizontalInput, float verticalInput) {
            positionX = x;
            positionY = y;
            positionZ = z;
            deltaX = dx;
            deltaY = dy;
            deltaZ = dz;
            sampleSpan = span;
            trackedGroundSpeed = groundSpeed;
            verticalSpeed = vertical;
            inputHorizontal = horizontalInput;
            inputVertical = verticalInput;
        }

        public void orientation(float body, float head, float pitch, float eye, float yaw) {
            bodyYaw = body;
            headYaw = head;
            headPitch = pitch;
            eyeYaw = eye;
            yawSpeed = yaw;
        }

        public void pitch(float body, float eye) {
            bodyPitch = body;
            eyePitch = eye;
        }

        public void closeEyes(boolean value) {
            closeEyes = value;
        }

        public void control(@Nullable String selected) {
            selectedControl = selected;
        }

        public void melody(float delta, float pitch, float volume, float current, float time) {
            melodyDelta = delta;
            melodyPitch = pitch;
            melodyVolume = volume;
            melodyCurrent = current;
            melodyTime = time;
        }

        public SubjectSample freeze() {
            return new SubjectSample(this);
        }

        public void clear() {
            positionX = Float.NaN;
            positionY = Float.NaN;
            positionZ = Float.NaN;
            deltaX = Float.NaN;
            deltaY = Float.NaN;
            deltaZ = Float.NaN;
            sampleSpan = Float.NaN;
            trackedGroundSpeed = Float.NaN;
            verticalSpeed = Float.NaN;
            inputHorizontal = Float.NaN;
            inputVertical = Float.NaN;
            bodyYaw = Float.NaN;
            bodyPitch = Float.NaN;
            eyePitch = Float.NaN;
            headYaw = Float.NaN;
            headPitch = Float.NaN;
            eyeYaw = Float.NaN;
            yawSpeed = Float.NaN;
            melodyDelta = Float.NaN;
            melodyPitch = Float.NaN;
            melodyVolume = Float.NaN;
            melodyCurrent = Float.NaN;
            melodyTime = Float.NaN;
            selectedControl = null;
            closeEyes = null;
        }
    }
}
