package com.elfmcys.ysm.format.parser.pojo.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.JsonAdapter;
import java.lang.reflect.Type;

@JsonAdapter(CubeUv.Adapter.class)
public class CubeUv {
    public float[] boxUv;
    public UvFaces perFaceUv;

    public static CubeUv box(float[] boxUv) {
        CubeUv value = new CubeUv();
        value.boxUv = boxUv;
        return value;
    }

    public static CubeUv perFace(UvFaces perFaceUv) {
        CubeUv value = new CubeUv();
        value.perFaceUv = perFaceUv;
        return value;
    }

    public static final class Adapter implements JsonDeserializer<CubeUv>, JsonSerializer<CubeUv> {
        @Override
        public CubeUv deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            if (json.isJsonArray()) {
                return CubeUv.box(context.deserialize(json, float[].class));
            }
            if (json.isJsonObject()) {
                return CubeUv.perFace(context.deserialize(json, UvFaces.class));
            }
            throw new JsonParseException("Expected cube uv to be an array or object");
        }

        @Override
        public JsonElement serialize(CubeUv src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == null) {
                return JsonNull.INSTANCE;
            }
            if (src.boxUv != null) {
                return context.serialize(src.boxUv);
            }
            if (src.perFaceUv != null) {
                return context.serialize(src.perFaceUv);
            }
            return JsonNull.INSTANCE;
        }
    }
}
