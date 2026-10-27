package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.AssetContainerWriter;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkImageSourceTest {
    private static final byte[] PNG_BYTES = Base64.getDecoder()
        .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @TempDir
    Path tempDir;

    @Test
    void opensBlobImageWithoutRetainingThePreviousBuffer() throws IOException {
        var bytes = new byte[] {1, 2, 3, 4};
        var chunk = new AssetContainerView.ChunkInfo("blob.1", "rgba", 0, bytes.length, bytes.length, 0, 0, 0, null);
        var source = ChunkImageSource.blob(dataSource(bytes), chunk, Image.Format.RGBA, 1, 1);

        try (var first = source.open(); var second = source.open()) {
            assertEquals(Image.Format.RGBA, first.format());
            assertEquals(1, first.width());
            assertEquals(1, first.height());
            var actual = new byte[first.data().size()];
            first.data().nio().get(actual);
            assertArrayEquals(bytes, actual);
            assertEquals(bytes.length, second.data().size());
        }
    }

    @Test
    void contextualNamedAndBlobSourcesCarryTheExactContextIntoOpen() throws IOException {
        var seen = new ArrayList<BooleanSupplier>();
        var reads = new AtomicInteger();
        var chunks = new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                return ArrayBuffer.move(new byte[] {1, 2, 3, 4});
            }

            @Override
            public UniBuffer readPayload(BooleanSupplier cancelled, AssetContainerView.ChunkInfo chunk,
                BufferType bufferType) {
                seen.add(cancelled);
                reads.incrementAndGet();
                return readPayload(chunk, bufferType);
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                return readPayload(chunk, bufferType);
            }
        };
        var namedChunk = new AssetContainerView.ChunkInfo("named-image", "RGBA", 0, 4, (1 << 16) | 1, 0, 0, 0, null);
        var blobChunk = new AssetContainerView.ChunkInfo("blob.1", "", 0, 4, 4, 0, 0, 0, null);

        BooleanSupplier cancelled = () -> false;
        try (var named = ChunkImageSource.named(cancelled, chunks, namedChunk).open();
            var blob = ChunkImageSource.blob(cancelled, chunks, blobChunk, Image.Format.RGBA, 1, 1).open()) {
            assertEquals(List.of(cancelled, cancelled), seen);
            assertEquals(2, reads.get());
        }
    }

    @Test
    void contextualTextureSourcesCarryTheExactContextIntoEveryComponent() throws IOException {
        var file = tempDir.resolve("pbr-texture.mxc");
        final int uv;
        final int normal;
        final int specular;
        try (var writer = new TestAssetFileWriter(); var data = ArrayBuffer.move(new byte[] {1, 2, 3, 4})) {
            uv = writer.addBlob(data, 0);
            normal = writer.addBlob(data, 0);
            specular = writer.addBlob(data, 0);
            write(writer, file);
        }

        var view = readView(file);
        var delegate = new FileChunkDataSource(file);
        var seen = new ArrayList<BooleanSupplier>();
        var chunks = new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
                return delegate.readPayload(chunk, bufferType);
            }

            @Override
            public UniBuffer readPayload(BooleanSupplier cancelled, AssetContainerView.ChunkInfo chunk,
                BufferType bufferType) throws IOException {
                seen.add(cancelled);
                return delegate.readPayload(chunk, bufferType);
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                throws IOException {
                return delegate.readStoredVerified(chunk, bufferType);
            }
        };
        var texture = PBRTextureSetProto.newBuilder().setUv(image(uv)).setNormal(image(normal))
            .setSpecular(image(specular)).build();

        BooleanSupplier cancelled = () -> false;
        var sources = view.textureSources(cancelled, chunks, texture);
        try (var uvImage = sources.uv().open(); var normalImage = sources.normal().open();
            var specularImage = sources.specular().open()) {
            assertEquals(List.of(cancelled, cancelled, cancelled), seen);
        }
    }

    @Test
    void cancelledContextRejectsImageOpenBeforeReading() throws IOException {
        var reads = new AtomicInteger();
        var chunks = new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                reads.incrementAndGet();
                return ArrayBuffer.move(new byte[] {1, 2, 3, 4});
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                return readPayload(chunk, bufferType);
            }
        };
        var chunk = new AssetContainerView.ChunkInfo("named-image", "RGBA", 0, 4, (1 << 16) | 1, 0, 0, 0, null);
        var cancelled = new AtomicBoolean(true);
        var source = ChunkImageSource.named(cancelled::get, chunks, chunk);

        assertThrows(CancellationException.class, source::open);
        assertEquals(0, reads.get());
    }

    @Test
    void cancellationAfterSynchronousReadClosesTheReturnedBuffer() {
        var cancelled = new AtomicBoolean();
        var returned = new AtomicReference<UniBuffer>();
        var chunks = new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                var buffer = ArrayBuffer.move(new byte[] {1, 2, 3, 4});
                returned.set(buffer);
                cancelled.set(true);
                return buffer;
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                throw new AssertionError("unused");
            }
        };
        var chunk = new AssetContainerView.ChunkInfo("blob.1", "", 0, 4, 4, 0, 0, 0, null);

        assertThrows(CancellationException.class, () -> chunks.readPayload(cancelled::get, chunk, BufferType.ARRAY));
        assertThrows(IllegalStateException.class, () -> returned.get().nio());
    }

    @Test
    void rejectsConflictingBlobEncodingBeforeReading() {
        var chunk = new AssetContainerView.ChunkInfo("blob.1", "png", 0, 4, 4, 0, 0, 0, null);

        var error = assertThrows(IOException.class,
            () -> ChunkImageSource.blob(dataSource(new byte[4]), chunk, Image.Format.RGBA, 1, 1));
        assertTrue(error.getMessage().contains("expected RGBA, found png"));
    }

    @Test
    void rejectsUnknownNamedImageEncoding() {
        var chunk = new AssetContainerView.ChunkInfo("named-image", "tiff", 0, 4, (1 << 16) | 1, 0, 0, 0, null);

        var error = assertThrows(IOException.class, () -> ChunkImageSource.named(dataSource(new byte[4]), chunk));
        assertTrue(error.getMessage().contains("Unknown image encoding"));
    }

    @Test
    void rejectsCorruptProbedImage() {
        var chunk = new AssetContainerView.ChunkInfo("external-image", "png", 0, 4, 4, 0, 0, 0, null);
        var source = ChunkImageSource.probed(dataSource(new byte[4]), chunk, "PNG");

        var error = assertThrows(AssetLoadException.class, source::open);
        assertEquals(AssetLoadException.Reason.CONTENT, error.reason());
        assertEquals("Failed to read image", error.getCause().getMessage());
    }

    @Test
    void rejectsProbedBytesThatConflictWithChunkEncoding() {
        var chunk = new AssetContainerView.ChunkInfo("external-image", "JPEG", 0, PNG_BYTES.length, PNG_BYTES.length, 0,
            0, 0, null);
        var source = ChunkImageSource.probed(dataSource(PNG_BYTES), chunk, null);

        var error = assertThrows(IOException.class, source::open);
        assertTrue(error.getMessage().contains("does not match chunk encoding"));
    }

    @Test
    void unwrapsChunkReadFailuresAsIoErrors() throws IOException {
        var chunk = new AssetContainerView.ChunkInfo("blob.1", "rgba", 0, 4, 4, 0, 0, 0, null);
        var source = ChunkImageSource.blob(failingDataSource(), chunk, Image.Format.RGBA, 1, 1);

        assertEquals("read failed", assertThrows(IOException.class, source::open).getMessage());
    }

    @Test
    void roundTripsBlobImageWithProtoMetadataAndEmptyChunkEncoding() throws IOException {
        var file = tempDir.resolve("blob.mxc");
        var bytes = new byte[] {1, 2, 3, 4};
        final int blobId;
        try (var writer = new TestAssetFileWriter(); var data = ArrayBuffer.move(bytes.clone())) {
            blobId = writer.addBlob(data, 0);
            write(writer, file);
        }

        var view = readView(file);
        var chunk = view.getAssetView().getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX + blobId);
        assertNotNull(chunk);
        assertEquals("", chunk.encoding());
        var descriptor = ImageProto.newBuilder().setBlobId(blobId).setFormat("RGBA").setWidth(1).setHeight(1)
            .setFrameCount(1).build();

        try (var image = view.imageBlobSource(new FileChunkDataSource(file), descriptor).open()) {
            assertEquals(Image.Format.RGBA, image.format());
            assertArrayEquals(bytes, imageBytes(image));
        }
    }

    @Test
    void roundTripsNamedImageWithChunkMetadata() throws IOException {
        var file = tempDir.resolve("named.mxc");
        var bytes = new byte[] {1, 2, 3, 4};
        try (var writer = new TestAssetFileWriter();
            var image = new Image(Image.Format.RGBA, 1, 1, ArrayBuffer.move(bytes.clone()))) {
            writer.addNamedImage("named-image", image);
            write(writer, file);
        }

        var view = readView(file);
        var chunk = view.getAssetView().getChunkInfo("named-image");
        assertNotNull(chunk);
        assertEquals("RGBA", chunk.encoding());
        var source = view.imageChunkSource(new FileChunkDataSource(file), "named-image");
        assertNotNull(source);

        try (var image = source.open()) {
            assertEquals(Image.Format.RGBA, image.format());
            assertEquals(1, image.width());
            assertEquals(1, image.height());
            assertArrayEquals(bytes, imageBytes(image));
        }
    }

    @Test
    void roundTripsProbedImageAndChecksExternalEncoding() throws IOException {
        var file = tempDir.resolve("probed.mxc");
        try (var writer = new AssetContainerWriter(); var data = ArrayBuffer.move(PNG_BYTES.clone())) {
            writer.setSchema("test/images");
            writer.addChunk("external-image", "PNG", PNG_BYTES.length, 0, 0, data, 0);
            try (var output = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
                writer.write(output);
            }
        }

        final AssetContainerView container;
        try (var input = FileChannel.open(file, StandardOpenOption.READ)) {
            container = AssetContainerReader.read(input);
        }
        var chunk = container.getChunkInfo("external-image");
        assertNotNull(chunk);
        var source = ChunkImageSource.probed(new FileChunkDataSource(file), chunk, "png");

        try (var image = source.open()) {
            assertEquals(Image.Format.PNG, image.format());
            assertEquals(1, image.width());
            assertEquals(1, image.height());
        }
    }

    private static byte[] imageBytes(Image image) {
        var bytes = new byte[image.data().size()];
        image.data().nio().get(bytes);
        return bytes;
    }

    private static ImageProto image(int blobId) {
        return ImageProto.newBuilder().setBlobId(blobId).setFormat("RGBA").setWidth(1).setHeight(1).setFrameCount(1)
            .build();
    }

    private static void write(AssetFileWriter writer, Path file) throws IOException {
        try (var output = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
            writer.write(output);
        }
    }

    private static AssetFileView readView(Path file) throws IOException {
        try (var input = FileChannel.open(file, StandardOpenOption.READ)) {
            return new AssetFileView(AssetContainerReader.read(input));
        }
    }

    private static ChunkDataSource dataSource(byte[] bytes) {
        return new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                return ArrayBuffer.move(bytes.clone());
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                return ArrayBuffer.move(bytes.clone());
            }
        };
    }

    private static ChunkDataSource failingDataSource() {
        return new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
                throw new IOException("read failed");
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                throws IOException {
                throw new IOException("read failed");
            }
        };
    }

    private static final class TestAssetFileWriter extends AssetFileWriter {
        private TestAssetFileWriter() {
            setSchemaId("test/images");
            setSummary("");
        }

        private void addNamedImage(String type, Image image) {
            addImage(type, image);
        }
    }
}
