package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.ModelPath;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Canonical catalog address. An empty entry denotes the namespace directory. */
public record CatalogLocation(CatalogRoot root, String entry) implements Comparable<CatalogLocation> {

    public static final CatalogLocation BUILTIN_DEFAULT = new CatalogLocation(CatalogRoot.BUILTIN, "default");

    public CatalogLocation {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(entry, "entry");
        if (!entry.isEmpty())
            new ModelPath(entry);
    }

    public CatalogLocation(CatalogRoot root, ModelPath path) {
        this(root, path.value());
    }

    public CatalogLocation(CatalogRootKind kind, ModelPath path) {
        this(CatalogRoot.local(kind), path);
    }

    public CatalogRootKind rootKind() {
        return root.kind();
    }

    public ModelPath path() {
        return new ModelPath(entry);
    }

    public String value() {
        return root.prefix() + entry;
    }

    public String parent() {
        var slash = entry.lastIndexOf('/');
        return new CatalogLocation(root, slash < 0 ? "" : entry.substring(0, slash)).value();
    }

    public static CatalogLocation parse(String value) {
        var location = directory(value);
        if (location.entry.isEmpty())
            throw new IllegalArgumentException("Model address requires an entry");
        return location;
    }

    public static CatalogLocation directory(String value) {
        Objects.requireNonNull(value, "address");
        for (var root : CatalogRoot.values()) {
            if (value.equals(root.prefix()))
                return new CatalogLocation(root, "");
            var prefix = root.prefix();
            if (value.startsWith(prefix)) {
                var location = new CatalogLocation(root, new ModelPath(value.substring(prefix.length())));
                if (!value.equals(location.value()))
                    throw new IllegalArgumentException("Noncanonical catalog address");
                return location;
            }
        }
        throw new IllegalArgumentException("Invalid catalog address");
    }

    public static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int compareTo(CatalogLocation other) {
        return compareUtf8(value(), other.value());
    }

    @Override
    public String toString() {
        return value();
    }
}
