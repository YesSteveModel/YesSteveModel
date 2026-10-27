package com.elfmcys.ysm.model.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** A configured DATA root and a complete conversion version own one reclaimable pool. */
public record ConvertedScope(String dataIdentity, String version, Path root) {
    public ConvertedScope {
        Objects.requireNonNull(dataIdentity);
        Objects.requireNonNull(version);
        root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        if (version.isBlank())
            throw new IllegalArgumentException("Conversion version must not be blank");
    }

    public static ConvertedScope of(Path dataRoot, Path cacheRoot, String version) {
        var data = dataRoot.toAbsolutePath().normalize();
        var identity = data.getFileSystem().provider().getScheme() + "\0" + data;
        return new ConvertedScope(identity, version,
            cacheRoot.resolve("converted").resolve(digest(identity)).resolve(digest(version)));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
