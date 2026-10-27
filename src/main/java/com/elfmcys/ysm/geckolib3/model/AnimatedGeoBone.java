package com.elfmcys.ysm.geckolib3.model;

import com.elfmcys.ysm.geckolib3.core.processor.BoneView;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoBone;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public class AnimatedGeoBone implements BoneView {
    private static final int ROTATION_OFFSET = 0;
    private static final int POSITION_OFFSET = 3;
    private static final int SCALE_OFFSET = 6;
    private static final int CUBES_HIDDEN_OFFSET = 9;
    private static final int CHILDREN_HIDDEN_OFFSET = 10;
    private static final int LOCATOR_SEQUENCE_OFFSET = 11;
    private static final int COLOR_OFFSET = 12;
    private static final int TRANSPARENCY_GLOW_OFFSET = 13;

    private GeoBone bone;
    private final float[] attributes;
    private final int baseOffset;

    public AnimatedGeoBone(GeoBone bone, float[] attributes, int boneIndex) {
        this.bone = bone;
        this.attributes = attributes;
        this.baseOffset = boneIndex * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;

        initializeAttributes(bone, attributes, boneIndex);
    }

    public static void initializeAttributes(GeoBone bone, float[] attributes, int boneIndex) {
        int baseOffset = boneIndex * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
        var rotation = bone.rotation();
        attributes[baseOffset + ROTATION_OFFSET] = rotation.x;
        attributes[baseOffset + ROTATION_OFFSET + 1] = rotation.y;
        attributes[baseOffset + ROTATION_OFFSET + 2] = rotation.z;
        attributes[baseOffset + POSITION_OFFSET] = 0;
        attributes[baseOffset + POSITION_OFFSET + 1] = 0;
        attributes[baseOffset + POSITION_OFFSET + 2] = 0;
        attributes[baseOffset + SCALE_OFFSET] = 1;
        attributes[baseOffset + SCALE_OFFSET + 1] = 1;
        attributes[baseOffset + SCALE_OFFSET + 2] = 1;
        attributes[baseOffset + CUBES_HIDDEN_OFFSET] = 0;
        attributes[baseOffset + CHILDREN_HIDDEN_OFFSET] = 0;
        var locator = bone.locatorType();
        attributes[baseOffset + LOCATOR_SEQUENCE_OFFSET] = locator == null ? 0 : Byte.toUnsignedInt(locator.seq());
        attributes[baseOffset + COLOR_OFFSET] = 0xFFFFFF;
        attributes[baseOffset + TRANSPARENCY_GLOW_OFFSET] = bone.glow() ? 0x0FFF : 0xFFFF;
    }

    public GeoBone getBoneData() {
        return bone;
    }

    void bindBoneData(GeoBone bone) {
        this.bone = bone;
    }

    public Vector3f getInitialRotation() {
        return bone.rotation();
    }

    public Vector3f getPivot() {
        return bone.pivot();
    }

    @Override
    public String getName() {
        return bone.name();
    }

    @Override
    public int getPooledName() {
        return bone.pooledName();
    }

    @Override
    public float getPositionX() {
        return attributes[baseOffset + POSITION_OFFSET];
    }

    @Override
    public float getPositionY() {
        return attributes[baseOffset + POSITION_OFFSET + 1];
    }

    @Override
    public float getPositionZ() {
        return attributes[baseOffset + POSITION_OFFSET + 2];
    }

    public void setPosition(float x, float y, float z) {
        attributes[baseOffset + POSITION_OFFSET] = x;
        attributes[baseOffset + POSITION_OFFSET + 1] = y;
        attributes[baseOffset + POSITION_OFFSET + 2] = z;
    }

    public void setPosition(Vector3fc value) {
        setPosition(value.x(), value.y(), value.z());
    }

    @Override
    public float getScaleX() {
        return attributes[baseOffset + SCALE_OFFSET];
    }

    @Override
    public float getScaleY() {
        return attributes[baseOffset + SCALE_OFFSET + 1];
    }

    @Override
    public float getScaleZ() {
        return attributes[baseOffset + SCALE_OFFSET + 2];
    }

    public void setScale(float x, float y, float z) {
        attributes[baseOffset + SCALE_OFFSET] = x;
        attributes[baseOffset + SCALE_OFFSET + 1] = y;
        attributes[baseOffset + SCALE_OFFSET + 2] = z;
    }

    public void setScale(Vector3fc value) {
        setScale(value.x(), value.y(), value.z());
    }

    @Override
    public float getRotationX() {
        return attributes[baseOffset + ROTATION_OFFSET];
    }

    @Override
    public float getRotationY() {
        return attributes[baseOffset + ROTATION_OFFSET + 1];
    }

    @Override
    public float getRotationZ() {
        return attributes[baseOffset + ROTATION_OFFSET + 2];
    }

    public void setRotation(float x, float y, float z) {
        attributes[baseOffset + ROTATION_OFFSET] = x;
        attributes[baseOffset + ROTATION_OFFSET + 1] = y;
        attributes[baseOffset + ROTATION_OFFSET + 2] = z;
    }

    public void setRotationX(float x) {
        attributes[baseOffset + ROTATION_OFFSET] = x;
    }

    public void setRotationY(float y) {
        attributes[baseOffset + ROTATION_OFFSET + 1] = y;
    }

    public void setRotationZ(float z) {
        attributes[baseOffset + ROTATION_OFFSET + 2] = z;
    }

    public void setRotation(Vector3fc value) {
        setRotation(value.x(), value.y(), value.z());
    }

    @Override
    public float getInitialRotationX() {
        return bone.rotation().x;
    }

    @Override
    public float getInitialRotationY() {
        return bone.rotation().y;
    }

    @Override
    public float getInitialRotationZ() {
        return bone.rotation().z;
    }

    @Override
    public float getPivotX() {
        return bone.pivot().x;
    }

    @Override
    public float getPivotY() {
        return bone.pivot().y;
    }

    @Override
    public float getPivotZ() {
        return bone.pivot().z;
    }

    @Override
    public boolean areCubesHidden() {
        return attributes[baseOffset + CUBES_HIDDEN_OFFSET] != 0;
    }

    @Override
    public boolean areChildrenHidden() {
        return attributes[baseOffset + CHILDREN_HIDDEN_OFFSET] != 0;
    }

    public void setHidden(boolean hidden) {
        setHidden(hidden, hidden);
    }

    public void setHidden(boolean cubesHidden, boolean childrenHidden) {
        attributes[baseOffset + CUBES_HIDDEN_OFFSET] = cubesHidden ? 1 : 0;
        attributes[baseOffset + CHILDREN_HIDDEN_OFFSET] = childrenHidden ? 1 : 0;
    }

    public void setColor(int red, int green, int blue) {
        attributes[baseOffset + COLOR_OFFSET] = red | (green << 8) | (blue << 16);
    }

    public void setTransparency(int alpha) {
        var packed = (int)attributes[baseOffset + TRANSPARENCY_GLOW_OFFSET];
        attributes[baseOffset + TRANSPARENCY_GLOW_OFFSET] = (packed & 0xFF00) | alpha;
    }

    public void setGlow(int level) {
        var packed = (int)attributes[baseOffset + TRANSPARENCY_GLOW_OFFSET];
        var glow = level == -1 ? 0xFF : level;
        attributes[baseOffset + TRANSPARENCY_GLOW_OFFSET] = (packed & 0xFF) | (glow << 8);
    }
}
