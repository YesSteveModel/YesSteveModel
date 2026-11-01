package com.elfmcys.ysm.format.container;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.version.VersionCompatibility;
import com.google.common.io.LittleEndianDataInputStream;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceMaps;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceOpenHashMap;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.SerializationException;

/**
 * Parses and validates container metadata. Chunk payload I/O belongs to ChunkDataSource implementations.
 *
 * <p>Ensures: normal return establishes supported container version, table structure, and preamble
 * verification. It does not establish schema semantics, payload availability, or ordinary chunk hashes.
 *
 * <p>Execution: synchronous, position-changing I/O on a borrowed channel. Callers exclude concurrent
 * channel use and close; the returned descriptor does not retain that channel.
 *
 * <p>Failure: malformed or unsupported input can produce IOException or unchecked format exceptions;
 * callers must not interpret access failure as evidence that stored content is corrupt.
 *
 * <p>Seam: implements the container layer of
 * [validation](docs/architecture/asset-pipeline/overview.md#分层验证与发布).
 */
public final class AssetContainerReader {
    private AssetContainerReader() {}

    @SuppressWarnings("UnstableApiUsage")
    public static AssetContainerView read(SeekableByteChannel file) throws IOException {
        if (file.size() > AssetContainerConstant.MAX_FILE_SIZE) {
            throw new IOException("File size too large");
        }

        var headLen = AssetContainerConstant.HEAD.length;
        var reader = beginRead(file, 0, headLen + AssetContainerConstant.MAX_SUMMARY_SIZE + 1, 4 * 1024);
        var head = new byte[headLen];
        reader.readFully(head);
        if (Arrays.compare(AssetContainerConstant.HEAD, head) != 0) {
            throw new UnsupportedEncodingException();
        }

        var headerOffset = headLen + 1;
        while (reader.readByte() != 0) {
            headerOffset++;
        }

        reader = beginRead(file, headerOffset, AssetContainerConstant.HEADER_SIZE, AssetContainerConstant.HEADER_SIZE);
        var headerSize = reader.readUnsignedShort();
        if (headerSize < AssetContainerConstant.HEADER_SIZE) {
            throw new SerializationException("Header size too small");
        }
        if (headerSize + headerOffset > file.size()) {
            throw new SerializationException("Header data overflow");
        }

        reader = beginRead(file, headerOffset + 2, headerSize - 2, headerSize - 2);
        var majorVer = reader.readUnsignedByte();
        var minorVer = reader.readUnsignedShort();
        var patchVer = reader.readUnsignedShort();
        var qualifierVer = BinaryUtil.readFixedString(reader, AssetContainerConstant.HEADER_QUALIFIER_VERSION_SIZE);
        var version = majorVer + "." + minorVer + "." + patchVer + (qualifierVer.isEmpty() ? "" : "-" + qualifierVer);
        if (!VersionCompatibility.isCompatible(AssetContainerConstant.CURRENT_VERSION, version, String::equals)) {
            throw new UnsupportedEncodingException("Container version is not supported: " + version);
        }
        var schema = BinaryUtil.readFixedString(reader, AssetContainerConstant.HEADER_SCHEMA_SIZE);

        var schemaPropertyCount = reader.readShort();
        var chunkCount = reader.readShort();
        var tlvSize = reader.readInt();
        var chunkTableSize = reader.readInt();

        long tlvOffset = headerOffset + headerSize;
        long chunkTableOffset = tlvOffset + tlvSize;
        long chunkDataOffset = chunkTableOffset + chunkTableSize;
        if (schemaPropertyCount < 0 || chunkCount < 0 || tlvSize < 0 || chunkTableSize < 0
            || tlvOffset > Integer.MAX_VALUE || tlvOffset < 0 || chunkTableOffset > Integer.MAX_VALUE
            || chunkTableOffset < 0 || chunkDataOffset > Integer.MAX_VALUE || chunkDataOffset < 0) {
            throw new SerializationException("Data overflow");
        }

        reader = beginRead(file, tlvOffset, tlvSize, 64);
        var schemaProperties = new Short2ReferenceOpenHashMap<String>(schemaPropertyCount);
        while (schemaPropertyCount-- > 0) {
            var type = reader.readShort();
            if (type < 0) {
                throw new SerializationException("Schema property type must not be negative");
            }
            var value = BinaryUtil.readShortString(reader);
            if (schemaProperties.put(type, value) != null) {
                throw new SerializationException("Duplicate schema property type: " + type);
            }
        }
        if (reader.read() != -1) {
            throw new SerializationException("Schema property data size overflow");
        }

        var chunkTable = new Object2ReferenceOpenHashMap<String, AssetContainerView.ChunkInfo>(chunkCount);
        var globalAlignmentShift = 0;
        reader = beginRead(file, chunkTableOffset, chunkTableSize, 512);
        var currentOffset = chunkDataOffset;
        while (chunkCount-- > 0) {
            var type = BinaryUtil.readShortString(reader);
            var encoding = BinaryUtil.readShortString(reader);
            var size = reader.readInt();
            var decodeSize = reader.readInt();
            var flags = reader.readInt();
            var alignmentShift = reader.readUnsignedByte();
            if (alignmentShift > AssetContainerConstant.MAX_CHUNK_ALIGN_SHIFT) {
                throw new SerializationException(
                    "Alignment shift must not be greater than " + AssetContainerConstant.MAX_CHUNK_ALIGN_SHIFT);
            }
            var alignment = 1 << alignmentShift;
            var hash = new byte[AssetContainerConstant.HASH_SIZE];
            reader.readFully(hash);

            long alignSize = (alignment - currentOffset % alignment) % alignment;
            long offset = currentOffset + alignSize;
            if (size < 0 || offset > Integer.MAX_VALUE) {
                throw new SerializationException("Chunk data overflow");
            }

            if (chunkTable.isEmpty()) {
                if (!type.equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
                    throw new SerializationException("Verification chunk is not the first");
                }
                for (var value : hash) {
                    if (value != 0) {
                        throw new SerializationException("Illegal verification chunk hash");
                    }
                }
            }

            var info = new AssetContainerView.ChunkInfo(type, encoding, (int)offset, size, decodeSize, flags,
                (int)alignSize, alignmentShift, hash);
            if (chunkTable.put(type, info) != null) {
                throw new SerializationException("Duplicate chunk type: " + type);
            }
            globalAlignmentShift = Math.max(globalAlignmentShift, alignmentShift);
            currentOffset = Math.addExact(offset, size);
        }
        if (reader.read() != -1) {
            throw new SerializationException("Chunk table size overflow");
        }
        if (currentOffset < file.size()) {
            throw new SerializationException("Illegal trailing data");
        }

        var verification = chunkTable.get(AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
        if (verification == null) {
            throw new SerializationException("Verification chunk not found");
        }
        if (verification.alignmentShift() != 0) {
            throw new SerializationException("Illegal verification chunk alignment");
        }
        if (verification.size() < AssetContainerConstant.VERIFICATION_PAYLOAD_HEADER_SIZE) {
            throw new SerializationException("Verification payload is too small");
        }
        final Hash256 containerId;
        try (var verificationInput = NativeBuffer.allocate((int)chunkDataOffset)) {
            readFully(file, 0, verificationInput.nio());
            var verificationPayload = new byte[verification.size()];
            readFully(file, verification.offset(), ByteBuffer.wrap(verificationPayload));
            var signatureLength =
                Short.toUnsignedInt(ByteBuffer.wrap(verificationPayload, AssetContainerConstant.HASH_SIZE, Short.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort());
            if (verificationPayload.length != AssetContainerConstant.VERIFICATION_PAYLOAD_HEADER_SIZE
                + signatureLength) {
                throw new SerializationException("Malformed verification signature length");
            }
            var hash = Arrays.copyOf(verificationPayload, AssetContainerConstant.HASH_SIZE);
            if (verification.encoding().equals(AssetContainerConstant.HASH_NAME)) {
                if (!Blake3.validateHash(verificationInput, hash)) {
                    throw new SerializationException("Container verification failed");
                }
            } else if (verification.encoding().equals(AssetContainerConstant.ED25519_NAME)) {
                throw new NotImplementedException("Ed25519 signature is not implemented");
            } else {
                throw new SerializationException("Unknown verification method");
            }
            containerId = new Hash256(hash);
        }

        var preambleSize = Math.addExact(verification.offset(), verification.size());
        return new AssetContainerView(minorVer, patchVer, qualifierVer, schema, containerId, globalAlignmentShift,
            preambleSize, Short2ReferenceMaps.unmodifiable(schemaProperties),
            Object2ReferenceMaps.unmodifiable(chunkTable));
    }

    public static AssetContainerView readPreamble(byte[] containerPreamble) throws IOException {
        return readPreamble(containerPreamble, 0, containerPreamble.length);
    }

    /**
     * Reads the supplied array range without retaining it. The range must contain the complete verified
     * preamble but need not contain ordinary chunk payloads; descriptor offsets remain container-relative.
     *
     * @param offset first byte in the array, non-negative
     * @param size byte count within the array; the caller must not mutate this range during parsing
     */
    public static AssetContainerView readPreamble(byte[] containerPreamble, int offset, int size) throws IOException {
        try (var channel = new ByteArraySeekableChannel(containerPreamble, offset, size)) {
            return read(channel);
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private static LittleEndianDataInputStream beginRead(SeekableByteChannel file, long offset, int maxSize,
        int bufferSize) throws IOException {
        file.position(offset >= 0 ? offset : file.size() + offset);
        return new LittleEndianDataInputStream(
            new BufferedInputStream(new BoundedInputStream(Channels.newInputStream(file), maxSize), bufferSize));
    }

    private static void readFully(SeekableByteChannel file, long offset, ByteBuffer target) throws IOException {
        file.position(offset);
        while (target.hasRemaining()) {
            if (file.read(target) < 0) {
                throw new IOException("Unexpected end of container data");
            }
        }
    }
}
