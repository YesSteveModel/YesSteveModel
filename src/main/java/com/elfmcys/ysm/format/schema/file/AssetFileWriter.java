package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.container.AssetContainerWriter;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.util.ProtoUtil;
import it.unimi.dsi.fastutil.shorts.ShortReferencePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import us.hebi.quickbuf.ProtoMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AssetFileWriter implements AutoCloseable {
    private String schemaId;
    private String summary = "";
    private final List<ShortReferencePair<String>> properties = new ArrayList<>(4);

    private final List<Triple<String, Integer, SharedBuffer>> rawList = new ArrayList<>(4);
    private final List<Pair<String, Image>> images = new ArrayList<>(4); // 不包含 texture
    private final List<BlobEntry> blobList = new ArrayList<>(4);
    private final List<StoredEntry> storedList = new ArrayList<>(4);

    protected AssetFileWriter() {}

    protected AssetFileWriter(AssetFileWriter source) {
        schemaId = source.schemaId;
        summary = source.summary;
        properties.addAll(source.properties);
        source.rawList
            .forEach(value -> rawList.add(Triple.of(value.getLeft(), value.getMiddle(), value.getRight().share())));
        source.images.forEach(value -> images.add(Pair.of(value.getLeft(), value.getRight().share())));
        source.blobList.forEach(value -> blobList
            .add(new BlobEntry(value.compressLevel(), value.encoding(), value.decodeSize(), value.data().share())));
        source.storedList.forEach(value -> storedList.add(new StoredEntry(value.type(), value.encoding(),
            value.decodeSize(), value.alignmentShift(), value.flags(), value.data().share(), value.hash().clone())));
    }

    protected void setSchemaId(String schemaId) {
        this.schemaId = schemaId;
    }

    protected void addProtoChunk(String type, ProtoMessage<?> value, int compressLevel) throws IOException {
        try (var bufScope = ArrayBuffer.allocateWithScope(value.getSerializedSize())) {
            value.writeTo(ProtoUtil.sink(bufScope.get()));
            rawList.add(Triple.of(type, compressLevel, SharedBuffer.adopt(bufScope.release())));
        }
    }

    protected void addRawChunk(String type, UniBuffer value, int compressLevel) {
        rawList.add(Triple.of(type, compressLevel, value.share()));
    }

    public void addStoredChunk(AssetContainerView.ChunkInfo chunk, UniBuffer stored) {
        Objects.requireNonNull(chunk, "chunk");
        if (chunk.hash() == null) {
            throw new IllegalArgumentException("Stored chunk has no logical hash");
        }
        storedList.add(new StoredEntry(chunk.type(), chunk.encoding(), chunk.decodeSize(), chunk.alignmentShift(),
            chunk.flags(), stored.share(), chunk.hash().clone()));
    }

    public int addProtoBlob(ProtoMessage<?> value, int compressLevel) throws IOException {
        try (var bufScope = ArrayBuffer.allocateWithScope(value.getSerializedSize())) {
            value.writeTo(ProtoUtil.sink(bufScope.get()));
            blobList.add(new BlobEntry(compressLevel, "", 0, SharedBuffer.adopt(bufScope.release())));
            return blobList.size();
        }
    }

    public int addBlob(UniBuffer buffer, int compressLevel) {
        blobList.add(new BlobEntry(compressLevel, "", 0, buffer.share()));
        return blobList.size();
    }

    public int nextBlobId() {
        return Math.addExact(blobList.size(), 1);
    }

    protected int addBorrowedBlob(ByteBuffer buffer) {
        blobList.add(new BlobEntry(0, "", 0, NativeBuffer.borrow(Objects.requireNonNull(buffer, "buffer"))));
        return blobList.size();
    }

    public int addImageBlob(Image image) {
        Objects.requireNonNull(image, "image");
        if (image.format() == Image.Format.RGBA) {
            throw new IllegalArgumentException("Image blob must use compressed storage");
        }
        blobList.add(new BlobEntry(0, image.format().name(), packImageSize(image), image.data()));
        return blobList.size();
    }

    protected void addImage(String type, Image image) {
        images.add(Pair.of(type, image.share()));
    }

    protected void setProperty(short type, String value) {
        properties.add(ShortReferencePair.of(type, value));
    }

    protected void setSummary(String summary) {
        this.summary = Objects.requireNonNull(summary, "summary");
    }

    public void write(WritableByteChannel output) throws IOException {
        try (var writer = new AssetContainerWriter()) {
            writer.setSchema(schemaId);
            writer.setSummary(summary);
            for (var prop : properties) {
                writer.setSchemaProperty(prop.leftShort(), prop.right());
            }
            for (var proto : rawList) {
                writer.addChunk(proto.getLeft(), "", 0, 0, 0, proto.getRight(), proto.getMiddle());
            }
            for (var stored : storedList) {
                writer.addStoredChunk(stored.type(), stored.encoding(), stored.decodeSize(), stored.alignmentShift(),
                    stored.flags(), stored.data(), stored.hash());
            }
            for (var img : images) {
                writer.addChunk(img.getKey(), img.getValue().format().name(), packImageSize(img.getRight()), 0, 0,
                    img.getValue().data(), 0);
            }
            for (var i = 0; i < blobList.size(); i++) {
                var blob = blobList.get(i);
                writer.addChunk(AssetFileConstant.BLOB_CHUNK_PREFIX + (i + 1), blob.encoding(), blob.decodeSize(), 0, 0,
                    blob.data(), blob.compressLevel());
            }
            writer.write(output);
        }
    }

    private int packImageSize(Image image) {
        return (image.width() << 16) | (image.height() & 0xFFFF);
    }

    @Override
    public void close() {
        rawList.clear();
        blobList.clear();
        storedList.clear();
        for (var pair : images) {
            pair.getRight().close();
        }
        images.clear();
    }

    private record BlobEntry(int compressLevel, String encoding, int decodeSize, UniBuffer data) {
    }

    private record StoredEntry(String type, String encoding, int decodeSize, int alignmentShift, int flags,
        SharedBuffer data, byte[] hash) {
    }
}
