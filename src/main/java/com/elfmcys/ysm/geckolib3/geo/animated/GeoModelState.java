package com.elfmcys.ysm.geckolib3.geo.animated;

import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocator;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ExposedShortArrayList;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.function.Consumer;

public final class GeoModelState implements Closeable {
    private final NativeModelState nativeState = NativeModelState.create();
    private final ReferenceArrayList<ShortArrayList> activeLocatorMap = new ReferenceArrayList<>(0);

    private GeoModel model;

    public boolean extract(AnimatedGeoModel animatedModel) {
        invalidate();
        try {
            var model = animatedModel.getModel();
            var boneCount = model.sortedBones().size();
            var activeLocatorMap = this.activeLocatorMap;

            var valid = nativeState.extract(model.bakedModel(), animatedModel.getBoneAttributes());
            if (!valid) {
                return false;
            }

            var locatorSlotCount = model.locatorType().size();
            if (activeLocatorMap.size() != locatorSlotCount) {
                var oldSize = activeLocatorMap.size();
                activeLocatorMap.size(locatorSlotCount);
                for (var i = oldSize; i < locatorSlotCount; i++) {
                    activeLocatorMap.set(i, new ExposedShortArrayList(2));
                }
            }
            for (var list : activeLocatorMap) {
                list.clear();
            }

            var sortedBones = model.sortedBones();
            var activeLocators = nativeState.getLocatorBoneIndices();
            for (var i = 0; i < activeLocators.size(); i++) {
                var boneIndex = Short.toUnsignedInt(activeLocators.getShort(i));
                if (boneIndex >= boneCount) {
                    return false;
                }
                var locatorType = sortedBones.get(boneIndex).locatorType();
                if (locatorType != null) {
                    activeLocatorMap.get(Byte.toUnsignedInt(locatorType.seq()) - 1).add((short)boneIndex);
                }
            }

            this.model = model;
            return true;
        } finally {
            if (this.model == null) {
                nativeState.invalidate();
            }
        }
    }

    public boolean isValid() {
        return model != null && nativeState.isValid();
    }

    public int getVertexCount() {
        return nativeState.getTotalVertexCount();
    }

    public boolean hasTranslucentVertices() {
        return nativeState.getTranslucentVertexCount() != 0;
    }

    public NativeModelState getNativeState() {
        return nativeState;
    }

    public void visitLocatorGroupOrDefault(GeoLocator locator, PoseStack poseStack, Consumer<PoseStack> visitor,
        Consumer<PoseStack> applyDefaultPose) {
        if (locatorGroupSize(locator) > 0) {
            visitLocatorGroup(locator, poseStack, visitor);
        } else {
            poseStack.pushPose();
            try {
                applyDefaultPose.accept(poseStack);
                visitor.accept(poseStack);
            } finally {
                poseStack.popPose();
            }
        }
    }

    public void visitLocatorGroup(GeoLocator locator, PoseStack poseStack, Consumer<PoseStack> visitor) {
        if (model.locatorType() != locator.type()) {
            throw new IllegalArgumentException("locator type mismatch");
        }

        var backupPose = new Matrix4f(poseStack.last().pose());
        var backupNormal = new Matrix3f(poseStack.last().normal());
        var bonePose = new Matrix4f();
        var boneNormal = new Matrix3f();
        var bonePoseView = nativeState.getBonePoses();
        var last = poseStack.last();

        try {
            for (var boneIndexShort : activeLocatorMap.get(Byte.toUnsignedInt(locator.seq()) - 1)) {
                var boneIndex = Short.toUnsignedInt(boneIndexShort);
                var bone = model.sortedBones().get(boneIndex);

                bonePoseView.getPose(boneIndex, bonePose);
                bonePoseView.getNormal(boneIndex, boneNormal);
                backupPose.mulAffine(bonePose, last.pose());
                backupNormal.mul(boneNormal, last.normal());

                var pivot = bone.pivot();
                poseStack.translate(pivot.x / 16, pivot.y / 16, pivot.z / 16);

                visitor.accept(poseStack);
            }
        } finally {
            last.pose().set(backupPose);
            last.normal().set(backupNormal);
        }
    }

    public int locatorGroupSize(GeoLocator locator) {
        if (model.locatorType() != locator.type()) {
            throw new IllegalArgumentException("locator type mismatch");
        }
        return activeLocatorMap.get(Byte.toUnsignedInt(locator.seq()) - 1).size();
    }

    private void invalidate() {
        model = null;
    }

    public GeoModel getModel() {
        return model;
    }

    @Override
    public void close() {
        invalidate();
        nativeState.close();
    }
}
