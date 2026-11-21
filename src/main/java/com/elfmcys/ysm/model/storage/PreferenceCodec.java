package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class PreferenceCodec {
    private PreferenceCodec() {}

    static NamedRoamingSnapshot snapshot(NamedRoamingSnapshot payload) {
        if (payload.names() == null || payload.values() == null || payload.names().length != payload.values().length)
            throw new IllegalArgumentException("Roaming arrays must have equal lengths");
        if (payload.names().length > 64)
            throw new IllegalArgumentException("Roaming payload exceeds 64 fields");
        var names = new java.util.HashSet<String>();
        for (int index = 0; index < payload.names().length; index++) {
            var name = payload.names()[index];
            if (name == null)
                throw new IllegalArgumentException("Invalid roaming field");
            var bytes = utf8(name);
            if (name.isEmpty() || bytes.length > 32 || !names.add(name))
                throw new IllegalArgumentException("Invalid roaming field");
            if (!Float.isFinite(payload.values()[index]))
                throw new IllegalArgumentException("Roaming values must be finite");
        }
        return payload;
    }

    static byte[] roaming(NamedRoamingSnapshot value) {
        var bytes = ByteBuffer.allocate(2 + value.names().length * 37);
        bytes.put((byte)1).put((byte)value.names().length);
        for (int index = 0; index < value.names().length; index++) {
            var text = utf8(value.names()[index]);
            bytes.put((byte)text.length).put(text).putFloat(value.values()[index]);
        }
        return java.util.Arrays.copyOf(bytes.array(), bytes.position());
    }

    static NamedRoamingSnapshot roaming(SharedArrayBuffer value) {
        var bytes = value.nio().order(ByteOrder.BIG_ENDIAN);
        version(bytes);
        var count = Byte.toUnsignedInt(bytes.get());
        if (count > 64)
            throw new IllegalArgumentException("Roaming payload exceeds 64 fields");
        var names = new String[count];
        var values = new float[count];
        var seen = new java.util.HashSet<String>();
        for (var index = 0; index < count; index++) {
            var length = Byte.toUnsignedInt(bytes.get());
            if (length == 0 || length > 32)
                throw new IllegalArgumentException("Invalid roaming field");
            names[index] = text(bytes, length);
            if (!seen.add(names[index]))
                throw new IllegalArgumentException("Invalid roaming field");
            values[index] = bytes.getFloat();
            if (!Float.isFinite(values[index]))
                throw new IllegalArgumentException("Roaming values must be finite");
        }
        end(bytes);
        return new NamedRoamingSnapshot(names, values);
    }

    static byte[] selection(PlayerPreferences.LocalSelection value) {
        var identity = utf8(value.identity().toString());
        var texture = utf8(value.texture());
        return ByteBuffer.allocate(5 + identity.length + texture.length).put((byte)1).putShort((short)identity.length)
            .put(identity).putShort((short)texture.length).put(texture).array();
    }

    static PlayerPreferences.LocalSelection selection(SharedArrayBuffer value) {
        var bytes = value.nio().order(ByteOrder.BIG_ENDIAN);
        version(bytes);
        var identity = PublishedModelId.parse(text(bytes, Short.toUnsignedInt(bytes.getShort())));
        var texture = text(bytes, Short.toUnsignedInt(bytes.getShort()));
        end(bytes);
        return new PlayerPreferences.LocalSelection(identity, texture);
    }

    static Boolean favourite(SharedArrayBuffer value) {
        var bytes = value.nio();
        if (bytes.remaining() != 1 || (bytes.get(0) != 0 && bytes.get(0) != 1))
            throw new IllegalArgumentException("Invalid favourite value");
        return bytes.get() == 1;
    }

    static byte[] utf8(String text) {
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text));
            var result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 text", failure);
        }
    }

    private static String text(ByteBuffer bytes, int length) {
        if (length > bytes.remaining())
            throw new IllegalArgumentException("Truncated preference text");
        var part = bytes.slice();
        part.limit(length);
        bytes.position(bytes.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(part).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 text", failure);
        }
    }

    private static void version(ByteBuffer bytes) {
        if (bytes.get() != 1)
            throw new IllegalArgumentException("Unknown preference value format");
    }

    private static void end(ByteBuffer bytes) {
        if (bytes.hasRemaining())
            throw new IllegalArgumentException("Trailing preference bytes");
    }
}
