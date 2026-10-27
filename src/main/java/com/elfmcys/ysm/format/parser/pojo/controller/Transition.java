package com.elfmcys.ysm.format.parser.pojo.controller;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;

import java.lang.reflect.Type;
import java.util.Map;

@JsonAdapter(Transition.Adapter.class)
public class Transition {
    public String destStateName = "";
    public String condition = "";

    public static final class Adapter implements JsonDeserializer<Transition> {
        @Override
        public Transition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            var transition = new Transition();
            if (json == null || json.isJsonNull() || !json.isJsonObject()) {
                return transition;
            }
            for (Map.Entry<String, JsonElement> item : json.getAsJsonObject().entrySet()) {
                transition.destStateName = item.getKey();
                transition.condition = item.getValue().getAsString();
                break;
            }
            return transition;
        }
    }
}
