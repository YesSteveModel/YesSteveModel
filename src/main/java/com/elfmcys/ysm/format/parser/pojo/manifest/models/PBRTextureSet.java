package com.elfmcys.ysm.format.parser.pojo.manifest.models;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

@JsonAdapter(PBRTextureSet.Adapter.class)
public class PBRTextureSet {
    public String uv = "";
    public String normal;
    public String specular;

    public PBRTextureSet() {}

    public PBRTextureSet(String uv) {
        this.uv = uv;
    }

    public static final class Adapter extends TypeAdapter<PBRTextureSet> {
        @Override
        public PBRTextureSet read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            if (token == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            if (token == JsonToken.STRING) {
                return new PBRTextureSet(in.nextString());
            }
            if (token != JsonToken.BEGIN_OBJECT) {
                throw new JsonParseException("Expected string or object for ModelTexture but was " + token);
            }

            PBRTextureSet texture = new PBRTextureSet();
            in.beginObject();
            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "uv":
                        texture.uv = in.nextString();
                        break;
                    case "normal":
                        texture.normal = readNullableString(in);
                        break;
                    case "specular":
                        texture.specular = readNullableString(in);
                        break;
                    default:
                        in.skipValue();
                        break;
                }
            }
            in.endObject();
            return texture;
        }

        @Override
        public void write(JsonWriter out, PBRTextureSet value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginObject();
            out.name("uv").value(value.uv);
            if (value.normal != null) {
                out.name("normal").value(value.normal);
            }
            if (value.specular != null) {
                out.name("specular").value(value.specular);
            }
            out.endObject();
        }

        private static String readNullableString(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return in.nextString();
        }
    }
}
