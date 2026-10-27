package com.elfmcys.ysm.format.parser.pojo.controller;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonAdapter(BlendTransition.Adapter.class)
public class BlendTransition {
    public Float linearLength;
    public LinkedHashMap<Float, Float> points = new LinkedHashMap<>();

    public void setLinear(float length) {
        linearLength = length;
        points.clear();
    }

    public static final class Adapter implements JsonDeserializer<BlendTransition> {
        @Override
        public BlendTransition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            var transition = new BlendTransition();
            if (json == null || json.isJsonNull()) {
                return transition;
            }
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
                transition.setLinear(json.getAsFloat());
            } else if (json.isJsonObject()) {
                transition.linearLength = null;
                transition.points.clear();
                for (Map.Entry<String, JsonElement> item : json.getAsJsonObject().entrySet()) {
                    transition.points.put(Float.parseFloat(item.getKey()), item.getValue().getAsFloat());
                }
            }
            return transition;
        }
    }
}
