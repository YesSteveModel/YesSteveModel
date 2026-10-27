package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.model.GeoModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CubesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
class FirstPersonRawFixtureTest {
    @Test
    void rawGameGeometryMatchesFixedProtocolGeometry() throws Exception {
        var root = Path.of(System.getenv("YSM_FIRST_PERSON_FIXTURES"));
        var manifest = JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
        var tolerance = manifest.getAsJsonObject("tolerance").get("position_absolute_blocks").getAsFloat();
        for (var name : manifest.getAsJsonObject("models").keySet()) {
            var expected = GeoModelProto.parseFrom(Files.readAllBytes(root.resolve(name + ".pb")));
            var raw = new Gson().fromJson(Files.readString(root.resolve(name + ".geo.json")), GeoModel.class);
            var actual = GeoBuilder.build(raw).model;
            assertEquals(expected.bones().size(), actual.bones().size(), name);
            for (var index = 0; index < expected.bones().size(); index++) {
                var left = expected.bones().get(index);
                var right = actual.bones().get(index);
                assertEquals(left.name(), right.name());
                assertEquals(left.parent(), right.parent());
                assertEquals(left.cubeCount(), right.cubeCount());
                for (var axis = 0; axis < 3; axis++) {
                    assertEquals(left.pivot().get(axis), right.pivot().get(axis), tolerance);
                    assertEquals(left.rotate().get(axis), right.rotate().get(axis), tolerance);
                }
            }
            var expectedCubes = CubesProto.parseFrom(bytes(expected.cubes())).cubesLegacy();
            var actualCubes = CubesProto.parseFrom(bytes(actual.cubes())).cubesLegacy();
            assertEquals(expectedCubes.size(), actualCubes.size());
            for (var index = 0; index < expectedCubes.size(); index++) {
                var left = expectedCubes.get(index);
                var right = actualCubes.get(index);
                assertEquals(left.faceCount(), right.faceCount());
                for (var vertex = 0; vertex < left.faceCount() * 4; vertex++) {
                    for (var axis = 0; axis < 3; axis++) {
                        assertEquals(left.pos().get(left.posIndices().get(vertex) * 3 + axis),
                            right.pos().get(right.posIndices().get(vertex) * 3 + axis), tolerance, name);
                    }
                    for (var axis = 0; axis < 2; axis++) {
                        assertEquals(left.uv().get(left.uvIndices().get(vertex) * 2 + axis),
                            right.uv().get(right.uvIndices().get(vertex) * 2 + axis), tolerance, name);
                    }
                }
                for (var component = 0; component < left.faceCount() * 3; component++) {
                    assertEquals(left.normal().get(component), right.normal().get(component), tolerance, name);
                }
            }
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var result = new byte[buffer.remaining()];
        buffer.duplicate().get(result);
        return result;
    }
}
