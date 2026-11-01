package com.elfmcys.ysm.model.storage;

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
                    new ModelFileIdentity(different(representation.modelId()), representation.containerId()), prefix,
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
    void identicalPrefixDoesNotAdmitCorruptPayloadOrWrongFileLength() throws Exception {
        var file = copyFixture("corrupt.mxc");
        var previous = ManagedContainer.openDirect(file, location("corrupt"));
        var payload = previous.view().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(chunk -> chunk.offset() >= previous.representation().metadataPrefixSize()).findFirst()
            .orElseThrow();
        var bytes = Files.readAllBytes(file);
        bytes[payload.offset()] ^= 1;
        Files.write(file, bytes);

        assertThrows(AssetLoadException.class, () -> ManagedContainer.openDirect(file, location("corrupt"), previous));
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(AssetLoadException.class, () -> ManagedContainer.openDirect(file, location("corrupt"), previous));
        {
            var retained = previous.representation().metadataPrefix().orElseThrow();
            assertTrue(retained.size() > 0);
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
