package com.elfmcys.ysm.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** A best-effort replacement observation; a provider need not expose a stable file key. */
public final class FileIdentity {
    private FileIdentity() {}

    public static Object read(Path path) throws IOException {
        var absolute = path.toAbsolutePath().normalize();
        var attributes = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
            || !absolute.equals(absolute.toRealPath()))
            throw new IOException("Linked filesystem entry: " + path);
        return observe(attributes);
    }

    static Object observe(BasicFileAttributes attributes) {
        return new Observation(attributes.fileKey(), attributes.isDirectory());
    }

    private record Observation(Object fileKey, boolean directory) {}
}
