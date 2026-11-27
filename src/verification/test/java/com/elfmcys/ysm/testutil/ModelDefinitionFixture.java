package com.elfmcys.ysm.testutil;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

public final class ModelDefinitionFixture {
    private ModelDefinitionFixture() {}

    public static void rewritePlayerDefinition(ManagedContainer source, ModelDataProto replacement, Path output)
        throws IOException {
        class DefinitionWriter extends ModelFileWriter {
            void replace(String chunk) throws IOException {
                addProtoChunk(chunk, replacement, 0);
            }
        }
        var model = source.modelFile();
        var view = model.getFileView();
        var toReplace = view.blobChunkInfo(model.getPlayer().descriptor().blobId());
        var chunks = view.getAssetView().getChunkTable().values().stream()
            .sorted(Comparator.comparingInt(AssetContainerView.ChunkInfo::offset)).toList();
        try (var writer = new DefinitionWriter();
            var channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.setManifest(model.getManifest());
            for (var chunk : chunks) {
                var type = chunk.type();
                if (type.equals(toReplace.type())) {
                    writer.replace(type);
                } else if (!type.equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                    && !type.equals(ModelFileConstant.MANIFEST_CHUNK_NAME)) {
                    writer.addStoredChunk(chunk, source.chunks().readStoredVerified(chunk, BufferType.ARRAY));
                }
            }
            writer.write(channel);
        }
    }
}
