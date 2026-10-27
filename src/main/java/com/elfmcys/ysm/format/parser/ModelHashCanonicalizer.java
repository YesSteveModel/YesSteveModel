package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.Blake3;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.TreeMap;

public class ModelHashCanonicalizer {
    private final TreeMap<RecordKey, byte[]> items = new TreeMap<>();

    public void add(String role, String portablePath, NativeBuffer original) {
        Objects.requireNonNull(original, "original");
        var bytes = new byte[original.size()];
        original.nio().get(bytes);
        addOwned(role, portablePath, bytes);
    }

    public void add(String role, String portablePath, byte[] original) {
        Objects.requireNonNull(original, "original");
        addOwned(role, portablePath, original.clone());
    }

    private void addOwned(String role, String portablePath, byte[] original) {
        var key = new RecordKey(asciiRole(role), portablePath(portablePath));
        var previous = items.putIfAbsent(key, original);
        if (previous != null && !Arrays.equals(previous, original)) {
            throw new IllegalArgumentException("Canonical source changed for " + role + ":" + portablePath);
        }
    }

    public byte[] aggregate() {
        long size = Integer.BYTES;
        for (var entry : items.entrySet()) {
            size = Math.addExact(size, Integer.BYTES + (long)entry.getKey().role.length);
            size = Math.addExact(size, Integer.BYTES + (long)entry.getKey().path.length);
            size = Math.addExact(size, Long.BYTES + (long)entry.getValue().length);
        }
        if (size > UniBuffer.MAX_SIZE) {
            throw new IllegalArgumentException("Canonical model source is too large");
        }

        try (var encoded = ArrayBuffer.allocate((int)size)) {
            var output = encoded.nio().order(ByteOrder.LITTLE_ENDIAN);
            output.putInt(items.size());
            for (var entry : items.entrySet()) {
                output.putInt(entry.getKey().role.length).put(entry.getKey().role);
                output.putInt(entry.getKey().path.length).put(entry.getKey().path);
                output.putLong(entry.getValue().length).put(entry.getValue());
            }
            return Blake3.computeHash(encoded);
        }
    }

    private static byte[] asciiRole(String role) {
        Objects.requireNonNull(role, "role");
        if (role.isEmpty()) {
            throw new IllegalArgumentException("Canonical role must not be empty");
        }
        for (var index = 0; index < role.length(); index++) {
            if (role.charAt(index) > 0x7f) {
                throw new IllegalArgumentException("Canonical role must be ASCII: " + role);
            }
        }
        return role.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] portablePath(String path) {
        Objects.requireNonNull(path, "portablePath");
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
            || isWindowsAbsolute(path)) {
            throw new IllegalArgumentException("Invalid portable path: " + path);
        }
        for (var segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid portable path: " + path);
            }
        }
        validateSurrogates(path);
        return path.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isWindowsAbsolute(String path) {
        return path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
            && path.charAt(2) == '/';
    }

    private static void validateSurrogates(String value) {
        for (var index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("Portable path contains invalid UTF-16");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Portable path contains invalid UTF-16");
            }
        }
    }

    private record RecordKey(byte[] role, byte[] path) implements Comparable<RecordKey> {
        @Override
        public int compareTo(RecordKey other) {
            var comparison = Arrays.compareUnsigned(role, other.role);
            return comparison != 0 ? comparison : Arrays.compareUnsigned(path, other.path);
        }
    }
}
