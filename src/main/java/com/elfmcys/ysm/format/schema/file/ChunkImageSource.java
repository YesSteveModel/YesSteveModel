package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageSource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Reads one immutable image chunk from a model file or the shared chunk cache. */
public final class ChunkImageSource implements ImageSource {
    private final @Nullable BooleanSupplier cancelled;
    private final ChunkDataSource source;
    private final AssetContainerView.ChunkInfo chunk;
    private final @Nullable Image.Format format;
    private final int width;
    private final int height;
    private final @Nullable String expectedEncoding;
    private final boolean chunkEncodingRequired;

    public static ChunkImageSource blob(ChunkDataSource source, AssetContainerView.ChunkInfo chunk, Image.Format format,
        int width, int height) throws IOException {
        var metadata = blobMetadata(chunk, format, width, height);
        return new ChunkImageSource(null, source, chunk, metadata.format(), metadata.width(), metadata.height(),
            format.name(), false);
    }

    public static ChunkImageSource blob(BooleanSupplier cancelled, ChunkDataSource source,
        AssetContainerView.ChunkInfo chunk, Image.Format format, int width, int height) throws IOException {
        var metadata = blobMetadata(chunk, format, width, height);
        return new ChunkImageSource(Objects.requireNonNull(cancelled, "cancelled"), source, chunk, metadata.format(),
            metadata.width(), metadata.height(), format.name(), false);
    }

    public static ChunkImageSource named(ChunkDataSource source, AssetContainerView.ChunkInfo chunk)
        throws IOException {
        var metadata = namedMetadata(chunk);
        return new ChunkImageSource(null, source, chunk, metadata.format(), metadata.width(), metadata.height(),
            metadata.format().name(), true);
    }

    public static ChunkImageSource named(BooleanSupplier cancelled, ChunkDataSource source,
        AssetContainerView.ChunkInfo chunk) throws IOException {
        var metadata = namedMetadata(chunk);
        return new ChunkImageSource(Objects.requireNonNull(cancelled, "cancelled"), source, chunk, metadata.format(),
            metadata.width(), metadata.height(), metadata.format().name(), true);
    }

    public static ChunkImageSource probed(ChunkDataSource source, AssetContainerView.ChunkInfo chunk,
        @Nullable String expectedEncoding) {
        var declaredEncoding =
            expectedEncoding != null ? expectedEncoding : chunk.encoding().isEmpty() ? null : chunk.encoding();
        return new ChunkImageSource(null, source, chunk, null, 0, 0, declaredEncoding, expectedEncoding != null);
    }

    private ChunkImageSource(@Nullable BooleanSupplier cancelled, ChunkDataSource source,
        AssetContainerView.ChunkInfo chunk, @Nullable Image.Format format, int width, int height,
        @Nullable String expectedEncoding, boolean chunkEncodingRequired) {
        this.cancelled = cancelled;
        this.source = Objects.requireNonNull(source, "source");
        this.chunk = copy(Objects.requireNonNull(chunk, "chunk"));
        this.format = format;
        this.width = width;
        this.height = height;
        this.expectedEncoding = expectedEncoding;
        this.chunkEncodingRequired = chunkEncodingRequired;
    }

    @Override
    public Image open() throws IOException {
        validateEncoding();
        var data = cancelled == null ? source.readPayload(chunk, BufferType.NATIVE)
            : source.readPayload(cancelled, chunk, BufferType.NATIVE);
        if (format != null) {
            return new Image(format, width, height, data);
        }
        try (data) {
            final Image image;
            try {
                image = Image.probe(data);
            } catch (IOException error) {
                throw AssetLoadException.content("Failed to probe image chunk: " + chunk.type(), error);
            }
            if (expectedEncoding != null && !image.format().name().equalsIgnoreCase(expectedEncoding)) {
                image.close();
                throw AssetLoadException.content("Image format does not match chunk encoding: " + chunk.type());
            }
            return image;
        }
    }

    private void validateEncoding() throws IOException {
        validateEncoding(chunk, expectedEncoding, chunkEncodingRequired);
    }

    static KnownImageMetadata blobMetadata(AssetContainerView.ChunkInfo chunk, Image.Format format, int width,
        int height) throws IOException {
        Objects.requireNonNull(format, "format");
        validateDimensions(chunk, width, height);
        validateEncoding(chunk, format.name(), false);
        return new KnownImageMetadata(format, width, height);
    }

    static KnownImageMetadata namedMetadata(AssetContainerView.ChunkInfo chunk) throws IOException {
        var encoding = chunk.encoding();
        if (encoding.isEmpty()) {
            throw AssetLoadException.content("Image chunk has no encoding: " + chunk.type());
        }
        final Image.Format format;
        try {
            format = Image.Format.valueOf(encoding.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw AssetLoadException.content(
                String.format(Locale.ROOT, "Unknown image encoding for chunk %s: %s", chunk.type(), encoding), error);
        }
        var width = (chunk.decodeSize() >>> 16) & 0xFFFF;
        var height = chunk.decodeSize() & 0xFFFF;
        validateDimensions(chunk, width, height);
        return new KnownImageMetadata(format, width, height);
    }

    private static void validateDimensions(AssetContainerView.ChunkInfo chunk, int width, int height)
        throws IOException {
        if (width <= 0 || height <= 0) {
            throw AssetLoadException.content(String.format(Locale.ROOT, "Invalid image dimensions for chunk %s: %dx%d",
                chunk.type(), width, height));
        }
    }

    private static void validateEncoding(AssetContainerView.ChunkInfo chunk, @Nullable String expectedEncoding,
        boolean required) throws IOException {
        var actualEncoding = chunk.encoding();
        if (actualEncoding.isEmpty()) {
            if (required) {
                throw AssetLoadException.content("Image chunk has no encoding: " + chunk.type());
            }
            return;
        }
        if (expectedEncoding != null && !actualEncoding.equalsIgnoreCase(expectedEncoding)) {
            throw AssetLoadException
                .content(String.format(Locale.ROOT, "Image encoding conflict for chunk %s: expected %s, found %s",
                    chunk.type(), expectedEncoding, actualEncoding));
        }
    }

    private static AssetContainerView.ChunkInfo copy(AssetContainerView.ChunkInfo value) {
        var hash = value.hash();
        return new AssetContainerView.ChunkInfo(value.type(), value.encoding(), value.offset(), value.size(),
            value.decodeSize(), value.flags(), value.alignSize(), value.alignmentShift(),
            hash == null ? null : Arrays.copyOf(hash, hash.length));
    }

    @Override
    public String toString() {
        return "chunk " + chunk.type();
    }

    record KnownImageMetadata(Image.Format format, int width, int height) {
    }
}
