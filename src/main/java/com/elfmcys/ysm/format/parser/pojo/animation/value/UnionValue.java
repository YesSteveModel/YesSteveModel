package com.elfmcys.ysm.format.parser.pojo.animation.value;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;

import java.lang.reflect.Type;

@JsonAdapter(UnionValue.Adapter.class)
public class UnionValue {
    public ValueType type = ValueType.NONE;
    public float floatValue = 0;
    public String stringValue = "";

    public static UnionValue of(float value) {
        var union = new UnionValue();
        union.type = ValueType.FLOAT;
        union.floatValue = value;
        return union;
    }

    public static UnionValue of(String value) {
        var union = new UnionValue();
        union.type = ValueType.STRING;
        union.stringValue = value;
        return union;
    }

    public enum ValueType {
        NONE, FLOAT, STRING
    }

    public static UnionValue fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) {
            var primitive = json.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return UnionValue.of(primitive.getAsFloat());
            }
            if (primitive.isString()) {
                return UnionValue.of(primitive.getAsString());
            }
        }
        throw new JsonParseException("Invalid union value: " + (json == null ? "null" : json));
    }

    public static final class Adapter implements JsonDeserializer<UnionValue> {
        @Override
        public UnionValue deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            return fromJson(json);
        }
    }
}
