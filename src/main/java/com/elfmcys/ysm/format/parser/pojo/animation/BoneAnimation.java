package com.elfmcys.ysm.format.parser.pojo.animation;

import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.BoneKeyFrameList;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;

@JsonAdapter(BoneAnimation.Adapter.class)
public class BoneAnimation {
    public String boneName = "";

    @SerializedName("rotation")
    public BoneKeyFrameList rotationKeyFrames;

    @SerializedName("position")
    public BoneKeyFrameList positionKeyFrames;

    @SerializedName("scale")
    public BoneKeyFrameList scaleKeyFrames;

    public BoneAnimation() {
        rotationKeyFrames = new BoneKeyFrameList();
        positionKeyFrames = new BoneKeyFrameList();
        scaleKeyFrames = new BoneKeyFrameList();
    }

    private BoneAnimation(JsonObject object) {
        var name = object.get("boneName");
        if (name != null) {
            if (!name.isJsonNull() && !name.isJsonPrimitive()) {
                throw new JsonParseException("Invalid bone name: " + name);
            }
            boneName = name.isJsonNull() ? null : name.getAsString();
        }
        rotationKeyFrames = readFrames(object, "rotation");
        positionKeyFrames = readFrames(object, "position");
        scaleKeyFrames = readFrames(object, "scale");
    }

    public static BoneAnimation fromJson(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            throw new JsonParseException("Invalid bone animation: " + json);
        }
        return new BoneAnimation(json.getAsJsonObject());
    }

    private static BoneKeyFrameList readFrames(JsonObject object, String name) {
        var json = object.get(name);
        // An omitted channel has its default list; explicit null must retain its presence semantics.
        if (json == null)
            return new BoneKeyFrameList();
        return json.isJsonNull() ? null : BoneKeyFrameList.fromJson(json);
    }

    public static final class Adapter implements JsonDeserializer<BoneAnimation> {
        @Override
        public BoneAnimation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
            return fromJson(json);
        }
    }
}
