package com.elfmcys.ysm.format.schema.baked.asset;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import com.elfmcys.ysm.format.schema.file.AssetFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationEntryProto;
import com.elfmcys.ysm.proto.baked.asset.BakedAssetManifestProto;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.testutil.ProtobufJavaOracle;
import com.elfmcys.ysm.util.ProtoBytes;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.protobuf.DynamicMessage;
import dev.semaweave.ProgramKey;
import dev.semaweave.program.ReadOnlyBytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedAssetWriterTest {
    private static final Hash256 CONTAINER_ID = hash(1);
    private static final Hash256 INPUT_HASH = hash(2);
    private static final String TARGET_ID = "player";

    @Test
    void writesOneCanonicalZstdChunkPerAnimationInInputOrder(@TempDir Path temp) throws Exception {
        var source = List.of(animation("walk"), animation("idle"), animation("jump"));
        var file = writeAsset(temp.resolve("animations.ysm-cache"), source);

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var container = AssetContainerReader.read(channel);
            var manifest = readManifest(channel);
            var protobufManifest = readProtobufJava(channel,
                container.getChunkInfo(BakedAssetConstant.MANIFEST_CHUNK_NAME), "ysm.baked.asset.BakedAssetManifest");
            assertEquals(source.size() + 2, container.getChunkTable().size());
            assertNull(container.getChunkInfo("expression"));
            assertEquals(source.size(), manifest.animations().size());
            assertEquals(source.size(), protobufManifest.getRepeatedFieldCount(
                ProtobufJavaOracle.field(protobufManifest.getDescriptorForType(), "animations")));
            for (var index = 0; index < source.size(); index++) {
                var expectedChunk = BakedAssetConstant.ANIM_CHUNK_PREFIX + "%06d".formatted(index);
                var entry = manifest.animations().get(index);
                var chunk = container.getChunkInfo(expectedChunk);
                assertEquals(source.get(index).name(), entry.name());
                assertEquals(expectedChunk, entry.chunkName());
                assertNotNull(chunk);
                assertEquals("zstd", chunk.encoding());
                assertTrue(chunk.size() > 0);
                assertTrue(chunk.decodeSize() > 0);
            }
            var protobufAnimation =
                readProtobufJava(channel, container.getChunkInfo(BakedAssetConstant.ANIM_CHUNK_PREFIX + "000000"),
                    "ysm.baked.asset.BakedAnimation");
            assertEquals("walk",
                protobufAnimation.getField(ProtobufJavaOracle.field(protobufAnimation.getDescriptorForType(), "name")));
        }

        var view = open(file);
        assertTrue(view.matches(CONTAINER_ID, TARGET_ID, INPUT_HASH));
        assertFalse(view.matches(hash(3), TARGET_ID, INPUT_HASH));
        assertFalse(view.matches(CONTAINER_ID, "vehicle", INPUT_HASH));
        assertFalse(view.matches(CONTAINER_ID, TARGET_ID, hash(3)));
    }

    @Test
    void writesEmptyAnimationSetWithItsManifest(@TempDir Path temp) throws Exception {
        var file = writeAsset(temp.resolve("empty.ysm-cache"), List.of());

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var container = AssetContainerReader.read(channel);
            assertEquals(2, container.getChunkTable().size());
            assertEquals(0, readManifest(channel).animations().size());
        }
        var store = com.elfmcys.ysm.model.resource.client.AnimationStore
            .fromIndices(open(file).animationSet(file).bind(List.of(), null), null);
        assertTrue(store.keySet().isEmpty());
        assertNull(store.state("missing"));
    }

    @Test
    void readsOnlyTheRequestedAnimationChunk(@TempDir Path temp) throws Exception {
        var file = writeAsset(temp.resolve("isolated.ysm-cache"), List.of(animation("walk"), animation("broken")));
        int brokenOffset;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            brokenOffset = AssetContainerReader.read(channel)
                .getChunkInfo(BakedAssetConstant.ANIM_CHUNK_PREFIX + "000001").offset();
        }
        corruptByte(file, brokenOffset);

        var view = open(file);
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertEquals("walk", view.readAnimation(channel, "main", "walk").name());
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThrows(IOException.class, () -> view.readAnimation(channel, "main", "broken"));
        }
    }

    @Test
    void retainsSeparateSourceDescriptorsAndReadsProgramEnvelopesOnDemand(@TempDir Path temp) throws Exception {
        var malformed = ProgramProto.newBuilder().setFormat(123).setBytecode(ByteBuffer.wrap(new byte[] {1})).build();
        var empty = ProgramProto.newBuilder().build();
        var descriptors = List.of(descriptor(0, "function/local", malformed), descriptor(1, "function/default", empty));
        var file = temp.resolve("inputs.ysm-cache");
        try (var writer = new BakedAssetWriter();
            var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.setData(CONTAINER_ID, TARGET_ID, INPUT_HASH, Map.of("main", List.of(animation("walk"))), descriptors,
                Map.of(0, malformed, 1, empty));
            writer.write(channel);
        }
        var view = open(file);
        var inputs = view.animationSet(file);
        assertEquals(descriptors, inputs.descriptors());
        assertEquals(malformed, inputs.loadProgram(0));
        assertEquals(empty, inputs.loadProgram(1));
        assertThrows(IOException.class, () -> inputs.loadProgram(2));
        try (var phase = inputs.source().openReadPhase()) {
            assertEquals("walk", phase.load("main", "walk").name());
            assertEquals(empty, phase.loadProgram(1));
        }

        int offset;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            offset = AssetContainerReader.read(channel).getChunkInfo(BakedAssetConstant.PROGRAM_CHUNK_PREFIX + "000000")
                .offset();
        }
        corruptByte(file, offset);
        var damaged = open(file).animationSet(file);
        assertEquals(descriptors, damaged.descriptors());
        assertEquals("walk", damaged.source().load("main", "walk").name());
        assertEquals(empty, damaged.loadProgram(1));
        assertThrows(IOException.class, () -> damaged.loadProgram(0));
    }

    @Test
    void rejectsProgramPayloadAndDescriptorMismatches(@TempDir Path temp) throws Exception {
        var program = ProgramProto.newBuilder().setFormat(1).setBytecode(ByteBuffer.wrap(new byte[] {1})).build();
        var valid = descriptor(0, "function/local", program);
        try (var writer = new BakedAssetWriter()) {
            var wrongHash = new ProgramInputDescriptor(0, valid.location(), valid.key(), hash(9), valid.byteLength());
            assertThrows(IOException.class, () -> writer.setData(CONTAINER_ID, TARGET_ID, INPUT_HASH, Map.of(),
                List.of(wrongHash), Map.of(0, program)));
        }
        try (var writer = new BakedAssetWriter()) {
            var gap =
                new ProgramInputDescriptor(1, valid.location(), valid.key(), valid.contentHash(), valid.byteLength());
            assertThrows(IOException.class,
                () -> writer.setData(CONTAINER_ID, TARGET_ID, INPUT_HASH, Map.of(), List.of(gap), Map.of(1, program)));
        }
        var old = temp.resolve("old-combined.ysm-cache");
        try (var writer = new TestAssetWriter("ysm/baked-asset");
            var channel = FileChannel.open(old, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.manifest(manifest());
            writer.write(channel);
        }
        assertRejected(old);
    }

    @Test
    void rejectsMalformedAnimationIndexesAndIncompleteFiles(@TempDir Path temp) throws Exception {
        var first = animation("first");
        var second = animation("second");

        var missing = manifest(entry("first", "animation/000000", first));
        assertRejected(writeCustom(temp.resolve("missing.ysm-cache"), missing, List.of()));

        var duplicateReference =
            manifest(entry("first", "animation/000000", first), entry("second", "animation/000000", second));
        assertRejected(writeCustom(temp.resolve("duplicate.ysm-cache"), duplicateReference,
            List.of(new Chunk("animation/000000", first, 9))));

        assertRejected(
            writeCustom(temp.resolve("extra.ysm-cache"), manifest(), List.of(new Chunk("animation/000000", first, 9))));

        var raw = manifest(entry("first", "animation/000000", first));
        assertRejected(
            writeCustom(temp.resolve("raw.ysm-cache"), raw, List.of(new Chunk("animation/000000", first, 0))));

        var truncated = writeAsset(temp.resolve("truncated.ysm-cache"), List.of(first));
        try (var channel = FileChannel.open(truncated, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 1);
        }
        assertRejected(truncated);
    }

    @Test
    void rejectsAnimationPayloadWhoseNameDoesNotMatchItsIndex(@TempDir Path temp) throws Exception {
        var payload = animation("payload-name");
        var manifest = manifest(entry("index-name", "animation/000000", payload));
        var file = writeCustom(temp.resolve("name-mismatch.ysm-cache"), manifest,
            List.of(new Chunk("animation/000000", payload, 9)));

        var view = open(file);
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThrows(IOException.class, () -> view.readAnimation(channel, "main", "index-name"));
        }
    }

    @Test
    void publishesOnlyAfterACompleteCandidateValidates(@TempDir Path temp) throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache"));
            var staging = cache.reserve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.BAKE)) {
            var target =
                cache.path(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.BAKE, staging.location().fileId());
            writeAsset(staging.file(), List.of(animation("walk")));
            assertFalse(Files.exists(target));
            staging.publish(candidate -> new BakedAssetView(candidate).matches(CONTAINER_ID, TARGET_ID, INPUT_HASH),
                AtomicSharedCache.AtomicReplacement::run);
            assertTrue(open(target).matches(CONTAINER_ID, TARGET_ID, INPUT_HASH));
            try (var failed = cache.reserve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.BAKE)) {
                writeAsset(failed.file(), List.of(animation("walk")));
                assertThrows(IOException.class,
                    () -> failed.publish(ignored -> false, AtomicSharedCache.AtomicReplacement::run));
                assertFalse(Files.exists(cache.path(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.BAKE,
                    failed.location().fileId())));
            }
        }
    }

    @Test
    void rejectsEmptyAndDuplicateAnimationNames(@TempDir Path temp) {
        assertThrows(IOException.class, () -> writeAsset(temp.resolve("empty-name.ysm-cache"), List.of(animation(""))));
        assertThrows(IOException.class,
            () -> writeAsset(temp.resolve("duplicate-name.ysm-cache"), List.of(animation("walk"), animation("walk"))));
    }

    private static void writeAsset(com.elfmcys.ysm.model.storage.CacheFile file, List<BakedAnimationProto> animations)
        throws IOException {
        try (var writer = new BakedAssetWriter()) {
            writer.setData(CONTAINER_ID, TARGET_ID, INPUT_HASH, java.util.Map.of("main", animations), List.of(),
                java.util.Map.of());
            writer.write(file);
        }
    }

    private static Path writeAsset(Path file, List<BakedAnimationProto> animations) throws IOException {
        Files.createDirectories(file.getParent());
        try (var writer = new BakedAssetWriter(); var channel = FileChannel.open(file, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.setData(CONTAINER_ID, TARGET_ID, INPUT_HASH, java.util.Map.of("main", animations), List.of(),
                java.util.Map.of());
            writer.write(channel);
        }
        return file;
    }

    private static Path writeCustom(Path file, BakedAssetManifestProto manifest, List<Chunk> chunks)
        throws IOException {
        try (var writer = new TestAssetWriter(); var channel = FileChannel.open(file, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (var chunk : chunks) {
                writer.animation(chunk.name(), chunk.animation(), chunk.compression());
            }
            writer.manifest(manifest);
            writer.write(channel);
        }
        return file;
    }

    private static BakedAssetView open(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return new BakedAssetView(channel);
        }
    }

    private static BakedAssetManifestProto readManifest(FileChannel channel) throws IOException {
        var chunk = AssetContainerReader.read(channel).getChunkInfo(BakedAssetConstant.MANIFEST_CHUNK_NAME);
        try (var data = InlineChunkReader.readPayload(channel, chunk, BufferType.ARRAY)) {
            return BakedAssetManifestProto.parseFrom(ProtoUtil.source((ArrayBuffer)data));
        }
    }

    private static DynamicMessage readProtobufJava(FileChannel channel, AssetContainerView.ChunkInfo chunk,
        String messageName) throws IOException {
        try (var data = InlineChunkReader.readPayload(channel, chunk, BufferType.ARRAY)) {
            return ProtobufJavaOracle.parse(messageName, ProtoBytes.copy(data.nio()));
        }
    }

    private static BakedAssetManifestProto manifest(BakedAnimationEntryProto... entries) {
        var manifest = BakedAssetManifestProto.newBuilder().setRenderTargetId(TARGET_ID)
            .setContainerId(ProtoBytes.wrap(CONTAINER_ID)).setInputHash(ProtoBytes.wrap(INPUT_HASH));
        for (var entry : entries) {
            manifest.addAnimations(entry);
        }
        return manifest.build();
    }

    private static BakedAnimationEntryProto entry(String name, String chunkName, BakedAnimationProto source)
        throws IOException {
        var entry = BakedAnimationEntryProto.newBuilder().setName(name).setSourceFile("main").setChunkName(chunkName)
            .setSourceHash(ProtoBytes.wrap(ModelHashing.blake3(ProtoUtil.serializeToArray(source))));
        return entry.build();
    }

    private static BakedAnimationProto animation(String name) {
        return BakedAnimationProto.newBuilder().setName(name).setLength(0).setLoop(LoopType.UNSPECIFIED).build();
    }

    private static ProgramInputDescriptor descriptor(int id, String location, ProgramProto program) throws IOException {
        var bytes = ProtoUtil.serializeToArray(program);
        return new ProgramInputDescriptor(id,
            new ModelProgramInventory.SemanticLocation(TARGET_ID, ExpressionSet.MAIN, location,
                ProgramRole.USER_FUNCTION, "callable"),
            new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)id})), ModelHashing.blake3(bytes), bytes.length);
    }

    private static Hash256 hash(int value) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte)value);
        return new Hash256(bytes);
    }

    private static void corruptByte(Path file, int offset) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var value = ByteBuffer.allocate(1);
            channel.position(offset);
            channel.read(value);
            value.flip();
            var corrupted = (byte)(value.get() ^ 0x7f);
            value.clear().put(corrupted).flip();
            channel.position(offset);
            channel.write(value);
        }
    }

    private static void assertRejected(Path file) {
        assertThrows(IOException.class, () -> open(file));
    }

    private record Chunk(String name, BakedAnimationProto animation, int compression) {
    }

    private static final class TestAssetWriter extends AssetFileWriter {
        private TestAssetWriter() {
            this(BakedAssetConstant.SCHEMA_ID);
        }

        private TestAssetWriter(String schema) {
            setSchemaId(schema);
            setProperty(BakedAssetConstant.PROP_VERSION, BakedAssetConstant.CURRENT_VERSION.toString());
        }

        private void animation(String name, BakedAnimationProto animation, int compression) throws IOException {
            addProtoChunk(name, animation, compression);
        }

        private void manifest(BakedAssetManifestProto manifest) throws IOException {
            addProtoChunk(BakedAssetConstant.MANIFEST_CHUNK_NAME, manifest, 0);
        }
    }
}
