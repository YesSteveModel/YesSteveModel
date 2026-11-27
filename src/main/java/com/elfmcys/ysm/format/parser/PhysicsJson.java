package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.AssetLoadException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.joml.Vector3d;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

record PhysicsJson(JsonObject values, String context) {
    static PhysicsJson parse(String text, String context) throws IOException {
        try (var reader = new JsonReader(new StringReader(text))) {
            var root = readValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw AssetLoadException.content("Unexpected data after the root object");
            }
            return object(root, context);
        } catch (IOException | IllegalStateException | NumberFormatException error) {
            throw AssetLoadException.content(context + " has invalid JSON: " + error.getMessage(), error);
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 32) {
            throw AssetLoadException.content("Physics JSON nesting is too deep at " + reader.getPath());
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    var name = reader.nextName();
                    if (object.has(name)) {
                        throw AssetLoadException.content("Duplicate field at " + reader.getPath());
                    }
                    object.add(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(readValue(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case STRING -> new JsonPrimitive(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw AssetLoadException.content("Expected a value at " + reader.getPath());
        };
    }

    static PhysicsJson object(JsonElement value, String context) throws AssetLoadException {
        if (value == null || !value.isJsonObject()) {
            throw AssetLoadException.content(context + " must be an object");
        }
        return new PhysicsJson(value.getAsJsonObject(), context);
    }

    static String string(JsonElement value, String context) throws AssetLoadException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw AssetLoadException.content(context + " must be a string");
        }
        return value.getAsString();
    }

    static int slot(JsonElement value, String context) throws AssetLoadException {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                return slot(value.getAsBigDecimal().intValueExact(), context);
            } catch (ArithmeticException error) {
                throw AssetLoadException.content(context + " must be an integer in 1..20", error);
            }
        }
        throw AssetLoadException.content(context + " must be an integer in 1..20");
    }

    static int slot(int number, String context) throws AssetLoadException {
        if (number < 1 || number > 20) {
            throw AssetLoadException.content(context + " must be an integer in 1..20");
        }
        return number;
    }

    private static Number number(JsonElement value, String context) throws AssetLoadException {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return value.getAsNumber();
        }
        throw AssetLoadException.content(context + " must be a number");
    }

    AssetLoadException error(String message) {
        return AssetLoadException.content(context + " " + message);
    }

    PhysicsJson only(String... names) throws AssetLoadException {
        return only(Set.of(names));
    }

    PhysicsJson only(Set<String> allowed) throws AssetLoadException {
        for (var key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw error("has unknown field '" + key + "'");
            }
        }
        return this;
    }

    PhysicsJson withContext(String newContext) {
        return new PhysicsJson(values, newContext);
    }

    boolean has(String key) {
        return values.has(key);
    }

    PhysicsJson object(String key) throws AssetLoadException {
        return object(values.get(key), field(key));
    }

    PhysicsJson optionalObject(String key) throws AssetLoadException {
        return has(key) ? object(key) : new PhysicsJson(new JsonObject(), field(key));
    }

    JsonArray array(String key) throws AssetLoadException {
        var value = values.get(key);
        if (value == null || !value.isJsonArray()) {
            throw error("field '" + key + "' must be an array");
        }
        return value.getAsJsonArray();
    }

    JsonArray optionalArray(String key) throws AssetLoadException {
        return has(key) ? array(key) : new JsonArray();
    }

    String string(String key) throws AssetLoadException {
        return string(values.get(key), field(key));
    }

    String string(String key, String fallback) throws AssetLoadException {
        return has(key) ? string(key) : fallback;
    }

    float number(String key) throws AssetLoadException {
        return number(values.get(key), field(key)).floatValue();
    }

    float number(String key, float fallback) throws AssetLoadException {
        return has(key) ? number(key) : fallback;
    }

    double decimal(String key) throws AssetLoadException {
        return number(values.get(key), field(key)).doubleValue();
    }

    double decimal(String key, double fallback) throws AssetLoadException {
        return has(key) ? decimal(key) : fallback;
    }

    boolean bool(String key) throws AssetLoadException {
        var value = values.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw error("field '" + key + "' must be a boolean");
        }
        return value.getAsBoolean();
    }

    Vector3d vector(String key) throws AssetLoadException {
        var array = xyz(key);
        var field = field(key);
        return new Vector3d(number(array.get(0), field).doubleValue(), number(array.get(1), field).doubleValue(),
            number(array.get(2), field).doubleValue());
    }

    Vector3d optionalVector(String key) throws AssetLoadException {
        return has(key) ? vector(key) : new Vector3d();
    }

    List<Float> floatVector(String key) throws AssetLoadException {
        var array = xyz(key);
        var field = field(key);
        return List.of(number(array.get(0), field).floatValue(), number(array.get(1), field).floatValue(),
            number(array.get(2), field).floatValue());
    }

    List<Float> optionalFloatVector(String key) throws AssetLoadException {
        return has(key) ? floatVector(key) : List.of(0f, 0f, 0f);
    }

    private JsonArray xyz(String key) throws AssetLoadException {
        var array = array(key);
        if (array.size() != 3) {
            throw error("field '" + key + "' must have three XYZ components");
        }
        return array;
    }

    private String field(String key) {
        return context + " field '" + key + "'";
    }
}
