package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerWriter;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.file.FileChunkDataSource;
import com.elfmcys.ysm.format.schema.file.ResidentChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ResolvedContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagedContainerResidencyTest {
    @TempDir
    static Path fixtureTemp;

    @TempDir
    Path temp;

    private static Path fixture;

    @BeforeAll
    static void createFixture() throws Exception {
        var manifest = ManagedContainerResidencyTest.class.getResource("/assets/ysm/builtin/default/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var vfs = new Directory(source)) {
            fixture = ModelParser.parseBuiltinDefault(vfs, Files.createDirectories(fixtureTemp.resolve("model")));
        }
    }

    @Test
    void defaultContainerOwnsAllChunkBytesWithoutAFileBacking() throws Exception {
        var handle = ManagedContainer.openResidentDefault(Files.readAllBytes(fixture), location("default"));
        var capturedChunks = handle.chunks();
        assertInstanceOf(ResidentChunkDataSource.class, capturedChunks);
        assertTrue(handle.residentOnly());
        assertThrows(IllegalStateException.class, handle::file);

        var target = handle.view().getRenderTargets().stream().filter(value -> !value.getTextureNames().isEmpty())
            .findFirst().orElseThrow();
        var textureName = target.getTextureNames().iterator().next();
        var capturedTexture = target.textureSources(capturedChunks, textureName).uv();
        assertSame(capturedChunks, handle.chunks());
        assertTrue(handle.representation().hasMetadataPrefix());

        try (var image = capturedTexture.open()) {
            assertTrue(image.data().size() > 0);
        }
        for (var chunk : handle.view().getFileView().getAssetView().getChunkTable().values()) {
            {
                var ignored = capturedChunks.readStoredVerified(chunk, BufferType.ARRAY);
                assertTrue(ignored.size() > 0);
            }
        }
    }

    @Test
    void ordinaryFileBackedHandlesRemainExactFileSources() throws Exception {
        var direct = openIndexed(copyFixture("direct.mxc"), location("direct"));
        var converted = openIndexed(copyFixture("converted.mxc"), location("converted"));

        for (var handle : new ManagedContainer[] {direct, converted}) {
            assertInstanceOf(FileChunkDataSource.class, handle.chunks());
            assertFalse(handle.residentOnly());
            assertTrue(Files.isSameFile(handle.file(), handle.location().path().value().equals("direct")
                ? temp.resolve("direct.mxc") : temp.resolve("converted.mxc")));
        }
    }

    @Test
    void fileRepresentationOwnsTheExactMetadataPrefix() throws Exception {
        var file = copyFixture("prefix.mxc");
        var handle = openIndexed(file, location("prefix"));
        var representation = handle.representation();
        var manifest =
            representation.view().getFileView().getAssetView().getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME);
        var expected = Arrays.copyOfRange(Files.readAllBytes(file), 0, manifest.offset() + manifest.size());

        {
            var prefix = representation.metadataPrefix().orElseThrow();
            try (var actual = prefix.copyArray()) {
                assertArrayEquals(expected,
                    Arrays.copyOfRange(actual.array(), actual.arrayOffset(), actual.arrayOffset() + actual.size()));

            }
        }
    }

    @Test
    void representationRejectsMismatchedModelAndContainerIds() throws Exception {
        var handle = openIndexed(copyFixture("identity.mxc"), location("identity"));
        var representation = handle.representation();
        {
            var prefix = representation.metadataPrefix().orElseThrow();
            assertThrows(IllegalArgumentException.class,
                () -> new ModelRepresentation(
                    new ModelFileIdentity(ModelId.parse("different/model"), representation.containerId()), prefix,
                    representation.view()));
            assertThrows(IllegalArgumentException.class,
                () -> new ModelRepresentation(
                    new ModelFileIdentity(representation.modelId(), different(representation.containerId())), prefix,
                    representation.view()));
        }
    }

    @Test
    void matchingPrefixSharesTheParsedGraphWithIndependentBufferOwnership() throws Exception {
        var file = copyFixture("unchanged.mxc");
        var previous = ManagedContainer.openDirect(file, location("unchanged"));
        var reloaded = ManagedContainer.openDirect(file, location("unchanged"), previous);
        assertNotSame(previous.representation(), reloaded.representation());
        assertSame(previous.view(), reloaded.view());
        assertSame(previous.view().getMetadata(), reloaded.view().getMetadata());
        assertSame(previous.view().getFileView().getAssetView(), reloaded.view().getFileView().getAssetView());
        reloaded.representation().close();
        {
            var retained = previous.representation().metadataPrefix().orElseThrow();
            assertTrue(retained.size() > 0);
        }
    }

    @Test
    void rebuiltEqualContentNormalizesAndDiscardsOnlyTheNewPrefixOwner() throws Exception {
        var file = copyFixture("equal.mxc");
        var previous = ManagedContainer.openDirect(file, location("equal"));
        var rebuilt = ManagedContainer.openDirect(file, location("equal"));
        assertNotSame(previous.view(), rebuilt.view());
        try (var outcome = new ResolvedContent(rebuilt, previous)) {
            assertSame(previous, outcome.content());
            assertThrows(IllegalStateException.class, () -> rebuilt.representation().metadataPrefix().orElseThrow());
        }
        {
            var retained = previous.representation().metadataPrefix().orElseThrow();
            assertTrue(retained.size() > 0);
        }
    }

    @Test
    void rejectedBorrowedContentAndAdoptedCreatedContentRemainUsable() throws Exception {
        var file = copyFixture("ownership.mxc");
        var previous = ManagedContainer.openDirect(file, location("ownership"));
        try (var rejected =
            new ResolvedContent(ManagedContainer.openDirect(file, location("ownership"), previous), previous)) {
            assertSame(previous, rejected.content());
        }
        {
            var retained = previous.representation().metadataPrefix().orElseThrow();
            assertTrue(retained.size() > 0);
        }
        var created = ManagedContainer.openDirect(file, location("created"));
        try (var adopted = new ResolvedContent(created, null)) {
            assertSame(created, adopted.adopt());
        }
        {
            var retained = created.representation().metadataPrefix().orElseThrow();
            assertTrue(retained.size() > 0);
        }
        var rejected = ManagedContainer.openDirect(file, location("rejected"));
        new ResolvedContent(rejected, null).close();
        assertThrows(IllegalStateException.class, () -> rejected.representation().metadataPrefix().orElseThrow());
    }

    @Test
    void directAndCachedMetadataValidateOnlyConsumedObjects() throws Exception {
        var valid = Files.readAllBytes(fixture);
        var asset = com.elfmcys.ysm.format.container.AssetContainerReader.readPreamble(valid);
        var manifest = asset.getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME);
        var later = asset.getChunkTable().values().stream()
            .filter(chunk -> chunk.offset() >= manifest.offset() + manifest.size()).findFirst().orElseThrow();
        try (var region = new CacheRegion(temp.resolve("cache"))) {
            var file = region.cache().path(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.CONVERTED, 100);
            Files.createDirectories(file.getParent());
            Files.write(file, valid);
            com.elfmcys.ysm.model.domain.ModelFileIdentity identity;
            try (var expected = ManagedContainer.openDirect(fixture, location("expected")).representation()) {
                identity = expected.identity();
            }
            var entry =
                new CatalogIndexEntry(identity, location("cached"), file, ContentStorageBinding.builtin(region));
            for (var corrupted : new int[] {0,
                asset.getChunkInfo(AssetContainerConstant.VERIFICATION_CHUNK_TYPE).offset(), manifest.offset(),
                later.offset()}) {
                var bytes = valid.clone();
                bytes[corrupted] ^= 1;
                Files.write(file, bytes);
                if (corrupted == later.offset()) {
                    for (var content : new ManagedContainer[] {ManagedContainer.openDirect(file, location("direct")),
                        ManagedContainer.openIndexed(entry)}) {
                        try (var representation = content.representation()) {
                            assertEquals(identity, representation.identity());
                            assertThrows(IOException.class,
                                () -> content.chunks().readStoredVerified(later, BufferType.ARRAY));
                            assertEquals(identity, representation.identity());
                        }
                    }
                } else {
                    assertThrows(IOException.class, () -> ManagedContainer.openDirect(file, location("direct")));
                    assertThrows(IOException.class, () -> ManagedContainer.openIndexed(entry));
                }
            }
            Files.write(file, Arrays.copyOf(valid, manifest.offset() + manifest.size()));
            var partial = ManagedContainer.openIndexed(entry);
            try (var representation = partial.representation()) {
                assertEquals(identity, representation.identity());
                assertThrows(IOException.class, () -> partial.chunks().readStoredVerified(later, BufferType.ARRAY));
            }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ysm.cacheReadProbe", matches = "true")
    void convertedReadsKeepCandidatesSeparateFromReadFailures() throws Exception {
        try (var region = new CacheRegion(temp.resolve("converted"));
            var representation = ManagedContainer.openDirect(fixture, location("source")).representation()) {
            var cache = region.cache();
            var objects = new ConvertedObjectStore(cache);
            var identity = representation.identity();
            Path target;
            com.elfmcys.ysm.natives.storage.CacheStorageTypes.Location token;
            try (var staging = objects.stage()) {
                staging.file().writeBytes(Files.readAllBytes(fixture));
                token = staging.location();
                target = staging.publish(file -> true, AtomicSharedCache.AtomicReplacement::run);
            }
            var entry = new ConvertedSourceIndex(identity, "custom/source", "test", token, target,
                com.elfmcys.ysm.buffer.ArrayBuffer.move(new byte[0]).shareArray(),
                representation.metadataPrefix().orElseThrow().shareArray());
            var indexes = new ConvertedSourceIndexStore(new ConvertedScope("test", "test"), cache);
            indexes.publish(entry);
            for (var failure : java.util.List.of("missing", "access", "io", "interrupted", "closed", "close",
                "cancelled", "oom", "defect")) {
                com.elfmcys.ysm.testutil.CacheReadFaults.set(target, failure);
                try {
                    assertEquals(entry, indexes.find("custom/source").orElseThrow());
                    switch (failure) {
                        case "missing", "access", "io" -> assertTrue(objects.findIdentity(entry).isEmpty());
                        case "cancelled" -> assertThrows(java.util.concurrent.CancellationException.class,
                            () -> objects.findIdentity(entry));
                        case "oom" -> assertThrows(OutOfMemoryError.class, () -> objects.findIdentity(entry));
                        case "defect" -> assertThrows(IllegalStateException.class, () -> objects.findIdentity(entry));
                        default -> assertThrows(IOException.class, () -> objects.findIdentity(entry));
                    }
                    assertEquals(identity, representation.identity());
                } finally {
                    com.elfmcys.ysm.testutil.CacheReadFaults.set(target, null);
                }
            }
            assertEquals(identity, objects.findIdentity(entry).orElseThrow());
        }
    }

    @Test
    void directoryAdmissionDefersUnreadPayloadAndLengthValidation() throws Exception {
        var file = copyFixture("corrupt.mxc");
        var previous = ManagedContainer.openDirect(file, location("corrupt"));
        var payload = previous.view().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(chunk -> chunk.offset() >= previous.representation().metadataPrefixSize()).findFirst()
            .orElseThrow();
        var bytes = Files.readAllBytes(file);
        bytes[payload.offset()] ^= 1;
        Files.write(file, bytes);

        var admitted = ManagedContainer.openDirect(file, location("corrupt"), previous);
        try (var ignored = admitted.representation()) {
            assertThrows(AssetLoadException.class,
                () -> admitted.chunks().readStoredVerified(payload, BufferType.ARRAY));
            assertThrows(AssetLoadException.class, () -> ManagedContainer.verifyFile(file));
        }
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        try (var partial = ManagedContainer.openDirect(file, location("corrupt"), previous).representation()) {
            assertEquals(previous.representation().identity(), partial.identity());
        }
        assertThrows(AssetLoadException.class, () -> ManagedContainer.verifyFile(file));
        {
            var retained = previous.representation().metadataPrefix().orElseThrow();
            assertTrue(retained.size() > 0);
        }
    }

    @Test
    void immutableDirectMetadataProofStillRequiresTheFreshPreamble() throws Exception {
        try (var region = new CacheRegion(temp.resolve("proof-cache"))) {
            var source = ManagedContainer.openDirect(fixture, location("source"));
            Path file;
            try (var representation = source.representation(); var preview = TestPreviews.blank()) {
                file = ModelExporter.export(source, preview, temp.resolve("proof.mxc"), null);
            }
            var first = region.directories().open(file, location("direct"), null);
            ModelFileIdentity identity;
            try (var representation = first.representation()) {
                identity = representation.identity();
            }
            var bytes = Files.readAllBytes(file);
            var preamble = com.elfmcys.ysm.format.container.AssetContainerReader.readPreamble(bytes);
            bytes[preamble.getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME).offset()] ^= 1;
            Files.write(file, bytes);
            var retained = region.directories().open(file, location("direct"), null);
            try (var representation = retained.representation()) {
                assertEquals(identity, representation.identity());
            }
            assertThrows(IOException.class, () -> ManagedContainer.openDirect(file, location("no-proof")));
            bytes[preamble.getChunkInfo(AssetContainerConstant.VERIFICATION_CHUNK_TYPE).offset()] ^= 1;
            Files.write(file, bytes);
            assertThrows(IOException.class, () -> region.directories().open(file, location("direct"), null));
        }
    }

    @Test
    void changedLayoutReusesOnlyTheEqualManifest() throws Exception {
        var file = copyFixture("original.mxc");
        var original = ManagedContainer.openDirect(file, location("original"));
        var output = temp.resolve("relayout.mxc");
        var asset = original.view().getFileView().getAssetView();
        try (var writer = new AssetContainerWriter();
            var channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.setSchema(asset.getSchema());
            writer.setSummary("A different preamble length changes all chunk offsets");
            for (var property : new short[] {ModelFileConstant.PROP_VENDOR, ModelFileConstant.PROP_VERSION,
                ModelFileConstant.PROP_MODEL_ID}) {
                writer.setSchemaProperty(property, asset.getSchemaProperty(property));
            }
            for (var chunk : asset.getChunkTable().values().stream()
                .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                .sorted(java.util.Comparator.comparingInt(AssetContainerView.ChunkInfo::offset)).toList()) {
                {
                    var bytes = original.chunks().readStoredVerified(chunk, BufferType.ARRAY);
                    writer.addStoredChunk(chunk.type(), chunk.encoding(), chunk.decodeSize(), chunk.alignmentShift(),
                        chunk.flags(), bytes, chunk.hash());
                }
            }
            writer.write(channel);
        }
        var changed = ManagedContainer.openDirect(output, location("changed"), original);

        assertEquals(original.modelId(), changed.modelId());
        assertNotSame(original.view(), changed.view());
        assertNotSame(original.view().getMetadata(), changed.view().getMetadata());
        assertNotSame(original.view().getFileView().getAssetView(), changed.view().getFileView().getAssetView());
        assertSame(original.view().getManifest(), changed.view().getManifest());
        {
            var texture = changed.chunks()
                .readStoredVerified(changed.view().getFileView().getAssetView().getChunkTable().values().stream()
                    .filter(chunk -> chunk.offset() >= changed.representation().metadataPrefixSize()).findFirst()
                    .orElseThrow(), BufferType.ARRAY);
            assertTrue(texture.size() > 0);
        }
    }

    private Path copyFixture(String name) throws Exception {
        return Files.copy(fixture, temp.resolve(name));
    }

    private static CatalogLocation location(String path) {
        return new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath(path));
    }

    private static ManagedContainer openIndexed(Path file, CatalogLocation location) throws Exception {
        final ModelFileIdentity identity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        return ManagedContainer.openIndexed(new CatalogIndexEntry(identity, location, file));
    }

    private static Hash256 different(Hash256 value) {
        var bytes = value.bytes();
        bytes[0] ^= 1;
        return new Hash256(bytes);
    }
}
