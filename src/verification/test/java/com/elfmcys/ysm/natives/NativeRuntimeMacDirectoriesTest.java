package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(NativeLibraryExtension.class)
class NativeRuntimeMacDirectoriesTest {
    @Test
    void boundDirectoryQueryReportsTheCurrentPlatform() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac") && !os.contains("darwin")) {
            assertThrows(IllegalStateException.class, NativeRuntime::macUserDirectories);
            return;
        }
        var directories = NativeRuntime.macUserDirectories();
        assertNotNull(directories.applicationSupport());
        assertTrue(directories.applicationSupport().isAbsolute());
        if (directories.caches() != null) assertTrue(directories.caches().isAbsolute());
    }
}
