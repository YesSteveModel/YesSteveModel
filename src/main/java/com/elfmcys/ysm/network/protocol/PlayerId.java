package com.elfmcys.ysm.network.protocol;

public final class PlayerId {
    public static final int SIZE = 16;
    private final long high;
    private final long low;

    public PlayerId(byte[] bytes) {
        if (bytes.length != SIZE) {
            throw new IllegalArgumentException("Player id must be exactly 16 bytes");
        }
        high = readLong(bytes, 0);
        low = readLong(bytes, Long.BYTES);
    }

    public byte[] bytes() {
        var result = new byte[SIZE];
        writeLong(result, 0, high);
        writeLong(result, Long.BYTES, low);
        return result;
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof PlayerId other && high == other.high && low == other.low;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(high) + Long.hashCode(low);
    }

    private static long readLong(byte[] source, int offset) {
        long value = 0;
        for (var index = 0; index < Long.BYTES; index++) {
            value = value << Byte.SIZE | source[offset + index] & 0xffL;
        }
        return value;
    }

    private static void writeLong(byte[] target, int offset, long value) {
        for (var index = Long.BYTES - 1; index >= 0; index--) {
            target[offset + index] = (byte)value;
            value >>>= Byte.SIZE;
        }
    }
}
