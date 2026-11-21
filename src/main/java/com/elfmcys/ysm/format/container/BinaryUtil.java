package com.elfmcys.ysm.format.container;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class BinaryUtil {
    public static void writeShortString(DataOutput output, String str) throws IOException {
        var bytes = ascii(str);
        if (bytes.length > 255) {
            throw new IllegalArgumentException("Short ASCII string exceeds 255 bytes");
        }
        output.write(bytes.length);
        output.write(bytes);
    }

    public static String readShortString(DataInput input) throws IOException {
        var size = Byte.toUnsignedInt(input.readByte());
        var bytes = new byte[size];
        input.readFully(bytes);
        for (var value : bytes) {
            if (value <= 0) {
                throw new IOException("Short string contains non-ASCII or NUL bytes");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    public static void writeFixedString(DataOutput output, String str, int size) throws IOException {
        var bytes = ascii(str);
        if (bytes.length > size) {
            throw new IllegalArgumentException("String utf8 byte size exceeds maximum allowed size of " + size);
        }
        output.write(bytes);
        for (var i = bytes.length; i < size; i++) {
            output.write(0);
        }
    }

    public static String readFixedString(DataInput input, int size) throws IOException {
        var bytes = new byte[size];
        input.readFully(bytes);
        var length = 0;
        while (length < size && bytes[length] != 0) {
            if (bytes[length] < 0) {
                throw new IOException("Fixed string contains non-ASCII bytes");
            }
            length++;
        }
        for (var index = length; index < size; index++) {
            if (bytes[index] != 0) {
                throw new IOException("Fixed string contains nonzero padding");
            }
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    private static byte[] ascii(String value) {
        for (var index = 0; index < value.length(); index++) {
            if (value.charAt(index) == 0 || value.charAt(index) > 127) {
                throw new IllegalArgumentException("String must contain ASCII without NUL");
            }
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
