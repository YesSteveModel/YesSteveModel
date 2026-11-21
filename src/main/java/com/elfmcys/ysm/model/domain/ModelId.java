package com.elfmcys.ysm.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One immutable, canonical ordinary model identity. It does not prove equal content or authorship.
 *
 * <p>
 * Invariant: construction validates the complete ASCII value; consumers preserve it without normalization.
 * <p>
 * Seam: establishes [model identity](docs/standards/model-schema/manifest-and-identity.md#properties-与-modelhash).
 */
public final class ModelId implements Comparable<ModelId> {
    public static final int MAX_LENGTH = 194;
    private static final Pattern HASH = Pattern.compile("hash:[0-9a-f]{64}");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z_-][0-9A-Za-z_-]{0,31}/[a-zA-Z_-][0-9A-Za-z_-]{0,31}");
    private final String text;
    private final String family;
    private final SemVer version;

    private ModelId(String text, String family, SemVer version) {
        this.text = text;
        this.family = family;
        this.version = version;
    }

    public static ModelId parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("ModelId must contain 1 to 194 ASCII characters");
        }
        if (HASH.matcher(text).matches()) {
            return new ModelId(text, null, null);
        }
        var separator = text.indexOf('@');
        var family = separator < 0 ? text : text.substring(0, separator);
        if (!NAME.matcher(family).matches()) {
            throw new IllegalArgumentException("Invalid ModelId: " + text);
        }
        var version = separator < 0 ? null : SemVer.parse(text.substring(separator + 1));
        return new ModelId(text, family, version);
    }

    public static ModelId fromHash(Hash256 hash) {
        return new ModelId("hash:" + Objects.requireNonNull(hash, "hash"), null, null);
    }

    public static boolean isValid(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    public boolean isHash() {
        return family == null;
    }

    public Optional<String> family() {
        return Optional.ofNullable(family);
    }

    public Optional<SemVer> version() {
        return Optional.ofNullable(version);
    }

    public byte[] bytes() {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public int compareTo(ModelId other) {
        return text.compareTo(other.text);
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof ModelId other && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
