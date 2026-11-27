package com.elfmcys.ysm.format.parser.pojo.manifest.models;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class PlayerModelFiles {
    public LinkedHashMap<String, String> model = new LinkedHashMap<>();
    public LinkedHashMap<String, String> animation = new LinkedHashMap<>();

    @JsonAdapter(PhysicsPath.class)
    public String physics;

    @SerializedName("animation_controllers")
    public List<String> animationControllers = new ArrayList<>();

    public List<PBRTextureSet> texture = new ArrayList<>();

    @Deprecated
    @SerializedName("sound_path")
    public String soundPath;

    private static final class PhysicsPath implements JsonDeserializer<String> {
        @Override
        public String deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("files.player.physics must be a path string or null");
            }
            return json.getAsString();
        }
    }
}
