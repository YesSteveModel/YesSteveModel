package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.model.Bone;
import com.elfmcys.ysm.format.parser.pojo.model.Cube;
import com.elfmcys.ysm.format.parser.pojo.model.CubeUv;
import com.elfmcys.ysm.format.parser.pojo.model.GeoModel;
import com.elfmcys.ysm.format.parser.pojo.model.Geometry;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CubesProto;
import com.elfmcys.ysm.testutil.ProtobufJavaOracle;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.protobuf.ByteString;
import com.google.protobuf.DynamicMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GeoBuilderTest {
    private static final float[] CUBE_NORMALS = new float[] {-1, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 1, 0, 1, 0, 0, -1, 0};

    @Test
    void writesNativeCompatibleFullCubeGeometry() throws Exception {
        var cubes = buildCubes(modelWithCubes(cube(null)));
        var encoded = cubes.cubesLegacy().get(0);

        assertEquals(6, encoded.faceCount());
        assertEquals(24, encoded.posIndices().size());
        assertEquals(24, encoded.uvIndices().size());
        assertEquals(18, encoded.normal().size());
    }

    @Test
    void cubeRotationDoesNotAffectFollowingCubeNormals() throws Exception {
        var cubes = buildCubes(modelWithCubes(cube(new float[] {0, 90, 0}), cube(null)));
        var expected = buildCubes(modelWithCubes(cube(null))).cubesLegacy().get(0);
        var actual = cubes.cubesLegacy().get(1);

        assertArrayEquals(expected.pos().toFloatArray(), actual.pos().toFloatArray());
        assertArrayEquals(expected.posIndices().toIntArray(), actual.posIndices().toIntArray());
        assertArrayEquals(expected.uv().toFloatArray(), actual.uv().toFloatArray());
        assertArrayEquals(expected.uvIndices().toIntArray(), actual.uvIndices().toIntArray());
        assertArrayEquals(CUBE_NORMALS, actual.normal().toFloatArray());
    }

    @Test
    void repeatedBuildsProduceIdenticalNormals() throws Exception {
        var model = modelWithCubes(cube(new float[] {20, 35, 10}));

        var first = buildCubes(model).cubesLegacy().get(0).normal().toFloatArray();
        var second = buildCubes(model).cubesLegacy().get(0).normal().toFloatArray();

        assertArrayEquals(first, second);
    }

    @Test
    void rawConversionPublishesProtobufJavaDecodableGeometry() throws Exception {
        var result = GeoBuilder.build(modelWithCubes(cube(null)));
        var geometry =
            ProtobufJavaOracle.parse("mixel.asset.model.data.GeoModel", ProtoUtil.serializeToArray(result.model));
        var bones = ProtobufJavaOracle.field(geometry.getDescriptorForType(), "bones");
        var cubesBytes =
            (ByteString)geometry.getField(ProtobufJavaOracle.field(geometry.getDescriptorForType(), "cubes"));
        var cubes = ProtobufJavaOracle.parse("mixel.asset.model.data.Cubes", cubesBytes.toByteArray());
        var cubeField = ProtobufJavaOracle.field(cubes.getDescriptorForType(), "cubes_legacy");
        var cube = (DynamicMessage)cubes.getRepeatedField(cubeField, 0);

        assertEquals(1, geometry.getRepeatedFieldCount(bones));
        assertEquals(1, cubes.getRepeatedFieldCount(cubeField));
        assertEquals(6, cube.getField(ProtobufJavaOracle.field(cube.getDescriptorForType(), "faceCount")));
        assertEquals(24,
            cube.getRepeatedFieldCount(ProtobufJavaOracle.field(cube.getDescriptorForType(), "pos_indices")));
        assertEquals(18, cube.getRepeatedFieldCount(ProtobufJavaOracle.field(cube.getDescriptorForType(), "normal")));
    }

    private static CubesProto buildCubes(GeoModel model) throws Exception {
        var result = GeoBuilder.build(model);
        return CubesProto.parseFrom(ProtoSource.newInstance(result.model.cubes()));
    }

    private static GeoModel modelWithCubes(Cube... cubes) {
        var raw = new GeoModel();
        raw.minecraftGeometry = new ArrayList<>();
        var geometry = new Geometry();
        geometry.description.textureWidth = 64;
        geometry.description.textureHeight = 64;
        raw.minecraftGeometry.add(geometry);

        var bone = new Bone();
        bone.name = "root";
        bone.cubes = new ArrayList<>();
        geometry.bones.add(bone);
        bone.cubes.addAll(List.of(cubes));
        return raw;
    }

    private static Cube cube(float[] rotation) {
        var cube = new Cube();
        cube.origin = new float[] {0, 0, 0};
        cube.size = new float[] {16, 16, 16};
        cube.uv = CubeUv.box(new float[] {0, 0});
        cube.rotation = rotation;
        return cube;
    }
}
