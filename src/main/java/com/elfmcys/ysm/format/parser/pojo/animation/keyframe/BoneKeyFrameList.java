package com.elfmcys.ysm.format.parser.pojo.animation.keyframe;

import com.elfmcys.ysm.format.parser.pojo.animation.value.Vector3v;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.EasingType;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@JsonAdapter(BoneKeyFrameList.Adapter.class)
public class BoneKeyFrameList {
    public List<BoneKeyFrame> keyFrames = new ArrayList<>();

    public float lastStartTick() {
        if (keyFrames.isEmpty()) {
            return 0;
        }
        return keyFrames.get(keyFrames.size() - 1).startTick;
    }

    public static BoneKeyFrameList fromJson(JsonElement json) {
        var list = new BoneKeyFrameList();
        if (json == null || json.isJsonNull()) {
            return list;
        }
        if (json.isJsonArray() || json.isJsonPrimitive()) {
            var keyFrame = new BoneKeyFrame();
            keyFrame.startTick = 0;
            keyFrame.preValue = Vector3v.fromJson(json);
            list.keyFrames.add(keyFrame);
            return list;
        }
        if (!json.isJsonObject()) {
            throw new JsonParseException("Invalid key frame list: " + json);
        }
        for (Map.Entry<String, JsonElement> item : json.getAsJsonObject().entrySet()) {
            var keyFrame = new BoneKeyFrame();
            keyFrame.startTick = secondsToTicks(Float.parseFloat(item.getKey()));
            readKeyFrameValue(keyFrame, item.getValue());
            list.keyFrames.add(keyFrame);
        }
        list.keyFrames.sort(Comparator.comparingDouble(frame -> frame.startTick));
        return list;
    }

    private static void readKeyFrameValue(BoneKeyFrame keyFrame, JsonElement json) {
        if (json == null || json.isJsonNull()) {
            throw new JsonParseException("Invalid key frame value: null");
        }
        if (json.isJsonArray() || json.isJsonPrimitive()) {
            keyFrame.preValue = Vector3v.fromJson(json);
            return;
        }
        if (!json.isJsonObject()) {
            throw new JsonParseException("Invalid key frame value: " + json);
        }
        var object = json.getAsJsonObject();
        var vector = object.get("vector");
        if (vector != null && !vector.isJsonNull()) {
            keyFrame.preValue = Vector3v.fromJson(vector);
            keyFrame.easingType = EasingType.fromJson(object.get("easing"));
            return;
        }
        var preValue = object.get("pre");
        var postValue = object.get("post");
        var hasPre = preValue != null && !preValue.isJsonNull();
        var hasPost = postValue != null && !postValue.isJsonNull();
        if (!hasPre && !hasPost) {
            throw new JsonParseException("Key frame must contain pre or post: " + json);
        }
        if (hasPre) {
            keyFrame.preValue = Vector3v.fromJson(preValue);
        } else {
            keyFrame.preValue = Vector3v.fromJson(postValue);
        }
        if (hasPre && hasPost) {
            keyFrame.postValue = Vector3v.fromJson(postValue);
        }
        keyFrame.easingType = EasingType.fromJson(object.get("lerp_mode"));
    }

    private static float secondsToTicks(float seconds) {
        return seconds * 20;
    }

    public static final class Adapter implements JsonDeserializer<BoneKeyFrameList> {
        @Override
        public BoneKeyFrameList deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            return fromJson(json);
        }
    }
}
