package com.elfmcys.ysm.model.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class RegularFileProbe {
    private RegularFileProbe() {}

    static boolean exists(Path file) throws IOException {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class).isRegularFile();
        } catch (NoSuchFileException missing) {
            return false;
        }
    }
}
