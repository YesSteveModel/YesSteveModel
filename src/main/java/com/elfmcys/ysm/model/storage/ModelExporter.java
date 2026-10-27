package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExportInfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** Produces one fully verified, atomically committed portable model artifact. */
public final class ModelExporter {
    private ModelExporter() {}

    public static Path export(ManagedContainer source, PreviewStore.EncodedPreview preview, Path output, String extra)
        throws IOException {
        return export(source, preview, output, extra, AtomicSharedCache.AtomicReplacement::run);
    }

    public static Path export(ManagedContainer source, PreviewStore.EncodedPreview preview, Path output, String extra,
        AtomicSharedCache.CommitGate gate) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(preview, "preview");
        output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        var temporary = output
            .resolveSibling(output.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID());
        try {
            write(source, preview, temporary, extra);
            verify(source, preview, temporary);
            var destination = output;
            gate.commit(() -> java.nio.file.Files.move(temporary, destination,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING));
            return output;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void write(ManagedContainer source, PreviewStore.EncodedPreview preview, Path temporary,
        String extra) throws IOException {
        var view = source.modelFile();
        var thumbnail = view.getFileView().getAssetView().getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        var preserveThumbnail = thumbnail != null && view.getThumbnailPreviewSource() != PreviewSource.UNSPECIFIED;
        var info = InfoProto.newBuilder(view.getManifest().info()).setExport(ExportInfoProto.newBuilder()
            .setTimestamp(Instant.now().getEpochSecond()).setExtra(Objects.requireNonNullElse(extra, "")).build());
        if (!preserveThumbnail) {
            info.setThumbnailSource(PreviewSource.GENERATED);
        }
        var manifest = ManifestProto.newBuilder(view.getManifest()).setInfo(info.build()).build();
        try (var writer = new ModelFileWriter()) {
            writer.setManifest(manifest);
            var chunks = view.getFileView().getAssetView().getChunkTable().values().stream()
                .filter(chunk -> !chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                .filter(chunk -> !chunk.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME))
                .filter(chunk -> preserveThumbnail || !chunk.type().equals(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME))
                .sorted(Comparator.comparingInt(AssetContainerView.ChunkInfo::offset)).toList();
            for (var chunk : chunks) {
                try (var stored = source.chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                    writer.addStoredChunk(chunk, stored);
                }
            }
            if (!preserveThumbnail) {
                try (var image = preview.open()) {
                    writer.setThumbnail(image);
                }
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write(channel);
            }
        }
    }

    private static void verify(ManagedContainer source, PreviewStore.EncodedPreview preview, Path temporary)
        throws IOException {
        var checked = ManagedContainer.openDirect(temporary, source.location());
        try {
            if (!checked.representation().modelId().equals(source.representation().modelId())) {
                throw new IOException("Export changed the model identity");
            }
            var original =
                source.modelFile().getFileView().getAssetView().getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
            var staged = checked.modelFile().getFileView().getAssetView()
                .getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
            if (staged == null || checked.modelFile().getThumbnailPreviewSource() == PreviewSource.UNSPECIFIED)
                throw new IOException("Export has no preview");
            var preserve =
                original != null && source.modelFile().getThumbnailPreviewSource() != PreviewSource.UNSPECIFIED;
            if (preserve && (!java.util.Arrays.equals(original.hash(), staged.hash())
                || !original.encoding().equals(staged.encoding()) || original.decodeSize() != staged.decodeSize()))
                throw new IOException("Export changed the preserved preview");
            try (var image = checked.modelFile().thumbnailSource(checked.chunks()).open()) {
                if (!preview.matches(image)) {
                    if (!preserve)
                        throw new IOException("Export preview differs from its verified content");
                    // A caller may supply a different image while the artifact preserves its own thumbnail.
                    PreviewStore.validate(image::share, staged.size());
                }
            }
            checked.modelFile().getCommon().validateSoundContent(() -> false, checked.chunks());
        } finally {
            checked.representation().close();
        }
    }

}
