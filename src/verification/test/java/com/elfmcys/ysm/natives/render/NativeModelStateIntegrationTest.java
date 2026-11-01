package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.client.gui.GuiModelFraming;
import com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CubeLegacyProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CubesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.ProtoUtil;
import java.nio.ByteBuffer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeModelStateIntegrationTest {
    @Test
    void guiCameraFitsNativeGeometryWithoutChangingAuthorPose() throws Exception {
        var source = quadModel();
        try (var input = ArrayBuffer.move(ProtoUtil.serializeToArray(source));
            var texture = NativeBuffer.allocate(16)) {
            texture.nio().putLong(0, -1L).putLong(8, -1L);
            var baked = NativeBakedModel.bake(input, 1, texture, 2, 2, 29, false);
            var locators = new GeoLocatorType(new ResourceLocation("test", "gui-framing")) {};
            try (var model = new GeoModel("gui-framing", source, locators, baked); var state = new GeoModelState()) {
                var animated = new AnimatedGeoModel(model);
                var attributes = animated.getBoneAttributes();
                var framing = new GuiModelFraming();
                framing.begin(type -> null, 52, 70);
                for (var size : new float[] {2, 1.0e30F}) {
                    attributes[0] = 0.4F;
                    attributes[1] = -0.7F;
                    attributes[2] = 0.3F;
                    attributes[3] = -35.4F * size;
                    attributes[4] = -38 * size;
                    attributes[5] = -22.55F * size;
                    attributes[6] = attributes[7] = attributes[8] = size;
                    var expectedAttributes = attributes.clone();
                    assertTrue(state.extract(animated));
                    var bonePose = state.getNativeState().getBonePoses().getPose(0, new Matrix4f());
                    var expectedPose = new Matrix4f(bonePose);
                    var poseStack = new PoseStack();
                    poseStack.translate(0, 0, 1_000);
                    poseStack.mulPose(new org.joml.Quaternionf().rotateZ((float)Math.PI).rotateX(-0.17F));
                    var normal = new org.joml.Matrix3f(poseStack.last().normal());
                    assertTrue(framing.apply(new Object(), state, poseStack));
                    assertTrue(poseStack.last().pose().isFinite());
                    assertEquals(normal, poseStack.last().normal());
                    assertArrayEquals(expectedAttributes, attributes);
                    assertEquals(expectedPose, state.getNativeState().getBonePoses().getPose(0, bonePose));
                    for (var x : new float[] {0, 1}) {
                        for (var y : new float[] {0, 1}) {
                            var point = bonePose.transformPosition(new Vector3f(x, y, 0));
                            poseStack.last().pose().transformPosition(point);
                            assertTrue(Math.abs(point.x) <= 26, point.toString());
                            assertTrue(Math.abs(point.y) <= 35, point.toString());
                            assertTrue(Math.abs(point.z - 1_000) <= 801, point.toString());
                        }
                    }
                }
                attributes[9] = 1;
                assertTrue(state.extract(animated));
                assertFalse(framing.apply(new Object(), state, new PoseStack()));
                framing.end();
            }
        }
    }

    @Test
    void tryBakeRejectsMalformedJavaOwnedGeometry() {
        try (var modelData = ArrayBuffer.move(new byte[] {(byte)0xff}); var texture = NativeBuffer.allocate(4)) {
            assertFalse(NativeBakedModel.tryBake(modelData, texture, 1, 1, 0, false, false, false));
        }
    }

    @Test
    void extractsQuadUv() throws Exception {
        var model = quadModel();
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize()); var texture = NativeBuffer.allocate(16)) {
            model.writeTo(ProtoUtil.sink(modelData));
            texture.nio().putLong(0, -1L).putLong(8, -1L);
            var baked = NativeBakedModel.bake(modelData, 1, texture, 2, 2, 29, false);
            try (var directModel = baked.bakedModel(); var bakedData = directModel.serialize()) {
                var read = NativeBakedModel.read(bakedData, 1);
                try (var bakedModel = read.bakedModel()) {
                    var uv = bakedModel.getCubeData(0, 1, 0, 1)[0].quads()[0].uv();
                    assertArrayEquals(new Vector2f[] {new Vector2f(0, 0), new Vector2f(0.5f, 0),
                        new Vector2f(0.5f, 0.5f), new Vector2f(0, 0.5f)}, uv);
                    assertArrayEquals(baked.sortedBoneIndices(), read.sortedBoneIndices());
                    assertArrayEquals(new float[] {0, 0, 0, 1, 1, 0}, bakedModel.getBoneBounds(1));
                    assertArrayEquals(uv, directModel.getCubeData(0, 1, 0, 1)[0].quads()[0].uv());
                    assertEquals(bakedModel.getBoneInfo(0, 1)[0], directModel.getBoneInfo(0, 1)[0]);
                    assertEquals(new org.joml.Vector4f(0, 0, 0, 0),
                        directModel.getCubeData(0, 1, 0, 1)[0].quads()[0].tangent());
                }
            }
        }
    }

    @Test
    void extractsBorrowedViewsAndCountsWithNoRenderBones() throws Exception {
        var model = GeoModelProto
            .newBuilder().addBones(BoneProto.newBuilder().setName("root").addRotate(0).addRotate(0).addRotate(0)
                .addPivot(0).addPivot(0).addPivot(0).build())
            .setProperties(properties()).setCubes(ByteBuffer.allocate(0)).build();

        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize()); var texture = NativeBuffer.allocate(4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            var baked = NativeBakedModel.bake(modelData, 1, texture, 1, 1, 0, false);
            try (var bakedModel = baked.bakedModel(); var state = NativeModelState.create()) {
                var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
                attributes[6] = 1;
                attributes[7] = 1;
                attributes[8] = 1;
                attributes[13] = 0xFFFF;

                assertTrue(state.extract(bakedModel, attributes));
                assertEquals(1, state.getBonePoses().getBoneCount());
                assertEquals(0, state.getRenderBoneIndices().capacity());
                assertTrue(state.getLocatorBoneIndices().isEmpty());
                assertEquals(0, state.getTotalVertexCount());
                assertEquals(0, state.getTranslucentVertexCount());

                state.close();
                assertFalse(state.isValid());
                assertNull(state.getBonePoses());
                assertNull(state.getRenderBoneIndices());
                assertThrows(IllegalStateException.class, state::get);
            }
        }
    }

    @Test
    void modelAndSerializedBytesHaveIndependentLifetimes() throws Exception {
        var model = quadModel();
        NativeBakedModel.ModelResult baked;
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize()); var texture = NativeBuffer.allocate(16)) {
            model.writeTo(ProtoUtil.sink(modelData));
            texture.nio().putLong(0, -1L).putLong(8, -1L);
            baked = NativeBakedModel.bake(modelData, 1, texture, 2, 2, 29, false);
        }
        NativeStorage bytes;
        try (var direct = baked.bakedModel()) {
            assertEquals(1, direct.getInfo().boneCount());
            assertEquals(new Vector2f(0.5f, 0.5f), direct.getCubeData(0, 1, 0, 1)[0].quads()[0].uv()[2]);
            try (var disposable = direct.serialize()) {
                assertTrue(disposable.size() > 0);
            }
            assertEquals(1, direct.getInfo().boneCount());
            assertEquals(new Vector2f(0.5f, 0.5f), direct.getCubeData(0, 1, 0, 1)[0].quads()[0].uv()[2]);
            bytes = direct.serialize();
        }
        try (bytes; var restored = NativeBakedModel.read(bytes, 1).bakedModel()) {
            assertEquals(1, restored.getInfo().boneCount());
            assertEquals(new Vector2f(0.5f, 0.5f), restored.getCubeData(0, 1, 0, 1)[0].quads()[0].uv()[2]);
        }
        assertThrows(IllegalStateException.class, baked.bakedModel()::serialize);
        baked.bakedModel().close();
    }

    @Test
    void mappingDeliveryRejectsWrongBoneCount() throws Exception {
        var model = GeoModelProto.newBuilder()
            .addBones(BoneProto.newBuilder().setName("root").addPivot(0).addPivot(0).addPivot(0).addRotate(0)
                .addRotate(0).addRotate(0).build())
            .setProperties(properties()).setCubes(ByteBuffer.allocate(0)).build();
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize()); var texture = NativeBuffer.allocate(4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            assertThrows(RuntimeException.class, () -> NativeBakedModel.bake(modelData, 0, texture, 1, 1, 0, false));
            try (var direct = NativeBakedModel.bake(modelData, 1, texture, 1, 1, 0, false).bakedModel();
                var bytes = direct.serialize()) {
                assertThrows(RuntimeException.class, () -> NativeBakedModel.read(bytes, 0));
                assertEquals(1, direct.getInfo().boneCount());
            }
        }
    }

    @Test
    void firstNonemptyCubeInfoMatchesKnownQuadOnFreshThread() throws Exception {
        // A fresh consumer thread makes first-use carrier growth independent of test order.
        var verification = new java.util.concurrent.FutureTask<Void>(() -> {
            var source = quadModel();
            try (var input = ArrayBuffer.move(ProtoUtil.serializeToArray(source));
                var texture = NativeBuffer.allocate(16)) {
                texture.nio().putLong(0, -1L).putLong(8, -1L);
                try (var model = NativeBakedModel.bake(input, 1, texture, 2, 2, 29, false).bakedModel();
                    var state = NativeModelState.create()) {
                    var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
                    attributes[6] = attributes[7] = attributes[8] = 1;
                    attributes[13] = 0xFFFF;
                    assertTrue(state.extract(model, attributes));
                    var cubes = state.calculateRenderCubeInfo();
                    assertEquals(1, cubes.boneCount());
                    assertEquals(1, cubes.cubeCount());
                    assertEquals(1, cubes.getCutoutNoCullingCubeCount(0));
                    assertEquals(new NativeModelState.VertexRange(0, 4), cubes.getCutoutNoCulling(0, 0));
                }
            }
            return null;
        });
        new Thread(verification, "cube-info-first-use").start();
        verification.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void emptyModelIsDirectlyUsable() throws Exception {
        var source = GeoModelProto.newBuilder().setProperties(properties()).setCubes(ByteBuffer.allocate(0)).build();
        try (var input = ArrayBuffer.move(ProtoUtil.serializeToArray(source)); var texture = NativeBuffer.allocate(4);
            var model = NativeBakedModel.bake(input, 0, texture, 1, 1, 0, false).bakedModel()) {
            assertEquals(0, model.getInfo().boneCount());
            assertArrayEquals(new float[0], model.getBoneBounds(0));
            try (var state = NativeModelState.create()) {
                assertTrue(state.extract(model, new float[0]));
                assertEquals(0, state.getTotalVertexCount());
            }
        }
    }

    private static GeoModelProto quadModel() throws java.io.IOException {
        var cube = CubeLegacyProto.newBuilder().setFaceCount(1).addPos(0).addPos(0).addPos(0).addPos(1).addPos(0)
            .addPos(0).addPos(1).addPos(1).addPos(0).addPos(0).addPos(1).addPos(0).addPosIndices(0).addPosIndices(1)
            .addPosIndices(2).addPosIndices(3).addUv(0).addUv(0).addUv(0.5f).addUv(0).addUv(0.5f).addUv(0.5f).addUv(0)
            .addUv(0.5f).addUvIndices(0).addUvIndices(1).addUvIndices(2).addUvIndices(3).addNormal(0).addNormal(0)
            .addNormal(1).build();
        var cubes = CubesProto.newBuilder().addCubesLegacy(cube).build();
        return GeoModelProto.newBuilder()
            .addBones(BoneProto.newBuilder().setName("root").addPivot(0).addPivot(0).addPivot(0).addRotate(0)
                .addRotate(0).addRotate(0).setCubeCount(1).build())
            .setProperties(properties()).setCubes(ByteBuffer.wrap(ProtoUtil.serializeToArray(cubes))).build();
    }

    private static GeoPropertiesProto properties() {
        return GeoPropertiesProto.newBuilder().setTextureHeight(0).setTextureWidth(0).build();
    }
}
