package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.benchmark.ReadHandleProbe;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import com.google.gson.GsonBuilder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separates reference preparation from measured production admission in independent JVMs. */
public final class DirectContainerReadSample {
    public static void main(String[] args) throws Exception {
        NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
        var file = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("prepare")) {
            prepare(file, Path.of(args[2]));
            return;
        }
        var location = new CatalogLocation(CatalogRootKind.MODELS, new ModelPath(file.getFileName().toString()));
        try (var cache = new AtomicSharedCache(Path.of(args[2]))) {
            var index = new DirectContainerIndex(cache);
            var phases = new LinkedHashMap<String, Object>();
            ManagedContainer previous = null;
            try {
                for (var phase : List.of(args[0], "reload")) {
                    ReadHandleProbe.clear();
                    long started = System.nanoTime();
                    var current = index.open(file, location, previous);
                    long elapsed = System.nanoTime() - started;
                    if (previous != null)
                        previous.representation().close();
                    previous = current;
                    var chunks = current.view().getFileView().getAssetView().getChunkTable().values().stream()
                        .map(chunk -> Map.of("type", chunk.type(), "offset", chunk.offset(), "bytes", chunk.size()))
                        .toList();
                    var handles = ReadHandleProbe.snapshot();
                    phases.put(phase,
                        Map.of("nanos", elapsed, "modelId", current.modelId().toString(), "containerId",
                            current.representation().identity().containerId().toString(), "chunks", chunks, "handles",
                            handles));
                }
            } finally {
                if (previous != null)
                    previous.representation().close();
            }
            Files.writeString(Path.of(args[3]), new GsonBuilder().setPrettyPrinting().create().toJson(phases));
        }
    }

    private static void prepare(Path source, Path target) throws Exception {
        var location = new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("reference"));
        var thumbnail = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        try (var verified = ManagedContainer.verifyFile(source)) {
            var content = ManagedContainer.openDirect(source, location);
            try (var writer = new ModelFileWriter(); var preview = PreviewStore.decode(thumbnail);
                var image = preview.open();
                var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var info = InfoProto.newBuilder(verified.view().getManifest().info())
                    .setThumbnailSource(PreviewSource.GENERATED).build();
                writer.setManifest(ManifestProto.newBuilder(verified.view().getManifest()).setInfo(info).build());
                for (var chunk : verified.view().getFileView().getAssetView().getChunkTable().values()) {
                    if (!chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                        && !chunk.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME)
                        && !chunk.type().equals(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME))
                        writer.addStoredChunk(chunk, content.chunks().readStoredVerified(chunk, BufferType.ARRAY));
                }
                writer.setThumbnail(image);
                writer.write(output);
            } finally {
                content.representation().close();
            }
        }
        try (var ignored = ManagedContainer.verifyFile(target)) {
        }
    }
}
