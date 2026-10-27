package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ExportTestFixture {
    private ExportTestFixture() {}

    public static Path parse(Path root) throws Exception {
        var directory = Path.of(Objects
            .requireNonNull(ExportTestFixture.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json")).toURI())
            .getParent();
        try (var vfs = new Directory(directory)) {
            return ModelParser.parse(vfs, Files.createDirectories(root), DefaultAnimationFilter.keepAll());
        }
    }

    public static ManagedContainer create(Path base, Path output, String path, int marker, PreviewSource preview,
        CatalogRootKind kind) throws Exception {
        var location = new CatalogLocation(kind, new ModelPath(path));
        var source = ManagedContainer.openDirect(base, location);
        try (var writer = new ModelFileWriter()) {
            var id = new byte[32];
            id[0] = (byte)marker;
            var info = source.modelFile().getManifest().info().toBuilder();
            info.setProperties(info.build().properties().toBuilder().setModelId(ByteBuffer.wrap(id)).build());
            info.setThumbnailSource(preview);
            writer.setManifest(source.modelFile().getManifest().toBuilder().setInfo(info.build()).build());
            for (var chunk : source.modelFile().getFileView().getAssetView().getChunkTable().values()) {
                if (chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                    || chunk.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME)
                    || chunk.type().equals(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME))
                    continue;
                try (var stored = source.chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                    writer.addStoredChunk(chunk, stored);
                }
            }
            if (preview != PreviewSource.UNSPECIFIED) {
                try (var encoded = TestPreviews.blank(); var image = encoded.open()) {
                    writer.setThumbnail(image);
                }
            }
            Files.createDirectories(output.getParent());
            try (var channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write(channel);
            }
        } finally {
            source.representation().close();
        }
        return ManagedContainer.openDirect(output, location);
    }

    public static ServerCatalog catalog(List<ManagedContainer> content) {
        return new ServerCatalog(new CatalogSnapshot(
            content.stream()
                .collect(Collectors.toMap(value -> value.representation().modelId(),
                    value -> new CatalogRecord(value.location(),
                        new CatalogContentBinding(value.representation().modelId(), value)))),
            List.of(), ModelScanReport.empty()));
    }

    public static ModelExportService.PreparedExport prepare(ModelExportService.Request request, PreviewStore store,
        Path output) throws Exception {
        return ModelExportService.prepare(request, store, output);
    }
}
