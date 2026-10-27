package com.elfmcys.ysm.format.parser.pojo.animation;

import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.EventKeyFrame;
import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.InstructionKeyFrame;
import com.elfmcys.ysm.format.parser.pojo.animation.value.UnionValue;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@JsonAdapter(Animation.Adapter.class)
public class Animation {
    public String animationName = "";

    @SerializedName("animation_length")
    public float animationLength = -1;

    public LoopType loop = LoopType.PLAY_ONCE;

    @SerializedName("start_delay")
    public UnionValue startDelay;

    @SerializedName("loop_delay")
    public UnionValue loopDelay;

    @SerializedName("anim_time_update")
    public UnionValue animTimeUpdate;

    @SerializedName("blend_weight")
    public UnionValue blendWeight;

    @SerializedName("override_previous_animation")
    public Boolean overridePreviousAnimation;

    public List<BoneAnimation> boneAnimations = new ArrayList<>();
    public List<InstructionKeyFrame> customInstructionKeyframes = new ArrayList<>();
    public List<EventKeyFrame> soundKeyFrames = new ArrayList<>();

    public static final class Adapter implements JsonDeserializer<Animation> {
        @Override
        public Animation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            return deserializeAnimation(json, false);
        }

        static Animation deserializeAnimation(JsonElement json, boolean mergeInstKeyframeLines) {
            var animation = new Animation();
            if (json == null || json.isJsonNull() || !json.isJsonObject()) {
                return animation;
            }
            var object = json.getAsJsonObject();
            var animationLength = object.get("animation_length");
            if (animationLength != null && animationLength.isJsonPrimitive()
                && animationLength.getAsJsonPrimitive().isNumber()) {
                animation.animationLength = secondsToTicks(animationLength.getAsFloat());
            }
            animation.loop = LoopType.fromJson(object.get("loop"));
            animation.startDelay = readUnionValue(object, "start_delay");
            animation.loopDelay = readUnionValue(object, "loop_delay");
            animation.animTimeUpdate = readUnionValue(object, "anim_time_update");
            animation.blendWeight = readUnionValue(object, "blend_weight");
            animation.overridePreviousAnimation = readBoolean(object.get("override_previous_animation"));

            readSoundEffects(animation, object.get("sound_effects"));
            readTimeline(animation, object.get("timeline"), mergeInstKeyframeLines);
            readBones(animation, object.get("bones"));

            if (animation.animationLength == -1) {
                animation.animationLength = calculateLength(animation.boneAnimations);
            }
            return animation;
        }

        private static UnionValue readUnionValue(JsonObject object, String name) {
            var value = object.get(name);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
                return null;
            }
            var primitive = value.getAsJsonPrimitive();
            if (!primitive.isNumber() && !primitive.isString()) {
                return null;
            }
            return UnionValue.fromJson(value);
        }

        private static Boolean readBoolean(JsonElement json) {
            if (json == null || json.isJsonNull() || !json.isJsonPrimitive()
                || !json.getAsJsonPrimitive().isBoolean()) {
                return null;
            }
            return json.getAsBoolean();
        }

        private static void readBones(Animation animation, JsonElement json) {
            if (json == null || json.isJsonNull()) {
                return;
            }
            var bones = requireObject(json, "bones");
            for (Map.Entry<String, JsonElement> item : bones.entrySet()) {
                if (item.getValue() == null || item.getValue().isJsonNull() || !item.getValue().isJsonObject()) {
                    throw new JsonParseException("Invalid bone animation: " + item.getKey());
                }
                var boneAnimation = BoneAnimation.fromJson(item.getValue());
                boneAnimation.boneName = item.getKey();
                animation.boneAnimations.add(boneAnimation);
            }
        }

        private static void readTimeline(Animation animation, JsonElement json, boolean mergeInstKeyframeLines) {
            if (json == null || json.isJsonNull()) {
                return;
            }
            var timeline = requireObject(json, "timeline");
            for (Map.Entry<String, JsonElement> item : timeline.entrySet()) {
                var keyFrame = new InstructionKeyFrame();
                keyFrame.startTick = secondsToTicks(Float.parseFloat(item.getKey()));
                var value = item.getValue();
                if (value != null && value.isJsonArray()) {
                    if (mergeInstKeyframeLines) {
                        var builder = new StringBuilder();
                        for (JsonElement element : value.getAsJsonArray()) {
                            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                                || !element.getAsJsonPrimitive().isString()) {
                                continue;
                            }
                            builder.append(element.getAsString()).append('\n');
                        }
                        keyFrame.eventData.add(builder.toString());
                    } else {
                        for (JsonElement element : value.getAsJsonArray()) {
                            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                                || !element.getAsJsonPrimitive().isString()) {
                                continue;
                            }
                            keyFrame.eventData.add(element.getAsString());
                        }
                    }
                } else if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    keyFrame.eventData.add(value.getAsString());
                }
                animation.customInstructionKeyframes.add(keyFrame);
            }
            animation.customInstructionKeyframes.sort(Comparator.comparingDouble(frame -> frame.startTick));
        }

        private static void readSoundEffects(Animation animation, JsonElement json) {
            if (json == null || json.isJsonNull()) {
                return;
            }
            var soundEffects = requireObject(json, "sound_effects");
            for (Map.Entry<String, JsonElement> item : soundEffects.entrySet()) {
                var startTick = secondsToTicks(Float.parseFloat(item.getKey()));
                var value = item.getValue();
                if (value != null && value.isJsonArray()) {
                    for (JsonElement element : value.getAsJsonArray()) {
                        addSoundEffect(animation, startTick, element);
                    }
                } else {
                    addSoundEffect(animation, startTick, value);
                }
            }
            animation.soundKeyFrames.sort(Comparator.comparingDouble(frame -> frame.startTick));
        }

        private static void addSoundEffect(Animation animation, float startTick, JsonElement json) {
            var object = requireObject(json, "sound effect");
            var keyFrame = new EventKeyFrame();
            keyFrame.startTick = startTick;
            keyFrame.eventData = readString(object.get("effect"));
            animation.soundKeyFrames.add(keyFrame);
        }

        private static JsonObject requireObject(JsonElement json, String name) {
            if (json == null || json.isJsonNull() || !json.isJsonObject()) {
                throw new JsonParseException("Json entry \"" + name + "\" is not an object");
            }
            return json.getAsJsonObject();
        }

        private static String readString(JsonElement json) {
            if (json == null || json.isJsonNull()) {
                return "";
            }
            return json.getAsString();
        }

        private static float calculateLength(List<BoneAnimation> boneAnimations) {
            float longestLength = 0;
            for (var animation : boneAnimations) {
                longestLength = Math.max(longestLength, animation.rotationKeyFrames.lastStartTick());
                longestLength = Math.max(longestLength, animation.positionKeyFrames.lastStartTick());
                longestLength = Math.max(longestLength, animation.scaleKeyFrames.lastStartTick());
            }
            return longestLength == 0 ? Float.MAX_VALUE : longestLength;
        }

        private static float secondsToTicks(float seconds) {
            return seconds * 20;
        }
    }
}
