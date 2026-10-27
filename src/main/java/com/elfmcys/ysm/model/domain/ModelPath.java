package com.elfmcys.ysm.model.domain;

import java.nio.file.Path;
import java.util.Objects;

/** User-visible path within a model origin. It is never used as persisted model identity. */
public record ModelPath(String value) implements Comparable<ModelPath> {
    public ModelPath {
        validate(value);
    }

    public static ModelPath relativeTo(Path root, Path file) {
        var relative = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        var result = new java.util.StringJoiner("/");
        for (var segment : relative) {
            var name = segment.toString();
            validate(name);
            result.add(name);
        }
        return new ModelPath(result.toString());
    }

    public String parentHierarchy() {
        var index = value.lastIndexOf('/');
        return index < 0 ? "" : value.substring(0, index + 1);
    }

    @Override
    public int compareTo(ModelPath other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static void validate(String input) {
        Objects.requireNonNull(input, "model path");
        for (var part : input.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Invalid model path segment");
        for (int i = 0; i < input.length(); i++) {
            var c = input.charAt(i);
            if (c == '\\' || c == ':' || c <= 0x1f || c >= 0x7f && c <= 0x9f)
                throw new IllegalArgumentException("Invalid model path character");
            if (Character.isHighSurrogate(c)) {
                if (++i == input.length() || !Character.isLowSurrogate(input.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode scalar sequence");
            } else if (Character.isLowSurrogate(c))
                throw new IllegalArgumentException("Invalid Unicode scalar sequence");
        }
    }
}
