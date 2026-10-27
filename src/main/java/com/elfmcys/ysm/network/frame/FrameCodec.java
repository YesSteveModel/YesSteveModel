package com.elfmcys.ysm.network.frame;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.UniBufferIO;
import com.elfmcys.ysm.natives.Zstd;

import java.util.Objects;
import java.util.function.IntPredicate;

/** Pure codec for the single current YSM wire format. */
public final class FrameCodec {
    public static final int MAX_BODY_BYTES = 30 * 1024;
    public static final int MAX_DECODED_PROTO_BYTES = 1024 * 1024;
    public static final int COMPRESSION_THRESHOLD = 64;
    public static final int COMPRESSION_LEVEL = 10;

    private static final int FULL_KIND = 1;
    private static final int FLAG_PROTOBUF_ZSTD = 1;
    private static final int FULL_HEADER_BYTES = 1 + 1 + Integer.BYTES * 3;

    private FrameCodec() {}

    public static OutboundFrame encode(int messageId, byte[] protobuf, byte[] attachment) {
        return encode(messageId, ArrayBuffer.borrow(protobuf),
            attachment == null ? ArrayBuffer.borrow(new byte[0]) : ArrayBuffer.borrow(attachment));
    }

    public static OutboundFrame encode(int messageId, UniBuffer protobuf, UniBuffer attachment) {
        validateMessageId(messageId);
        Objects.requireNonNull(protobuf, "protobuf");
        Objects.requireNonNull(attachment, "attachment");
        if (protobuf.size() > MAX_DECODED_PROTO_BYTES) {
            throw new IllegalArgumentException("Decoded protobuf exceeds 1 MiB");
        }
        if (attachment.size() == 0 && protobuf.size() < MAX_BODY_BYTES) {
            var target = ArrayBuffer.allocate(1 + protobuf.size());
            target.array()[target.arrayOffset()] = (byte)(messageId << 1);
            UniBufferIO.copy(protobuf, 0, target, 1, protobuf.size());
            return new OutboundFrame(target);
        }

        UniBuffer stored = protobuf.borrow();
        UniBuffer compressed = null;
        var flags = 0;
        try {
            if (protobuf.size() > COMPRESSION_THRESHOLD) {
                compressed = Zstd.compressAndHash(protobuf, null, BufferType.NATIVE, COMPRESSION_LEVEL);
                if (compressed.size() < protobuf.size()) {
                    stored = compressed;
                    flags = FLAG_PROTOBUF_ZSTD;
                }
            }
            checkBody(stored.size(), attachment.size());
            var target = ArrayBuffer.allocate(FULL_HEADER_BYTES + stored.size() + attachment.size());
            try {
                var array = target.array();
                var offset = target.arrayOffset();
                array[offset] = (byte)((messageId << 1) | FULL_KIND);
                array[offset + 1] = (byte)flags;
                writeInt(array, offset + 2, protobuf.size());
                writeInt(array, offset + 6, stored.size());
                writeInt(array, offset + 10, attachment.size());
                UniBufferIO.copy(stored, 0, target, FULL_HEADER_BYTES, stored.size());
                UniBufferIO.copy(attachment, 0, target, FULL_HEADER_BYTES + stored.size(), attachment.size());
                return new OutboundFrame(target);
            } catch (Throwable error) {
                target.close();
                throw error;
            }
        } finally {
            if (compressed != null) {
                compressed.close();
            }
        }
    }

    public static DecodedFrame decode(byte[] frame, IntPredicate registeredMessage) {
        try (var input = ArrayBuffer.move(frame)) {
            return decode(input, registeredMessage);
        }
    }

    public static DecodedFrame decode(UniBuffer frame, IntPredicate registeredMessage) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(registeredMessage, "registeredMessage");
        if (frame.size() == 0) {
            throw malformed("Missing message tag");
        }
        try (var array = frame.acquireArray()) {
            var bytes = array.array();
            var base = array.arrayOffset();
            var tag = Byte.toUnsignedInt(bytes[base]);
            var messageId = tag >>> 1;
            validateMessageId(messageId);
            if (!registeredMessage.test(messageId)) {
                throw malformed("Unknown or reserved message id: " + messageId);
            }
            if ((tag & FULL_KIND) == 0) {
                var size = frame.size() - 1;
                if (size >= MAX_BODY_BYTES) {
                    throw malformed("Simple frame body must be smaller than 30 KiB");
                }
                return new DecodedFrame(messageId, frame.slice(1, size).acquire(), ArrayBuffer.allocate(0), false);
            }
            if (frame.size() < FULL_HEADER_BYTES) {
                throw malformed("Truncated full-frame header");
            }
            var flags = Byte.toUnsignedInt(bytes[base + 1]);
            if ((flags & ~FLAG_PROTOBUF_ZSTD) != 0) {
                throw malformed("Unknown full-frame flags: " + flags);
            }
            var decodedSize = readInt(bytes, base + 2);
            var storedSize = readInt(bytes, base + 6);
            var attachmentSize = readInt(bytes, base + 10);
            if (decodedSize < 0 || decodedSize > MAX_DECODED_PROTO_BYTES) {
                throw malformed("Invalid decoded protobuf size: " + decodedSize);
            }
            checkBody(storedSize, attachmentSize);
            if ((long)FULL_HEADER_BYTES + storedSize + attachmentSize != frame.size()) {
                throw malformed("Full-frame sizes do not match the remaining bytes");
            }
            var compressed = (flags & FLAG_PROTOBUF_ZSTD) != 0;
            UniBuffer protobuf = null;
            UniBuffer attachment = null;
            try {
                if (compressed) {
                    if (storedSize == 0 || decodedSize <= COMPRESSION_THRESHOLD || storedSize >= decodedSize) {
                        throw malformed("Invalid zstd protobuf representation");
                    }
                    try (var stored = frame.slice(FULL_HEADER_BYTES, storedSize).acquire()) {
                        protobuf = Zstd.decompressAndValidate(stored, decodedSize, null, BufferType.ARRAY);
                    }
                } else {
                    if (decodedSize != storedSize) {
                        throw malformed("Raw protobuf sizes do not match");
                    }
                    protobuf = frame.slice(FULL_HEADER_BYTES, storedSize).acquire();
                }
                attachment = frame.slice(FULL_HEADER_BYTES + storedSize, attachmentSize).acquire();
                return new DecodedFrame(messageId, protobuf, attachment, compressed);
            } catch (RuntimeException error) {
                if (protobuf != null)
                    protobuf.close();
                if (attachment != null)
                    attachment.close();
                throw malformed("Invalid full frame", error);
            }
        }
    }

    private static void validateMessageId(int messageId) {
        if (messageId < 0 || messageId > 127) {
            throw new IllegalArgumentException("Message id must be in [0, 127]");
        }
    }

    private static void checkBody(int storedSize, int attachmentSize) {
        if (storedSize < 0 || attachmentSize < 0 || (long)storedSize + attachmentSize >= MAX_BODY_BYTES) {
            throw malformed("Full-frame body must be smaller than 30 KiB");
        }
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte)value;
        target[offset + 1] = (byte)(value >>> 8);
        target[offset + 2] = (byte)(value >>> 16);
        target[offset + 3] = (byte)(value >>> 24);
    }

    private static int readInt(byte[] source, int offset) {
        return source[offset] & 0xff | (source[offset + 1] & 0xff) << 8 | (source[offset + 2] & 0xff) << 16
            | source[offset + 3] << 24;
    }

    private static IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException malformed(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }

    public static final class DecodedFrame implements AutoCloseable {
        private final int messageId;
        private final UniBuffer protobuf;
        private final UniBuffer attachment;
        private final boolean compressed;

        private DecodedFrame(int messageId, UniBuffer protobuf, UniBuffer attachment, boolean compressed) {
            this.messageId = messageId;
            this.protobuf = protobuf;
            this.attachment = attachment;
            this.compressed = compressed;
        }

        public int messageId() {
            return messageId;
        }

        public UniBuffer protobuf() {
            return protobuf.borrow();
        }

        public UniBuffer attachment() {
            return attachment.borrow();
        }

        public boolean compressed() {
            return compressed;
        }

        @Override
        public void close() {
            protobuf.close();
            attachment.close();
        }
    }
}
