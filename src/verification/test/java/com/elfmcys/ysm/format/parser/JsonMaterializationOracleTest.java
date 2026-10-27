package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.BoneKeyFrameList;
import com.elfmcys.ysm.format.parser.pojo.animation.value.Vector3v;
import com.elfmcys.ysm.format.parser.pojo.animation.value.UnionValue;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.EasingType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class JsonMaterializationOracleTest {
    @Test
    void preservesIndependentValuesOrderPresenceAndEasing() {
        var fixture = fixtures();
        var gson = new Gson();
        for (var item : fixture.getAsJsonObject().getAsJsonArray("valid")) {
            var entry = item.getAsJsonObject();
            var result = gson.fromJson(entry.get("input"), BoneKeyFrameList.class);
            var ticks = entry.getAsJsonArray("ticks");
            assertEquals(ticks.size(), result.keyFrames.size(), entry.get("id").getAsString());
            for (int i = 0; i < ticks.size(); i++) {
                var frame = result.keyFrames.get(i);
                assertEquals(ticks.get(i).getAsFloat(), frame.startTick);
                vector(entry.getAsJsonArray("pre").get(i), frame.preValue);
                vector(entry.getAsJsonArray("post").get(i), frame.postValue);
                assertSame(entry.getAsJsonArray("easing").get(i).getAsString().equals("catmullrom")
                    ? EasingType.CATMULLROM : EasingType.LINEAR, frame.easingType);
            }
        }
    }

    @Test
    void rejectsIndependentInvalidShapes() {
        var gson = new Gson();
        for (var item : fixtures().getAsJsonObject().getAsJsonArray("invalid")) {
            var entry = item.getAsJsonObject();
            assertThrows(RuntimeException.class, () -> gson.fromJson(entry.get("input"), BoneKeyFrameList.class),
                entry.get("id").getAsString());
        }
    }

    private static JsonElement fixtures() {
        try (var input = new InputStreamReader(
            JsonMaterializationOracleTest.class.getResourceAsStream("/render-target-calibration/json-values.json"),
            StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(input);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static void vector(JsonElement expected, Vector3v actual) {
        if (expected.isJsonNull()) {
            assertNull(actual);
            return;
        }
        JsonArray components = expected.getAsJsonArray();
        assertEquals(components.size(), actual.components.size());
        for (int i = 0; i < components.size(); i++) {
            var value = components.get(i).getAsJsonPrimitive();
            var component = actual.components.get(i);
            if (value.isNumber()) {
                assertEquals(UnionValue.ValueType.FLOAT, component.type);
                assertEquals(value.getAsFloat(), component.floatValue);
            } else {
                assertEquals(UnionValue.ValueType.STRING, component.type);
                assertEquals(value.getAsString(), component.stringValue);
            }
        }
    }
}
