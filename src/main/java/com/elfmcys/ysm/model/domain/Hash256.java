package com.elfmcys.ysm.model.domain;

import com.elfmcys.ysm.natives.Blake3;

import java.util.HexFormat;

/** One immutable 256-bit hash value. Field names define its domain role. */
public final class Hash256 implements Comparable<Hash256> {
    public static final int SIZE = Blake3.HASH_SIZE;
    private static final HexFormat HEX = HexFormat.of();

    private final long word0;
    private final long word1;
    private final long word2;
    private final long word3;
    private final int hashCode;
    private String text;

    public Hash256(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    public Hash256(byte[] bytes, int offset, int length) {
        if (length != SIZE || offset < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("Hash must contain exactly " + SIZE + " bytes");
        }
        this.word0 = readLong(bytes, offset);
        this.word1 = readLong(bytes, offset + Long.BYTES);
        this.word2 = readLong(bytes, offset + Long.BYTES * 2);
        this.word3 = readLong(bytes, offset + Long.BYTES * 3);
        this.hashCode = arrayHashCode(bytes, offset);
    }

    public static Hash256 parse(String value) {
        if (value.length() != SIZE * 2) {
            throw new IllegalArgumentException("Hash must contain exactly " + (SIZE * 2) + " hexadecimal characters");
        }
        return new Hash256(HEX.parseHex(value));
    }

    public byte[] bytes() {
        var result = new byte[SIZE];
        writeLong(result, 0, word0);
        writeLong(result, Long.BYTES, word1);
        writeLong(result, Long.BYTES * 2, word2);
        writeLong(result, Long.BYTES * 3, word3);
        return result;
    }

    public boolean matches(byte[] value, int offset, int length) {
        return length == SIZE && offset >= 0 && offset <= value.length - length && word0 == readLong(value, offset)
            && word1 == readLong(value, offset + Long.BYTES) && word2 == readLong(value, offset + Long.BYTES * 2)
            && word3 == readLong(value, offset + Long.BYTES * 3);
    }

    public boolean matches(byte[] value) {
        return matches(value, 0, value.length);
    }

    /** Only roaming-variable synchronization is allowed to use this truncated value. */
    public int roamingHash() {
        return (int)(word0 >>> Integer.SIZE);
    }

    @Override
    public int compareTo(Hash256 other) {
        var comparison = Long.compareUnsigned(word0, other.word0);
        if (comparison == 0) {
            comparison = Long.compareUnsigned(word1, other.word1);
        }
        if (comparison == 0) {
            comparison = Long.compareUnsigned(word2, other.word2);
        }
        if (comparison == 0) {
            comparison = Long.compareUnsigned(word3, other.word3);
        }
        return comparison;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Hash256 other && word0 == other.word0 && word1 == other.word1 && word2 == other.word2
            && word3 == other.word3;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        if (text == null) {
            text = HEX.formatHex(bytes());
        }
        return text;
    }

    private static long readLong(byte[] source, int offset) {
        return (long)(source[offset] & 0xff) << 56 | (long)(source[offset + 1] & 0xff) << 48
            | (long)(source[offset + 2] & 0xff) << 40 | (long)(source[offset + 3] & 0xff) << 32
            | (long)(source[offset + 4] & 0xff) << 24 | (long)(source[offset + 5] & 0xff) << 16
            | (long)(source[offset + 6] & 0xff) << 8 | (source[offset + 7] & 0xffL);
    }

    private static void writeLong(byte[] target, int offset, long value) {
        for (var index = Long.BYTES - 1; index >= 0; index--) {
            target[offset + index] = (byte)value;
            value >>>= Byte.SIZE;
        }
    }

    private static int arrayHashCode(byte[] source, int offset) {
        var result = 1;
        for (var index = offset; index < offset + SIZE; index++) {
            result = 31 * result + source[index];
        }
        return result;
    }
}
