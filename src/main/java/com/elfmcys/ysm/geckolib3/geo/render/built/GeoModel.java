package com.elfmcys.ysm.geckolib3.geo.render.built;

import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import it.unimi.dsi.fastutil.objects.ReferenceLists;
import java.util.Objects;
import java.util.function.IntFunction;

public class GeoModel implements Closeable {
    private final String identity;
    private final ReferenceList<GeoBone> sortedBones;
    private final GeoLocatorType locatorType;
    private final ReferenceList<ReferenceArrayList<GeoBone>> locatorMap;
    private NativeBakedModel bakedModel;
    private float[] guiBoneBounds;

    public GeoModel(String identity, GeoModelProto model, GeoLocatorType locatorType,
        NativeBakedModel.ModelResult bakedModel) {
        this(identity, boneCount(model), index -> model.bones().get(index), bakedModel.sortedBoneIndices(), locatorType,
            bakedModel.bakedModel());
    }

    private GeoModel(String identity, int boneCount, IntFunction<BoneProto> boneByIndex, short[] sortedBoneIndices,
        GeoLocatorType locatorType, NativeBakedModel bakedModel) {
        this.identity = identity;
        Objects.requireNonNull(sortedBoneIndices, "sortedBoneIndices");
        this.locatorType = Objects.requireNonNull(locatorType, "locatorType");
        this.bakedModel = Objects.requireNonNull(bakedModel, "bakedModel");
        if (sortedBoneIndices.length != boneCount) {
            throw new IllegalArgumentException("Bone index count mismatch");
        }

        var locatorMap = new ReferenceArrayList<ReferenceArrayList<GeoBone>>(locatorType.size());
        for (int i = 0; i < locatorType.size(); i++) {
            locatorMap.add(new ReferenceArrayList<>(2));
        }
        var sortedBones = new ReferenceArrayList<GeoBone>(boneCount);
        for (var sortedIndex = 0; sortedIndex < boneCount; sortedIndex++) {
            var originalIndex = Short.toUnsignedInt(sortedBoneIndices[sortedIndex]);
            if (originalIndex >= boneCount) {
                throw new IllegalArgumentException("Invalid sorted bone indices");
            }
            var boneData = boneByIndex.apply(originalIndex);
            var locator = locatorType.getByBoneName(boneData.name());
            var bone = new GeoBone(boneData, locator);
            sortedBones.add(bone);
            if (locator != null) {
                locatorMap.get(locator.seq() - 1).add(bone);
            }
        }
        this.sortedBones = ReferenceLists.unmodifiable(sortedBones);
        this.locatorMap = ReferenceLists.unmodifiable(locatorMap);
        ResourceDiagnostics.track(ResourceDiagnostics.Kind.GEO, this, identity + " bones=" + boneCount);
        bakedModel.share();
    }

    public String identity() {
        return identity;
    }

    private static int boneCount(GeoModelProto model) {
        return model.bones().size();
    }

    public ReferenceList<GeoBone> sortedBones() {
        return sortedBones;
    }

    public GeoLocatorType locatorType() {
        return locatorType;
    }

    public ReferenceList<ReferenceArrayList<GeoBone>> locatorMap() {
        return locatorMap;
    }

    public NativeBakedModel bakedModel() {
        return Objects.requireNonNull(bakedModel, "Geometry is closed");
    }

    /** Borrowed immutable geometry, populated only when a GUI actually draws this model. */
    public float[] guiBoneBounds() {
        if (guiBoneBounds == null) {
            guiBoneBounds = bakedModel.getBoneBounds(sortedBones.size());
        }
        return guiBoneBounds;
    }

    @Override
    public void close() {
        bakedModel = null;
        guiBoneBounds = null;
        ResourceDiagnostics.untrack(ResourceDiagnostics.Kind.GEO, this);
    }
}
