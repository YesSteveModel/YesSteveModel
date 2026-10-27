package com.elfmcys.ysm.format.schema.baked.model;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.testutil.ProtobufJavaOracle;
import com.elfmcys.ysm.util.ProtoBytes;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BakedModelWriterTest {
    @TempDir
    Path temp;

    @Test
    void writesWithoutAnExplicitSummary() throws Exception {
        var path = temp.resolve("model.geo.ysm-cache");
        var hash = new byte[Blake3.HASH_SIZE];
        var model = GeoModelProto.newBuilder().setProperties(properties()).setCubes(ByteBuffer.wrap(new byte[] {4, 5}));
        model.addBones(BoneProto.newBuilder().setName("root").build());
        try (var baked = ArrayBuffer.move(new byte[] {1, 2, 3}); var writer = new BakedModelWriter();
            var output = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.setData(hash, model.build(), baked);
            writer.write(output);
        }

        try (var input = FileChannel.open(path, StandardOpenOption.READ)) {
            var view = new BakedModelView(input);
            assertEquals(new Hash256(hash), view.bakeHash());
            assertArrayEquals(new byte[] {4, 5}, ProtoBytes.copy(view.model().cubes()));
            var container = AssetContainerReader.read(input);
            var chunk = container.getChunkInfo(BakedModelConstant.MANIFEST_CHUNK_NAME);
            try (var bytes = InlineChunkReader.readPayload(input, chunk, BufferType.ARRAY)) {
                var protobufIndex =
                    ProtobufJavaOracle.parse("mixel.asset.model.data.GeoModel", ProtoBytes.copy(bytes.nio()));
                assertEquals(1, protobufIndex
                    .getRepeatedFieldCount(ProtobufJavaOracle.field(protobufIndex.getDescriptorForType(), "bones")));
            }
            try (var baked = view.readModelData(input)) {
                assertEquals(3, baked.size());
            }
        }
    }

    private static GeoPropertiesProto properties() {
        return GeoPropertiesProto.newBuilder().setTextureHeight(0).setTextureWidth(0).build();
    }
}
