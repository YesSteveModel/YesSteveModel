package com.elfmcys.ysm.natives.legacy;

import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

final class NativeLegacyProtocol {
    private static final byte[] MAGIC = {'Y', 'S', 'M', 'L', 'E', 'G', 'I', 0};
    private static final int HEADER_SIZE = 60;
    private static final int PAYLOAD_PREFIX_SIZE = 40;
    private static final int MAX_DESCRIPTOR_SIZE = 16 * 1024 * 1024;
    private static final int MAX_PAYLOAD_COUNT = 32766;
    private static final long MAX_SOURCE_SIZE = 66L * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 4096;

    private NativeLegacyProtocol() {}

    static Response accept(NativeLegacyImportResult raw) {
        if (raw == null) {
            throw new ProtocolException("Native legacy result is null");
        }
        try {
            var status = NativeLegacyStatus.fromNative(raw.statusCode());
            if (status == null) {
                throw new ProtocolException("Unknown native legacy status: " + raw.statusCode());
            }
            if (!isValidDiagnostic(raw.diagnostic())) {
                throw new ProtocolException("Invalid native legacy diagnostic");
            }
            if (status != NativeLegacyStatus.SUCCESS) {
                if (raw.get() != 0 || raw.descriptor() == null || raw.descriptor().length != 0 || raw.payloads() == null
                    || raw.payloads().length != 0) {
                    throw new ProtocolException("Native failure result carries payload ownership");
                }
                var failure = new Failure(status, raw.diagnostic());
                raw.close();
                return failure;
            }

            if (raw.get() == 0 || !raw.diagnostic().isEmpty() || raw.descriptor() == null || raw.payloads() == null) {
                throw new ProtocolException("Invalid native success shape");
            }

            var parsed = parseDescriptor(raw.descriptor(), raw.payloads());
            var payloads = new ArrayList<LegacyPayloadBuffer>(raw.payloads().length);
            for (var payload : raw.payloads()) {
                payloads.add(LegacyPayloadBuffer.publicView(payload, raw));
            }
            return new Success(raw, parsed, List.copyOf(payloads));
        } catch (RuntimeException | Error failure) {
            try {
                raw.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        } finally {
            Reference.reachabilityFence(raw);
        }
    }

    private static Descriptor parseDescriptor(byte[] bytes, ByteBuffer[] payloads) {
        if (bytes.length < HEADER_SIZE || bytes.length > MAX_DESCRIPTOR_SIZE) {
            throw new ProtocolException("Invalid legacy descriptor size");
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var magic = new byte[MAGIC.length];
        input.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new ProtocolException("Invalid legacy descriptor magic");
        }
        long innerVersion = Integer.toUnsignedLong(input.getInt());
        long payloadCount = Integer.toUnsignedLong(input.getInt());
        long reserved = Integer.toUnsignedLong(input.getInt());
        long sourceSize = input.getLong();
        if (innerVersion < 1 || innerVersion > 32 || payloadCount < 2 || payloadCount > MAX_PAYLOAD_COUNT
            || payloadCount != payloads.length || reserved != 0 || sourceSize < 0 || sourceSize > MAX_SOURCE_SIZE) {
            throw new ProtocolException("Invalid legacy descriptor header");
        }
        var modelId = new byte[32];
        input.get(modelId);
        for (int index = 16; index < modelId.length; ++index) {
            if (modelId[index] != 0) {
                throw new ProtocolException("Invalid legacy model identity padding");
            }
        }

        var records = new ArrayList<PayloadRecord>((int)payloadCount);
        for (int index = 0; index < payloadCount; ++index) {
            requireRemaining(input, PAYLOAD_PREFIX_SIZE);
            int kindCode = Short.toUnsignedInt(input.getShort());
            int encodingCode = Short.toUnsignedInt(input.getShort());
            long flags = Integer.toUnsignedLong(input.getInt());
            long logicalId = Integer.toUnsignedLong(input.getInt());
            long nameLength = Integer.toUnsignedLong(input.getInt());
            long meta0 = Integer.toUnsignedLong(input.getInt());
            long meta1 = Integer.toUnsignedLong(input.getInt());
            long meta2 = input.getLong();
            long payloadSize = input.getLong();
            if (flags != 0 || nameLength > MAX_DESCRIPTOR_SIZE || nameLength > input.remaining() || payloadSize < 0
                || payloadSize > Integer.MAX_VALUE) {
                throw new ProtocolException("Invalid legacy payload record");
            }
            var nameBytes = new byte[(int)nameLength];
            input.get(nameBytes);
            var name = decodeUtf8(nameBytes);
            var kind = PayloadKind.fromCode(kindCode);
            var encoding = PayloadEncoding.fromCode(encodingCode);
            if (kind == null || encoding == null) {
                throw new ProtocolException("Unknown legacy payload metadata");
            }
            validateBuffer(payloads[index], payloadSize);
            records.add(new PayloadRecord(kind, encoding, (int)logicalId, name, (int)meta0, (int)meta1, meta2,
                (int)payloadSize));
        }
        validateNoAliasing(payloads);
        validateCanonicalRecords(records);

        if (input.hasRemaining()) {
            throw new ProtocolException("Legacy descriptor has trailing bytes");
        }
        return new Descriptor((int)innerVersion, sourceSize, modelId, List.copyOf(records));
    }

    private static void validateBuffer(ByteBuffer payload, long payloadSize) {
        if (payload == null || !payload.isDirect() || !payload.isReadOnly() || payload.position() != 0
            || payload.remaining() != payload.capacity() || payload.capacity() != payloadSize) {
            throw new ProtocolException("Invalid legacy payload buffer view");
        }
    }

    private static void validateNoAliasing(ByteBuffer[] payloads) {
        for (int leftIndex = 0; leftIndex < payloads.length; ++leftIndex) {
            var left = payloads[leftIndex];
            if (left.capacity() == 0) {
                continue;
            }
            long leftStart = PlatformDependent.directBufferAddress(left) + left.position();
            long leftEnd = leftStart + Integer.toUnsignedLong(left.capacity());
            if (Long.compareUnsigned(leftEnd, leftStart) < 0) {
                throw new ProtocolException("Legacy payload address overflow");
            }
            for (int rightIndex = leftIndex + 1; rightIndex < payloads.length; ++rightIndex) {
                var right = payloads[rightIndex];
                if (right.capacity() == 0) {
                    continue;
                }
                long rightStart = PlatformDependent.directBufferAddress(right) + right.position();
                long rightEnd = rightStart + Integer.toUnsignedLong(right.capacity());
                if (Long.compareUnsigned(rightEnd, rightStart) < 0
                    || Long.compareUnsigned(leftStart, rightEnd) < 0 && Long.compareUnsigned(rightStart, leftEnd) < 0) {
                    throw new ProtocolException("Legacy payload buffers alias");
                }
            }
        }
    }

    private static void validateCanonicalRecords(List<PayloadRecord> records) {
        if (records.get(0).kind() != PayloadKind.MANIFEST || records.get(1).kind() != PayloadKind.STRING_DATA
            || records.get(1).logicalId() != 1) {
            throw new ProtocolException("Invalid legacy payload prefix");
        }

        int phase = 0;
        long nextBlobId = 1;
        long nextSoundId = 1;
        var modelNames = new HashSet<String>();
        String previousSound = null;
        boolean sawThumbButton = false;
        boolean sawThumbIcon = false;
        for (int index = 0; index < records.size(); ++index) {
            var record = records.get(index);
            validateRecordMetadata(record);
            switch (record.kind()) {
                case MANIFEST -> {
                    if (index != 0) {
                        throw new ProtocolException("Manifest is not first");
                    }
                }
                case STRING_DATA -> {
                    if (index != 1) {
                        throw new ProtocolException("StringData is not second");
                    }
                    ++nextBlobId;
                }
                case MODEL_DATA -> {
                    if (index < 2 || phase != 0 || Integer.toUnsignedLong(record.logicalId()) != nextBlobId++
                        || !modelNames.add(record.name())) {
                        throw new ProtocolException("Noncanonical ModelData record");
                    }
                }
                case BLOB_IMAGE -> {
                    phase = Math.max(phase, 1);
                    if (phase != 1 || Integer.toUnsignedLong(record.logicalId()) != nextBlobId++) {
                        throw new ProtocolException("Noncanonical blob image record");
                    }
                }
                case NAMED_IMAGE -> {
                    phase = Math.max(phase, 2);
                    if (phase != 2) {
                        throw new ProtocolException("Noncanonical named image record");
                    }
                    if (record.name().equals("thumb-button")) {
                        if (sawThumbButton || sawThumbIcon) {
                            throw new ProtocolException("Noncanonical named image order");
                        }
                        sawThumbButton = true;
                    } else {
                        if (sawThumbIcon) {
                            throw new ProtocolException("Duplicate named image");
                        }
                        sawThumbIcon = true;
                    }
                }
                case SOUND_STREAM -> {
                    phase = 3;
                    if (Integer.toUnsignedLong(record.logicalId()) != nextSoundId++
                        || previousSound != null && compareUnsignedUtf8(previousSound, record.name()) >= 0) {
                        throw new ProtocolException("Noncanonical sound stream order");
                    }
                    previousSound = record.name();
                }
            }
        }
    }

    private static void validateRecordMetadata(PayloadRecord record) {
        boolean noMeta = record.meta0() == 0 && record.meta1() == 0 && record.meta2() == 0;
        switch (record.kind()) {
            case MANIFEST -> require(record.encoding() == PayloadEncoding.DIRECT && record.logicalId() == 0
                && record.name().isEmpty() && noMeta);
            case STRING_DATA -> require(record.encoding() == PayloadEncoding.DIRECT && record.logicalId() != 0
                && record.name().isEmpty() && noMeta);
            case MODEL_DATA -> require(record.encoding() == PayloadEncoding.DIRECT && record.logicalId() != 0
                && !record.name().isEmpty() && noMeta);
            case BLOB_IMAGE -> require(record.logicalId() != 0 && record.name().isEmpty() && validImage(record));
            case NAMED_IMAGE -> require(record.logicalId() == 0
                && (record.name().equals("thumb-button") || record.name().equals("thumb-icon")) && validImage(record));
            case SOUND_STREAM -> require(record.logicalId() != 0 && !record.name().isEmpty()
                && (record.meta0() == 1 || record.meta0() == 2) && record.meta1() != 0 && record.meta2() > 0
                && (record.encoding() == PayloadEncoding.OGG_VORBIS || record.encoding() == PayloadEncoding.OGG_OPUS));
        }
    }

    private static boolean validImage(PayloadRecord record) {
        return record.encoding().isImage() && record.meta0() > 0 && record.meta0() <= MAX_IMAGE_DIMENSION
            && record.meta1() > 0 && record.meta1() <= MAX_IMAGE_DIMENSION && record.meta2() == 1;
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new ProtocolException("Invalid legacy payload metadata");
        }
    }

    private static int compareUnsignedUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        return Arrays.compareUnsigned(leftBytes, rightBytes);
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new ProtocolException("Legacy payload name is not UTF-8", failure);
        }
    }

    private static void requireRemaining(ByteBuffer input, int count) {
        if (input.remaining() < count) {
            throw new ProtocolException("Truncated legacy descriptor");
        }
    }

    private static boolean isValidDiagnostic(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); ++index) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= 4096;
    }

    sealed interface Response extends AutoCloseable permits Failure, Success {
        NativeLegacyStatus status();

        @Override
        void close();
    }

    record Failure(NativeLegacyStatus status, String diagnostic) implements Response {
        @Override
        public void close() {}
    }

    static final class Success implements Response {
        private final NativeLegacyImportResult ownership;
        private final Descriptor descriptor;
        private final List<LegacyPayloadBuffer> payloads;

        private Success(NativeLegacyImportResult ownership, Descriptor descriptor, List<LegacyPayloadBuffer> payloads) {
            this.ownership = ownership;
            this.descriptor = descriptor;
            this.payloads = payloads;
        }

        @Override
        public NativeLegacyStatus status() {
            return NativeLegacyStatus.SUCCESS;
        }

        Descriptor descriptor() {
            ownership.get();
            return descriptor;
        }

        List<LegacyPayloadBuffer> payloads() {
            ownership.get();
            return payloads;
        }

        @Override
        public void close() {
            ownership.close();
        }
    }

    static final class Descriptor {
        private final int innerVersion;
        private final long sourceSize;
        private final byte[] modelId;
        private final List<PayloadRecord> records;

        private Descriptor(int innerVersion, long sourceSize, byte[] modelId, List<PayloadRecord> records) {
            this.innerVersion = innerVersion;
            this.sourceSize = sourceSize;
            this.modelId = modelId.clone();
            this.records = records;
        }

        int innerVersion() {
            return innerVersion;
        }

        long sourceSize() {
            return sourceSize;
        }

        byte[] modelId() {
            return modelId.clone();
        }

        List<PayloadRecord> records() {
            return records;
        }

    }

    record PayloadRecord(PayloadKind kind, PayloadEncoding encoding, int logicalId, String name, int meta0, int meta1,
        long meta2, int payloadSize) {
    }

    enum PayloadKind {
        MANIFEST(1), STRING_DATA(2), MODEL_DATA(3), BLOB_IMAGE(4), NAMED_IMAGE(5), SOUND_STREAM(6);

        private final int code;

        PayloadKind(int code) {
            this.code = code;
        }

        static PayloadKind fromCode(int code) {
            return Arrays.stream(values()).filter(value -> value.code == code).findFirst().orElse(null);
        }
    }

    enum PayloadEncoding {
        DIRECT(0), RGBA(1), PNG(2), JPEG(3), WEBP(4), AVIF(5), OGG_VORBIS(6), OGG_OPUS(7), ZTX(8);

        private final int code;

        PayloadEncoding(int code) {
            this.code = code;
        }

        boolean isImage() {
            return this == PNG || this == JPEG || this == WEBP || this == AVIF || this == ZTX;
        }

        static PayloadEncoding fromCode(int code) {
            return Arrays.stream(values()).filter(value -> value.code == code).findFirst().orElse(null);
        }
    }

    static final class ProtocolException extends IllegalStateException {
        ProtocolException(String message) {
            super(message);
        }

        ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }

        NativeLegacyStatus status() {
            return NativeLegacyStatus.RESULT_PROTOCOL;
        }
    }
}
