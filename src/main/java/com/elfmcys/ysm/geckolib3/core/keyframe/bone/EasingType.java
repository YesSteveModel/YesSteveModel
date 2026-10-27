package com.elfmcys.ysm.geckolib3.core.keyframe.bone;

import com.google.gson.JsonElement;

import java.util.List;

public interface EasingType {
    EasingType LINEAR = EasingType::buildLinearKeyFrame;
    EasingType CATMULLROM = EasingType::buildCatmullRomKeyFrame;

    static EasingType fromJson(JsonElement json) {
        if (json == null || json.isJsonNull() || !json.isJsonPrimitive()) {
            return LINEAR;
        }
        var primitive = json.getAsJsonPrimitive();
        if (!primitive.isString()) {
            return LINEAR;
        }
        var value = primitive.getAsString();
        if ("linear".equalsIgnoreCase(value)) {
            return LINEAR;
        }
        if ("catmullrom".equalsIgnoreCase(value)) {
            return CATMULLROM;
        }
        return LINEAR;
    }

    BoneKeyFrame buildKeyFrame(List<RawBoneKeyFrame> keyFrames, int index);

    static TransitionKeyFrame buildTransitionKeyFrame(List<RawBoneKeyFrame> keyFrames) {
        RawBoneKeyFrame transitionDst = keyFrames.get(0);
        return new TransitionKeyFrame(transitionDst.startTick(), transitionDst.preValue(), transitionDst.postValue());
    }

    static BoneKeyFrame buildLinearKeyFrame(List<RawBoneKeyFrame> keyFrames, int index) {
        if (index == 0) {
            return buildTransitionKeyFrame(keyFrames);
        }
        RawBoneKeyFrame begin = keyFrames.get(index - 1);
        RawBoneKeyFrame end = keyFrames.get(index);
        return new LinearKeyFrame(begin.startTick(), end.startTick() - begin.startTick(), begin.postValue(),
            end.preValue(), end.postValue());
    }

    static BoneKeyFrame buildCatmullRomKeyFrame(List<RawBoneKeyFrame> keyFrames, int index) {
        if (index == 0) {
            return buildTransitionKeyFrame(keyFrames);
        }
        RawBoneKeyFrame left = keyFrames.get(Math.max(0, index - 2));
        RawBoneKeyFrame begin = keyFrames.get(index - 1);
        RawBoneKeyFrame end = keyFrames.get(index);
        RawBoneKeyFrame right = keyFrames.get(Math.min(keyFrames.size() - 1, index + 1));

        return new CatmullRomKeyFrame(begin.startTick(), end.startTick() - begin.startTick(), left.postValue(),
            begin.postValue(), end.preValue(), right.preValue(), end.postValue());
    }
}
