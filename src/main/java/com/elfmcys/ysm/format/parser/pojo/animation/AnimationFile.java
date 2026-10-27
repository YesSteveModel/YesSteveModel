package com.elfmcys.ysm.format.parser.pojo.animation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonAdapter(AnimationFile.Adapter.class)
public class AnimationFile {
    public List<Animation> animations = new ArrayList<>();

    public static Gson createGson(boolean mergeInstKeyframeLines) {
        return new GsonBuilder().registerTypeAdapter(AnimationFile.class, new Adapter(mergeInstKeyframeLines)).create();
    }

    public static final class Adapter implements JsonDeserializer<AnimationFile> {
        private final boolean mergeInstKeyframeLines;

        public Adapter() {
            this(false);
        }

        public Adapter(boolean mergeInstKeyframeLines) {
            this.mergeInstKeyframeLines = mergeInstKeyframeLines;
        }

        @Override
        public AnimationFile deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            var file = new AnimationFile();
            if (json == null || json.isJsonNull() || !json.isJsonObject()) {
                return file;
            }
            var animations = json.getAsJsonObject().get("animations");
            if (animations == null || animations.isJsonNull() || !animations.isJsonObject()) {
                return file;
            }
            for (Map.Entry<String, JsonElement> item : animations.getAsJsonObject().entrySet()) {
                if (item.getValue() == null || item.getValue().isJsonNull() || !item.getValue().isJsonObject()) {
                    continue;
                }
                var animation = Animation.Adapter.deserializeAnimation(item.getValue(), mergeInstKeyframeLines);
                animation.animationName = item.getKey();
                file.animations.add(animation);
            }
            return file;
        }
    }
}
