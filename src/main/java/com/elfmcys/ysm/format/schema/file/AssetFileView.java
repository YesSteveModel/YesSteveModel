package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.util.ProtoUtil;
import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;

public class AssetFileView {
    private final AssetContainerView assetView;

    public AssetFileView(AssetContainerView assetView) {
        this.assetView = assetView;
    }

    public Image readImageBlob(BooleanSupplier cancelled, ChunkDataSource source, ImageProto img) throws IOException {
        var chunk = blobChunkInfo(img.blobId());
        if (chunk == null) {
            throw AssetLoadException.content("Image blob " + img.blobId() + " not found");
        }
        var metadata =
            ChunkImageSource.blobMetadata(chunk, imageFormat(img.format(), chunk.type()), img.width(), img.height());
        return new Image(metadata.format(), metadata.width(), metadata.height(),
            source.readPayload(cancelled, chunk, BufferType.NATIVE));
    }

    public ImageSource imageBlobSource(ChunkDataSource source, ImageProto image) throws IOException {
        var chunk = blobChunkInfo(image.blobId());
        if (chunk == null) {
            throw AssetLoadException.content("Image blob " + image.blobId() + " not found");
        }
        var format = imageFormat(image.format(), chunk.type());
        return ChunkImageSource.blob(source, chunk, format, image.width(), image.height());
    }

    public ImageSource imageBlobSource(BooleanSupplier cancelled, ChunkDataSource source, ImageProto image)
        throws IOException {
        var chunk = blobChunkInfo(image.blobId());
        if (chunk == null) {
            throw AssetLoadException.content("Image blob " + image.blobId() + " not found");
        }
        var format = imageFormat(image.format(), chunk.type());
        return ChunkImageSource.blob(cancelled, source, chunk, format, image.width(), image.height());
    }

    public @Nullable ImageSource imageChunkSource(ChunkDataSource source, String chunkType) throws IOException {
        var chunk = assetView.getChunkInfo(chunkType);
        if (chunk == null) {
            return null;
        }
        return ChunkImageSource.named(source, chunk);
    }

    public @Nullable ImageSource imageChunkSource(BooleanSupplier cancelled, ChunkDataSource source, String chunkType)
        throws IOException {
        var chunk = assetView.getChunkInfo(chunkType);
        if (chunk == null) {
            return null;
        }
        return ChunkImageSource.named(cancelled, source, chunk);
    }

    public AssetContainerView getAssetView() {
        return assetView;
    }

    public @Nullable AssetContainerView.ChunkInfo blobChunkInfo(int id) {
        return assetView.getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX + Integer.toUnsignedLong(id));
    }

    public PBRImageSources textureSources(ChunkDataSource source, PBRTextureSetProto texture) throws IOException {
        var uv = imageBlobSource(source, texture.uv());
        var normal = texture.hasNormal() ? imageBlobSource(source, texture.normalUnsafe()) : null;
        var specular = texture.hasSpecular() ? imageBlobSource(source, texture.specularUnsafe()) : null;
        return new PBRImageSources(uv, normal, specular);
    }

    public PBRImageSources textureSources(BooleanSupplier cancelled, ChunkDataSource source, PBRTextureSetProto texture)
        throws IOException {
        var uv = imageBlobSource(cancelled, source, texture.uv());
        var normal = texture.hasNormal() ? imageBlobSource(cancelled, source, texture.normalUnsafe()) : null;
        var specular = texture.hasSpecular() ? imageBlobSource(cancelled, source, texture.specularUnsafe()) : null;
        return new PBRImageSources(uv, normal, specular);
    }

    public SharedBuffer readPayload(BooleanSupplier cancelled, ChunkDataSource source, String type,
        BufferType outputType) throws IOException {
        var chunkInfo = assetView.getChunkInfo(type);
        if (chunkInfo == null) {
            throw AssetLoadException.content("Chunk " + type + " not found");
        }
        return source.readPayload(cancelled, chunkInfo, outputType);
    }

    public SharedBuffer readBlobPayload(BooleanSupplier cancelled, ChunkDataSource source, int id,
        BufferType outputType) throws IOException {
        return readPayload(cancelled, source, AssetFileConstant.BLOB_CHUNK_PREFIX + Integer.toUnsignedLong(id),
            outputType);
    }

    public <D extends ProtoMessage<D>> D readProtoBlob(BooleanSupplier cancelled, ChunkDataSource source, int blobId,
        ProtoReader<D> reader) throws IOException {
        var buffer = readBlobPayload(cancelled, source, blobId, BufferType.ARRAY);
        if (!(buffer instanceof ArrayBuffer arrayBuffer)) {
            throw new IllegalStateException("Array chunk read returned a non-array buffer");
        }
        try {
            return reader.readProto(ProtoUtil.source(arrayBuffer));
        } catch (IOException error) {
            throw AssetLoadException.content("Failed to decode protobuf blob " + blobId, error);
        } finally {
            java.lang.ref.Reference.reachabilityFence(buffer);
        }
    }

    public @Nullable Image readImageChunk(BooleanSupplier cancelled, ChunkDataSource source, String chunkType)
        throws IOException {
        var chunkInfo = assetView.getChunkInfo(chunkType);
        if (chunkInfo == null) {
            return null;
        }
        var metadata = ChunkImageSource.namedMetadata(chunkInfo);
        return new Image(metadata.format(), metadata.width(), metadata.height(),
            readPayload(cancelled, source, chunkType, BufferType.NATIVE));
    }

    private static Image.Format imageFormat(String encoding, String chunkType) throws IOException {
        if (encoding.isEmpty()) {
            throw AssetLoadException.content("Image protobuf has no format for chunk " + chunkType);
        }
        try {
            return Image.Format.valueOf(encoding.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw AssetLoadException.content("Unknown image protobuf format for chunk " + chunkType + ": " + encoding,
                error);
        }
    }

    @FunctionalInterface
    public interface ProtoReader<D extends ProtoMessage<D>> {
        D readProto(ProtoSource proto) throws IOException;
    }
}
