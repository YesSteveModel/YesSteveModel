package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class FirstPersonFixtureIntegrationTest {
    @Test
    void fixedFixturesEnterRealBakeAndExtract() throws Exception {
        var root = Path.of(System.getenv("YSM_FIRST_PERSON_FIXTURES"));
        var manifest = JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
        for (var entry : manifest.getAsJsonObject("files").entrySet()) {
            assertEquals(entry.getValue().getAsString(),
                HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(entry.getKey())))),
                entry.getKey());
        }
        var image = ImageIO.read(root.resolve("markers-a.png").toFile());
        try (var texture = NativeBuffer.allocate(image.getWidth() * image.getHeight() * 4)) {
            var pixels = texture.nio();
            for (var y = 0; y < image.getHeight(); y++) {
                for (var x = 0; x < image.getWidth(); x++) {
                    var argb = image.getRGB(x, y);
                    pixels.put((byte)(argb >>> 16)).put((byte)(argb >>> 8)).put((byte)argb).put((byte)(argb >>> 24));
                }
            }
            for (var entry : manifest.getAsJsonObject("models").entrySet()) {
                var bytes = Files.readAllBytes(root.resolve(entry.getKey() + ".pb"));
                var source = GeoModelProto.parseFrom(bytes);
                var expectedBones = entry.getValue().getAsJsonObject().get("bone_count").getAsInt();
                assertEquals(expectedBones, source.bones().size());
                try (var input = ArrayBuffer.move(bytes);
                    var model = NativeBakedModel
                        .bake(input, expectedBones, texture, image.getWidth(), image.getHeight(), 29, false)
                        .bakedModel();
                    var state = NativeModelState.create()) {
                    assertEquals(expectedBones, model.getInfo().boneCount());
                    var attributes = new float[expectedBones * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
                    for (var bone = 0; bone < expectedBones; bone++) {
                        var offset = bone * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
                        for (var axis = 0; axis < 3; axis++) {
                            attributes[offset + axis] = source.bones().get(bone).rotate().get(axis);
                        }
                        attributes[offset + 6] = attributes[offset + 7] = attributes[offset + 8] = 1;
                        attributes[offset + 12] = 0xFFFFFF;
                        attributes[offset + 13] = 0xFFFF;
                    }
                    assertTrue(state.extract(model, attributes), entry.getKey());
                    assertEquals(expectedBones, state.getBonePoses().getBoneCount());
                    assertTrue(state.getTotalVertexCount() > 0, entry.getKey());
                    var cubeCount = 0;
                    var quadCount = 0;
                    var boneInfo = model.getBoneInfo(0, expectedBones);
                    for (var boneIndex = 0; boneIndex < expectedBones; boneIndex++) {
                        var info = boneInfo[boneIndex];
                        var partitions = new NativeBakedModel.BonePartitionInfo[] {info.cutout(),
                            info.cutoutNoCulling(), info.translucent(), info.translucentCulling()};
                        for (var partition = 0; partition < partitions.length; partition++) {
                            var count = partitions[partition].cubeCount();
                            cubeCount += count;
                            for (var cube : model.getCubeData(boneIndex, partition, 0, count)) {
                                quadCount += cube.quadCount();
                            }
                        }
                    }
                    var statistics = entry.getValue().getAsJsonObject();
                    assertEquals(statistics.get("cube_count").getAsInt(), cubeCount);
                    assertEquals(statistics.get("quad_count").getAsInt(), quadCount);
                    System.out.printf(
                        "[first-person-fixture] model=%s bones=%d cubes=%d quads=%d vertexCapacity=%d groupCount=unavailable%n",
                        entry.getKey(), expectedBones, cubeCount, quadCount, state.getTotalVertexCount());
                }
            }
        }
    }
}
