package com.elfmcys.ysm.format.parser.pojo.controller;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;

import java.lang.reflect.Type;
import java.util.Map;

@JsonAdapter(AnimationEntry.Adapter.class)
public class AnimationEntry {
    public String name = "";
    public String condition = "";

    public static final class Adapter implements JsonDeserializer<AnimationEntry> {
        @Override
        public AnimationEntry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            var entry = new AnimationEntry();
            if (json == null || json.isJsonNull()) {
                return entry;
            }
            if (json.isJsonObject()) {
                for (Map.Entry<String, JsonElement> item : json.getAsJsonObject().entrySet()) {
                    entry.name = item.getKey();
                    entry.condition = item.getValue().getAsString();
                    break;
                }
            } else {
                entry.name = json.getAsString();
            }
            return entry;
        }
    }
}
