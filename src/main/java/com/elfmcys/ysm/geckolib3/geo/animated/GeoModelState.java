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

/**
 * Owns a native frame state and publishes its matching Java model and locator map.
 *
 * <p>Execution: the entity owner serializes extraction, locator consumption, rendering, and close.
 * Locator callbacks run inline and must not replace, extract, or close the state during the visit.
 *
 * <p>Ownership: the owner retains the installed model's resource lifetime through consumption.
 * This class keeps a model reference but does not acquire a resource lease for a borrowed result.
 *
 * <p>Seam: implements Java publication in the
 * [frame state contract](docs/architecture/rendering/overview.md#帧状态发布与借用).
 */
public final class GeoModelState implements Closeable {
    private final NativeModelState nativeState = NativeModelState.create();
    private final ReferenceArrayList<ShortArrayList> activeLocatorMap = new ReferenceArrayList<>(0);

    private GeoModel model;

    /**
     * <p>Requires: animatedModel's binding and complete attribute array stay unchanged during the call.
     * The owner has ended consumption of the previous result.
     *
     * <p>Failure: false or a Java publication exception leaves no consumable result.
     * The previous result is revoked before extraction starts; it is not a fallback frame.
     *
     * @return true after native views and the matching locator map are fully published, including empty geometry
     */
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

    /**
     * @return the embedded state for the owner's current consumption window; no independent close
     *     or publication authority transfers to the caller
     */
    public NativeModelState getNativeState() {
        return nativeState;
    }

    /**
     * Uses the locator visit contract, or calls applyDefaultPose then visitor once for an empty group.
     * The default branch restores the pushed frame in finally; both callbacks must balance their own stack changes.
     */
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

    /**
     * <p>Requires: this state is valid and locator belongs to its model's locator type.
     * The callback preserves stack depth and consumes each temporary locator pose before returning.
     *
     * <p>Failure: callback exceptions propagate unchanged. The original top pose and normal are restored in finally.
     *
     * <p>Execution: invokes visitor synchronously once per active locator, in the published bone order.
     */
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

    /**
     * @return the active group size in this valid publication; locator must belong to the current model's type
     */
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

    /** Revokes Java consumption and closes the native owner after all outstanding consumers have returned. */
    @Override
    public void close() {
        invalidate();
        nativeState.close();
    }
}
