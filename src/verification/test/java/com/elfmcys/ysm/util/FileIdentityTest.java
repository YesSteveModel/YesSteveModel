package com.elfmcys.ysm.util;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileIdentityTest {
    @Test
    void missingBirthTimeAndFileKeyDoNotTurnMutationsIntoReplacement() {
        assertEquals(FileIdentity.observe(attributes(null, 1)), FileIdentity.observe(attributes(null, 2)));
        assertEquals(FileIdentity.observe(attributes("stable", 1)), FileIdentity.observe(attributes("stable", 2)));
        assertNotEquals(FileIdentity.observe(attributes("first", 1)), FileIdentity.observe(attributes("next", 1)));
    }

    private static BasicFileAttributes attributes(Object key, long created) {
        return new BasicFileAttributes() {
            public FileTime lastModifiedTime() { return FileTime.fromMillis(created); }
            public FileTime lastAccessTime() { return FileTime.fromMillis(100); }
            public FileTime creationTime() { return FileTime.fromMillis(created); }
            public boolean isRegularFile() { return true; }
            public boolean isDirectory() { return false; }
            public boolean isSymbolicLink() { return false; }
            public boolean isOther() { return false; }
            public long size() { return 10; }
            public Object fileKey() { return key; }
        };
    }
}
