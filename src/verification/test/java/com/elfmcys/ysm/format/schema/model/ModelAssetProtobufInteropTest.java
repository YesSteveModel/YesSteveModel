package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LanguageFileProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LicenseProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.testutil.ProtobufJavaOracle;
import com.elfmcys.ysm.util.ProtoBytes;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.protobuf.ByteString;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import us.hebi.quickbuf.ProtoSource;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelAssetProtobufInteropTest {
    @Test
    void bothCodecsAgreeOnRepresentativeModelValues() throws Exception {
        var manifest = manifest("quickbuf");
        var protobufManifest =
            ProtobufJavaOracle.parse("mixel.manifest.Manifest", ProtoUtil.serializeToArray(manifest));
        var protobufTarget = repeatedMessage(protobufManifest, "render_targets", 0);
        var protobufTextures = mapMessage(protobufTarget, "textures", "main");
        assertEquals("player", field(protobufTarget, "target_id"));
        assertFalse(
            protobufTextures.hasField(ProtobufJavaOracle.field(protobufTextures.getDescriptorForType(), "normal")));
        assertTrue(
            protobufTextures.hasField(ProtobufJavaOracle.field(protobufTextures.getDescriptorForType(), "specular")));
        assertArrayEquals(hash(),
            ((ByteString)field(messageField(messageField(protobufManifest, "info"), "properties"), "model_id"))
                .toByteArray());

        var modifiedManifest = replaceNestedString(protobufManifest, "info", "metadata", "name", "protobuf-java");
        var quickbufManifest = ManifestProto.parseFrom(ProtoSource.newInstance(modifiedManifest.toByteArray()));
        assertEquals("protobuf-java", quickbufManifest.info().metadataUnsafe().name());
        assertFalse(quickbufManifest.renderTargets().get(0).textures().get("main").hasNormal());
        assertTrue(quickbufManifest.renderTargets().get(0).textures().get("main").hasSpecular());
        assertArrayEquals(hash(), ProtoBytes.copy(quickbufManifest.info().properties().modelId()));

        var geometry = geometry();
        var protobufGeometry =
            ProtobufJavaOracle.parse("mixel.asset.model.data.GeoModel", ProtoUtil.serializeToArray(geometry));
        var protobufBone = repeatedMessage(protobufGeometry, "bones", 0);
        assertEquals(3, repeatedCount(protobufBone, "pivot"));
        assertEquals(3, repeatedCount(protobufBone, "rotate"));
        assertTrue(protobufBone.hasField(ProtobufJavaOracle.field(protobufBone.getDescriptorForType(), "debug")));
        assertEquals(ByteString.copyFrom(new byte[] {10, 11, 12}), field(protobufGeometry, "cubes"));

        var protobufProperties = messageField(protobufGeometry, "properties");
        var changedProperties = protobufProperties.toBuilder()
            .setField(ProtobufJavaOracle.field(protobufProperties.getDescriptorForType(), "texture_height"), 128.0F)
            .build();
        var changedGeometry = protobufGeometry.toBuilder().setField(
            ProtobufJavaOracle.field(protobufGeometry.getDescriptorForType(), "properties"), changedProperties).build();
        var quickbufGeometry = GeoModelProto.parseFrom(ProtoSource.newInstance(changedGeometry.toByteArray()));
        assertEquals(128.0F, quickbufGeometry.properties().textureHeight());
        assertArrayEquals(new float[] {1, 2, 3}, quickbufGeometry.bones().get(0).pivot().toFloatArray());
        assertTrue(quickbufGeometry.bones().get(0).hasDebug());
        assertFalse(quickbufGeometry.bones().get(0).debug().orElseThrow());
        assertArrayEquals(new byte[] {10, 11, 12}, ProtoBytes.copy(quickbufGeometry.cubes()));

        var animation = animation("query.speed");
        var protobufAnimation =
            ProtobufJavaOracle.parse("mixel.asset.model.data.Animation", ProtoUtil.serializeToArray(animation));
        var protobufBlend = messageField(protobufAnimation, "blend_weight");
        assertEquals("program",
            protobufBlend.getOneofFieldDescriptor(protobufBlend.getDescriptorForType().getOneofs().get(0)).getName());
        var protobufProgram = messageField(protobufBlend, "program");
        assertEquals(ByteString.copyFromUtf8("query.speed"), field(protobufProgram, "bytecode"));

        var changedProgram = protobufProgram.toBuilder()
            .setField(ProtobufJavaOracle.field(protobufProgram.getDescriptorForType(), "bytecode"),
                ByteString.copyFromUtf8("query.modified"))
            .build();
        var changedBlend = protobufBlend.toBuilder()
            .setField(ProtobufJavaOracle.field(protobufBlend.getDescriptorForType(), "program"), changedProgram)
            .build();
        var changedAnimation = protobufAnimation.toBuilder()
            .setField(ProtobufJavaOracle.field(protobufAnimation.getDescriptorForType(), "blend_weight"), changedBlend)
            .build();
        var quickbufAnimation = AnimationProto.parseFrom(ProtoSource.newInstance(changedAnimation.toByteArray()));
        var quickbufBlend = quickbufAnimation.blendWeight().orElseThrow();
        assertEquals(ExpressionValueProto.ValueCase.PROGRAM, quickbufBlend.value_Case());
        assertEquals("query.modified",
            java.nio.charset.StandardCharsets.UTF_8.decode(quickbufBlend.program().bytecode().duplicate()).toString());

        var modelData = ModelDataProto.newBuilder().putGeoModels("main", ByteBuffer.wrap(new byte[] {1, 2}))
            .putAnimationFiles("main", AnimationFileProto.newBuilder().addAnimations(animation).build()).build();
        var protobufModelData =
            ProtobufJavaOracle.parse("mixel.asset.model.ModelData", ProtoUtil.serializeToArray(modelData));
        assertEquals(ByteString.copyFrom(new byte[] {1, 2}), mapScalar(protobufModelData, "geo_models", "main"));
        var geoModels = ProtobufJavaOracle.field(protobufModelData.getDescriptorForType(), "geo_models");
        var entry = DynamicMessage.newBuilder(geoModels.getMessageType())
            .setField(ProtobufJavaOracle.field(geoModels.getMessageType(), "key"), "arm")
            .setField(ProtobufJavaOracle.field(geoModels.getMessageType(), "value"),
                ByteString.copyFrom(new byte[] {3, 4}))
            .build();
        var changedModelData = protobufModelData.toBuilder().addRepeatedField(geoModels, entry).build();
        var quickbufModelData = ModelDataProto.parseFrom(ProtoSource.newInstance(changedModelData.toByteArray()));
        assertArrayEquals(new byte[] {3, 4}, ProtoBytes.copy(quickbufModelData.geoModels().get("arm")));
        assertEquals("query.speed", java.nio.charset.StandardCharsets.UTF_8.decode(quickbufModelData.animationFiles()
            .get("main").animations().get(0).blendWeight().orElseThrow().program().bytecode().duplicate()).toString());
    }

    @Test
    void productionModelFileSeamAcceptsBytesFromBothCodecs(@TempDir Path temp) throws Exception {
        var quickbufFile = temp.resolve("quickbuf.ysm");
        writeQuickbufModelFile(quickbufFile, manifest("quickbuf"));
        var quickbufBytes = readManifestBytes(quickbufFile);
        var protobufManifest = ProtobufJavaOracle.parse("mixel.manifest.Manifest", quickbufBytes);
        assertEquals("quickbuf", field(messageField(messageField(protobufManifest, "info"), "metadata"), "name"));
        try (var input = FileChannel.open(quickbufFile, StandardOpenOption.READ)) {
            assertEquals("quickbuf", new ModelFileView(input).getManifest().info().metadataUnsafe().name());
        }

        var protobufBytes =
            replaceNestedString(protobufManifest, "info", "metadata", "name", "protobuf-java").toByteArray();
        var protobufFile = temp.resolve("protobuf-java.ysm");
        writeProtobufModelFile(protobufFile, protobufBytes);
        try (var input = FileChannel.open(protobufFile, StandardOpenOption.READ)) {
            var view = new ModelFileView(input);
            assertEquals("protobuf-java", view.getManifest().info().metadataUnsafe().name());
            assertEquals("player", view.getPlayer().id());
            assertTrue(view.getPlayer().textureDescriptor("main").hasSpecular());
        }
    }

    private static void writeQuickbufModelFile(Path file, ManifestProto manifest) throws Exception {
        try (var writer = new ModelFileWriter();
            var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.setManifest(manifest);
            writer.write(output);
        }
    }

    private static void writeProtobufModelFile(Path file, byte[] protobufBytes) throws Exception {
        var manifest = ManifestProto.parseFrom(ProtoSource.newInstance(protobufBytes));
        try (var logicalBytes = ArrayBuffer.move(protobufBytes); var writer = new ModelFileWriter();
            var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.setManifestExact(manifest, logicalBytes);
            writer.write(output);
        }
    }

    private static byte[] readManifestBytes(Path file) throws Exception {
        try (var input = FileChannel.open(file, StandardOpenOption.READ)) {
            var container = AssetContainerReader.read(input);
            var chunk = container.getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME);
            try (var bytes = InlineChunkReader.readPayload(input, chunk, BufferType.ARRAY)) {
                return ProtoBytes.copy(bytes.nio());
            }
        }
    }

    private static ManifestProto manifest(String name) {
        var image =
            ImageProto.newBuilder().setBlobId(1).setFormat("png").setWidth(16).setHeight(16).setFrameCount(1).build();
        var texture = PBRTextureSetProto.newBuilder().setUv(image).setSpecular(image.withBlobId(2)).build();
        var target = RenderTargetProto.newBuilder().setTargetId("player")
            .setKind(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER).addMatch("minecraft:player")
            .setBlobId(4).putTextures("main", texture)
            .setSettings(ModelSettingsProto.newBuilder().setHeightScale(1).setWidthScale(1).setRenderLayersFirst(true)
                .setForceCulling(true).setGuiNoLighting(true).setMergeMultilineExpr(true).build())
            .setStats(ModelStatsProto.newBuilder().setBones(1).setCubes(1).setFaces(6).build()).build();
        var info = InfoProto.newBuilder()
            .addLanguageFiles(LanguageFileProto.newBuilder().setLocale("en_us").putEntries("model.name", name).build())
            .setSettings(SettingsProto.newBuilder().setDefaultTexture("main").setPreviewAnimation("idle")
                .setDisablePreviewRotation(true).build())
            .setMetadata(MetadataProto.newBuilder().setName(name).setTips("tip")
                .setLicense(LicenseProto.newBuilder().setType("CC0-1.0").setDesc("public domain").build()).build())
            .setProperties(PropertiesProto.newBuilder().setModelId(ByteBuffer.wrap(hash())).setFree(true)
                .setOriginVer("1.0").build())
            .build();
        return ManifestProto.newBuilder().addRenderTargets(target)
            .setCommonAssets(CommonProto.newBuilder().setStringsBlobId(3).build()).setInfo(info).build();
    }

    private static GeoModelProto geometry() throws Exception {
        return GeoModelProto.newBuilder()
            .addBones(BoneProto.newBuilder().setName("root").addPivot(1).addPivot(2).addPivot(3).addRotate(4)
                .addRotate(5).addRotate(6).setDebug(false).setCubeCount(1).build())
            .setProperties(GeoPropertiesProto.newBuilder().setTextureHeight(64).setTextureWidth(64).build())
            .setCubes(ByteBuffer.wrap(new byte[] {10, 11, 12})).build();
    }

    private static AnimationProto animation(String blend) {
        return AnimationProto.newBuilder().setName("idle").setLength(1)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.LOOP)
            .setBlendWeight(ExpressionValueProto.newBuilder()
                .setProgram(ProgramProto.newBuilder().setFormat(1)
                    .setBytecode(ByteBuffer.wrap(blend.getBytes(java.nio.charset.StandardCharsets.UTF_8))).build())
                .build())
            .build();
    }

    private static byte[] hash() {
        var hash = new byte[32];
        for (var index = 0; index < hash.length; index++) {
            hash[index] = (byte)index;
        }
        return hash;
    }

    private static Object field(DynamicMessage message, String name) {
        return message.getField(ProtobufJavaOracle.field(message.getDescriptorForType(), name));
    }

    private static DynamicMessage messageField(DynamicMessage message, String name) {
        return (DynamicMessage)field(message, name);
    }

    private static DynamicMessage repeatedMessage(DynamicMessage message, String name, int index) {
        var descriptor = ProtobufJavaOracle.field(message.getDescriptorForType(), name);
        return (DynamicMessage)message.getRepeatedField(descriptor, index);
    }

    private static int repeatedCount(DynamicMessage message, String name) {
        return message.getRepeatedFieldCount(ProtobufJavaOracle.field(message.getDescriptorForType(), name));
    }

    private static Object mapScalar(DynamicMessage message, String fieldName, String key) {
        var descriptor = ProtobufJavaOracle.field(message.getDescriptorForType(), fieldName);
        for (var index = 0; index < message.getRepeatedFieldCount(descriptor); index++) {
            var entry = (DynamicMessage)message.getRepeatedField(descriptor, index);
            if (key.equals(field(entry, "key"))) {
                return field(entry, "value");
            }
        }
        throw new AssertionError("Missing map key: " + key);
    }

    private static DynamicMessage mapMessage(DynamicMessage message, String fieldName, String key) {
        return (DynamicMessage)mapScalar(message, fieldName, key);
    }

    private static DynamicMessage replaceNestedString(DynamicMessage root, String firstField, String secondField,
        String stringField, String value) {
        var firstDescriptor = ProtobufJavaOracle.field(root.getDescriptorForType(), firstField);
        var first = (DynamicMessage)root.getField(firstDescriptor);
        var secondDescriptor = ProtobufJavaOracle.field(first.getDescriptorForType(), secondField);
        var second = (DynamicMessage)first.getField(secondDescriptor);
        var changedSecond = second.toBuilder()
            .setField(ProtobufJavaOracle.field(second.getDescriptorForType(), stringField), value).build();
        var changedFirst = first.toBuilder().setField(secondDescriptor, changedSecond).build();
        return root.toBuilder().setField(firstDescriptor, changedFirst).build();
    }
}
