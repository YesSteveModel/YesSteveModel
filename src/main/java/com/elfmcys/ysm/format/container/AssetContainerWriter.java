package com.elfmcys.ysm.format.container;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.natives.Zstd;
import com.google.common.io.LittleEndianDataOutputStream;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceMap;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceMaps;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceOpenHashMap;
import net.minecraft.util.StringUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AssetContainerWriter implements AutoCloseable {
    private static final ByteBuffer ZERO =
        ByteBuffer.allocateDirect(1 << AssetContainerConstant.MAX_CHUNK_ALIGN_SHIFT).asReadOnlyBuffer();

    private byte[] summary = new byte[0];

    private long chunkDataSize = AssetContainerConstant.VERIFICATION_PAYLOAD_HEADER_SIZE;
    private final Short2ReferenceMap<String> schemaProperties = new Short2ReferenceOpenHashMap<>();
    private final Set<String> chunkTypes = new HashSet<>();
    private final List<Chunk> chunkList = new ArrayList<>();
    private String schema;

    public AssetContainerWriter() {
        chunkTypes.add(AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
    }

    public void setSummary(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > AssetContainerConstant.MAX_SUMMARY_SIZE) {
            throw new IllegalArgumentException("Summary size too large");
        }
        for (var b : bytes) {
            if (b == 0)
                throw new IllegalArgumentException("Summary utf-8 bytes contains \\0");
        }
        this.summary = bytes;
    }

    public void setSchema(String value) {
        this.schema = value;
    }

    public void setSchemaProperty(short type, String value) {
        if (type < 0) {
            throw new IllegalArgumentException("Type must be greater than or equal to 0");
        }
        if (schemaProperties.size() == Short.MAX_VALUE) {
            throw new IllegalArgumentException("Too many properties");
        }
        schemaProperties.put(type, value);
    }

    public void addChunk(String type, String encoding, int decodeSize, int alignmentShift, int flags, UniBuffer buffer,
        int compressLevel) {
        if (chunkTypes.size() == AssetContainerConstant.MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException("Too many chunks");
        }
        if (chunkTypes.contains(type)) {
            throw new IllegalArgumentException("Duplicated chunk");
        }
        if (alignmentShift < 0) {
            throw new IllegalArgumentException("AlignmentShift must not be less than 0");
        }
        if (alignmentShift > AssetContainerConstant.MAX_CHUNK_ALIGN_SHIFT) {
            throw new IllegalArgumentException(
                "AlignmentShift must not be greater than " + AssetContainerConstant.MAX_CHUNK_ALIGN_SHIFT);
        }
        byte[] hash = new byte[Blake3.HASH_SIZE];
        SharedBuffer storedBuffer;
        if (compressLevel > 0) {
            if (decodeSize != 0) {
                throw new IllegalArgumentException("Uncompressed chunk data decode size must be 0");
            }
            if (!StringUtil.isNullOrEmpty(encoding)) {
                throw new IllegalArgumentException("Uncompressed chunk data encoding must be null or empty");
            }
            encoding = "zstd";
            decodeSize = buffer.size();
            storedBuffer = SharedBuffer.adopt(Zstd.compressAndHash(buffer, hash, BufferType.NATIVE, compressLevel));
        } else {
            Blake3.computeHash(buffer, hash);
            storedBuffer = buffer.share();
        }
        if (chunkDataSize + storedBuffer.size() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Chunk data too large");
        }
        chunkTypes.add(type);
        chunkList.add(new Chunk(type, encoding, decodeSize, alignmentShift, flags, storedBuffer, hash));
        chunkDataSize += storedBuffer.size();
    }

    /** Adds an already verified stored representation without decoding and recompressing it. */
    public void addStoredChunk(String type, String encoding, int decodeSize, int alignmentShift, int flags,
        UniBuffer buffer, byte[] logicalHash) {
        if (chunkTypes.size() == AssetContainerConstant.MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException("Too many chunks");
        }
        if (chunkTypes.contains(type)) {
            throw new IllegalArgumentException("Duplicated chunk");
        }
        if (alignmentShift < 0 || alignmentShift > AssetContainerConstant.MAX_CHUNK_ALIGN_SHIFT) {
            throw new IllegalArgumentException("Stored chunk alignment is outside its bound");
        }
        if (logicalHash == null || logicalHash.length != Blake3.HASH_SIZE) {
            throw new IllegalArgumentException("Stored chunk has no full logical hash");
        }
        var stored = buffer.share();
        final long nextDataSize;
        try {
            nextDataSize = Math.addExact(chunkDataSize, stored.size());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Stored chunk data is outside its bound", overflow);
        }
        if (stored.size() <= 0 || nextDataSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Stored chunk data is outside its bound");
        }
        chunkTypes.add(type);
        chunkList.add(new Chunk(type, encoding, decodeSize, alignmentShift, flags, stored, logicalHash.clone()));
        chunkDataSize = nextDataSize;
    }

    @Override
    public void close() {
        chunkList.clear();
    }

    @SuppressWarnings("UnstableApiUsage")
    public void write(WritableByteChannel output) throws IOException {
        if (schema == null) {
            throw new IllegalArgumentException("Schema not set");
        }

        var verificationInputStream = new ByteArrayOutputStream();
        var writer = new LittleEndianDataOutputStream(verificationInputStream);

        // head + summary
        writer.write(AssetContainerConstant.HEAD);
        writer.write(summary);
        writer.write(0);

        // header
        writer.writeShort(AssetContainerConstant.HEADER_SIZE);
        writer.writeByte(AssetContainerConstant.CURRENT_MAJOR_VER);
        writer.writeShort(AssetContainerConstant.CURRENT_MINOR_VER);
        writer.writeShort(AssetContainerConstant.CURRENT_PATCH_VER);
        BinaryUtil.writeFixedString(writer, AssetContainerConstant.CURRENT_QUALIFIER_VER,
            AssetContainerConstant.HEADER_QUALIFIER_VERSION_SIZE);
        BinaryUtil.writeFixedString(writer, schema, AssetContainerConstant.HEADER_SCHEMA_SIZE);
        writer.writeShort(schemaProperties.size());
        writer.writeShort(chunkList.size() + 1);
        writer.writeInt(0); // schemaPropertyDataSize
        writer.writeInt(0); // chunkTableSize

        // schema property
        Short2ReferenceMaps.fastForEach(schemaProperties, entry -> {
            try {
                writer.writeShort(entry.getShortKey());
                BinaryUtil.writeShortString(writer, entry.getValue());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.flush();

        var headerOffset = AssetContainerConstant.HEAD.length + summary.length + 1;
        var schemaPropertyDataSize = verificationInputStream.size() - AssetContainerConstant.HEADER_SIZE - headerOffset;
        var schemaPropertySizeOffset = headerOffset + 2 + 1 + 2 + 2
            + AssetContainerConstant.HEADER_QUALIFIER_VERSION_SIZE + AssetContainerConstant.HEADER_SCHEMA_SIZE + 4;

        var verificationPayload = new byte[AssetContainerConstant.VERIFICATION_PAYLOAD_HEADER_SIZE];

        // verification chunk
        BinaryUtil.writeShortString(writer, AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
        BinaryUtil.writeShortString(writer, AssetContainerConstant.HASH_NAME); // encoding
        writer.writeInt(verificationPayload.length); // size
        writer.writeInt(0); // decode size
        writer.writeInt(0); // flags
        writer.writeByte(0); // alignment shift
        writer.write(new byte[AssetContainerConstant.HASH_SIZE]);

        // normal chunk
        for (var chunkInfo : chunkList) {
            BinaryUtil.writeShortString(writer, chunkInfo.type);
            BinaryUtil.writeShortString(writer, chunkInfo.encoding);
            writer.writeInt(chunkInfo.buffer.size());
            writer.writeInt(chunkInfo.decodeSize);
            writer.writeInt(chunkInfo.flags);
            writer.writeByte(chunkInfo.alignmentShift);
            writer.write(chunkInfo.hash);
        }

        var verificationInput = ByteBuffer.wrap(verificationInputStream.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        verificationInputStream.close();

        var alignList = calculateAlignSizeList(verificationPayload.length + verificationInput.remaining());
        var alignedChunkDataSize = chunkDataSize;
        for (var alignSize : alignList) {
            alignedChunkDataSize += alignSize;
        }

        if (verificationInput.remaining() + alignedChunkDataSize > AssetContainerConstant.MAX_FILE_SIZE) {
            throw new IOException("File size too large");
        }

        var chunkTableSize =
            verificationInput.remaining() - headerOffset - AssetContainerConstant.HEADER_SIZE - schemaPropertyDataSize;
        verificationInput.putInt(schemaPropertySizeOffset, schemaPropertyDataSize);
        verificationInput.putInt(schemaPropertySizeOffset + 4, chunkTableSize);
        var containerHash = new byte[AssetContainerConstant.HASH_SIZE];
        Blake3.computeHash(ArrayBuffer.borrow(verificationInput), containerHash);
        System.arraycopy(containerHash, 0, verificationPayload, 0, containerHash.length);

        writeFully(output, verificationInput);
        writeFully(output, ByteBuffer.wrap(verificationPayload));
        for (var i = 0; i < chunkList.size(); i++) {
            var chunk = chunkList.get(i);
            var alignSize = alignList.getInt(i);
            writeFully(output, ZERO.slice(0, alignSize));
            writeFully(output, chunk.buffer.nio());
        }
    }

    private static void writeFully(WritableByteChannel output, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            if (output.write(source) == 0) {
                throw new IOException("Container output made no write progress");
            }
        }
    }

    private IntList calculateAlignSizeList(int offset) {
        var list = new IntArrayList(chunkList.size());
        for (var chunk : chunkList) {
            var alignment = 1 << chunk.alignmentShift;
            var alignSize = (alignment - offset % alignment) % alignment;
            list.add(alignSize);
            offset += alignSize + chunk.buffer.size();
        }
        return list;
    }

    public record Chunk(String type, String encoding, int decodeSize, int alignmentShift, int flags,
        SharedBuffer buffer, byte[] hash) {
    }
}
