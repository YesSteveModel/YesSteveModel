package com.elfmcys.ysm.mock.classpath;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Real-container input corpus shared by the two isolated verification endpoints. */
public final class MockScenarioFixture implements AutoCloseable {
    private final Map<String, Asset> assets;
    private final List<ModelRepresentation> detachedRepresentations = new ArrayList<>();

    public static MockScenarioFixture create(Path root) throws Exception {
        var assets = new LinkedHashMap<String, Asset>();
        assets.put("default", parse(root, "default", "/assets/ysm/builtin/default/ysm.json", true));
        assets.put("active-exact", parse(root, "active-exact", "/assets/ysm/builtin/misc/1_alex/ysm.json", false));
        assets.put("same-model-local",
            parse(root, "same-model-local", "/assets/ysm/builtin/misc/2_steve/ysm.json", false));
        assets.put("local-exact", parse(root, "local-exact", "/assets/ysm/builtin/misc/3_default_boy/ysm.json", false));
        assets.put("cache-exact",
            parse(root, "cache-exact", "/assets/ysm/builtin/misc/4_default_controllers/ysm.json", false));
        assets.put("server-exact",
            parse(root, "server-exact", "/assets/ysm/builtin/wine_fox/01_taisho_maid/ysm.json", false));
        assets.put("server-failure",
            parse(root, "server-failure", "/assets/ysm/builtin/wine_fox/02_new_year/ysm.json", false));
        assets.put("cache-failure",
            parse(root, "cache-failure", "/assets/ysm/builtin/wine_fox/03_astronaut/ysm.json", false));
        assets.put("same-model-remote", repack(root, assets.get("same-model-local"), "same-model-remote", (byte)'R'));
        assets.put("replacement-old", repack(root, assets.get("server-exact"), "replacement-old", (byte)'O'));
        assets.put("replacement-new", repack(root, assets.get("server-exact"), "replacement-new", (byte)'N'));
        return new MockScenarioFixture(Map.copyOf(assets));
    }

    private MockScenarioFixture(Map<String, Asset> assets) {
        this.assets = assets;
    }

    public Asset asset(String name) {
        var asset = assets.get(name);
        if (asset == null) {
            throw new IllegalArgumentException("Unknown classpath scenario asset: " + name);
        }
        return asset;
    }

    public CatalogRecord record(String name, String path, CatalogAccess access) {
        return record(asset(name), path, access, asset(name).content());
    }

    public CatalogRecord unavailableRecord(String name, String path, CatalogAccess access) {
        var asset = asset(name);
        var representation = asset.content().representation().withoutMetadataPrefix();
        detachedRepresentations.add(representation);
        ModelContent unavailable = new ModelContent() {
            @Override
            public ModelRepresentation representation() {
                return representation;
            }

            @Override
            public ChunkDataSource chunks() {
                return asset.content().chunks();
            }
        };
        return record(asset, path, access, unavailable);
    }

    public Map<String, AssetIdentity> inventory() throws Exception {
        var result = new LinkedHashMap<String, AssetIdentity>();
        assets.forEach((name, asset) -> result.put(name, new AssetIdentity(asset.identity().modelId().toString(),
            asset.identity().containerId().toString(), uncheckedHash(asset.file()))));
        return Map.copyOf(result);
    }

    private static String uncheckedHash(Path path) {
        try {
            return EvidenceJson.sha256(Files.readAllBytes(path));
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to hash scenario fixture " + path, failure);
        }
    }

    private static CatalogRecord record(Asset asset, String path, CatalogAccess access, ModelContent content) {
        var location = new CatalogLocation(
            access == CatalogAccess.AUTHORIZED ? CatalogRootKind.AUTH : CatalogRootKind.CUSTOM, new ModelPath(path));
        return new CatalogRecord(location, new CatalogContentBinding(asset.identity().modelId(), content));
    }

    private static Asset parse(Path root, String name, String resource, boolean builtin) throws Exception {
        var manifest = MockScenarioFixture.class.getResource(resource);
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        var output = Files.createDirectories(root.resolve(name));
        final Path file;
        try (var vfs = new Directory(source)) {
            file = builtin ? ModelParser.parseBuiltinDefault(vfs, output)
                : ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll());
        }
        var asset = open(name, file);
        if (builtin || asset.content().modelFile().getThumbnailPreviewSource() != PreviewSource.UNSPECIFIED) {
            return asset;
        }
        // Direct catalog inputs require an embedded preview; export timestamps would break cross-process identity.
        var portable = output.resolve(name + "-portable.mxc");
        try (var writer = new ModelFileWriter()) {
            var view = asset.content().modelFile();
            writer.setManifest(ManifestProto.newBuilder(view.getManifest())
                .setInfo(
                    InfoProto.newBuilder(view.getManifest().info()).setThumbnailSource(PreviewSource.GENERATED).build())
                .build());
            for (var chunk : view.getFileView().getAssetView().getChunkTable().values().stream()
                .sorted(Comparator.comparingInt(AssetContainerView.ChunkInfo::offset)).toList()) {
                if (chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                    || chunk.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME)
                    || chunk.type().equals(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME)) {
                    continue;
                }
                try (var stored = asset.content().chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                    writer.addStoredChunk(chunk, stored);
                }
            }
            var image = PreviewStore.decode(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
            try (var preview = image.open()) {
                writer.setThumbnail(preview);
            }
            try (var channel = FileChannel.open(portable, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write(channel);
            }
        } finally {
            asset.content().representation().close();
        }
        var result = open(name, portable);
        if (!result.identity().modelId().equals(asset.identity().modelId())) {
            throw new IllegalStateException("Fixture preview changed the model identity");
        }
        return result;
    }

    private static Asset repack(Path root, Asset source, String name, byte summaryMarker) throws Exception {
        var output = Files.createDirectories(root.resolve(name));
        var target = Files.copy(source.file(), output.resolve(name + ".mxc"), StandardCopyOption.REPLACE_EXISTING);
        var bytes = Files.readAllBytes(target);
        var summaryOffset = AssetContainerConstant.HEAD.length;
        if (bytes[summaryOffset] == 0) {
            throw new IllegalStateException("Scenario fixture requires a container summary");
        }
        bytes[summaryOffset] = bytes[summaryOffset] == summaryMarker ? (byte)(summaryMarker ^ 1) : summaryMarker;
        final AssetContainerView view;
        try (var channel = FileChannel.open(source.file(), StandardOpenOption.READ)) {
            view = AssetContainerReader.read(channel);
        }
        var verification = view.getChunkInfo(AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
        final byte[] containerHash;
        try (var hashInput = ArrayBuffer.borrow(bytes, 0, verification.offset())) {
            containerHash = Blake3.computeHash(hashInput);
        }
        System.arraycopy(containerHash, 0, bytes, verification.offset(), containerHash.length);
        Files.write(target, bytes);
        var repacked = open(name, target);
        if (!source.identity().modelId().equals(repacked.identity().modelId())
            || source.identity().containerId().equals(repacked.identity().containerId())) {
            throw new IllegalStateException("Repacked fixture must retain ModelId and replace ContainerId");
        }
        return repacked;
    }

    private static Asset open(String name, Path file) throws Exception {
        final ModelFileIdentity identity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath(name));
        return new Asset(name, file, identity,
            ManagedContainer.openIndexed(new CatalogIndexEntry(identity, location, file)));
    }

    @Override
    public void close() {
        detachedRepresentations.forEach(ModelRepresentation::close);
        assets.values().forEach(asset -> asset.content().representation().close());
    }

    public record Asset(String name, Path file, ModelFileIdentity identity, ManagedContainer content) {
    }

    public record AssetIdentity(String modelId, String containerId, String sha256) {
    }
}
