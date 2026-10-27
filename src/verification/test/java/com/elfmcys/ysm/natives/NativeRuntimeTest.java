package com.elfmcys.ysm.natives;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeRuntimeTest {
    @Test
    void macDirectoryReportPreservesAbsentLocationsAndUnicodePaths() {
        var support = Path.of(System.getProperty("java.io.tmpdir"), "user-\uD83D\uDC31", "Application Support")
            .toAbsolutePath().normalize();
        var directories = NativeRuntime.decodeMacUserDirectories(new String[] {support.toString(), null});

        assertEquals(support, directories.applicationSupport());
        assertNull(directories.caches());
        assertNull(NativeRuntime.decodeMacUserDirectories(new String[] {null, null}).applicationSupport());
    }

    @Test
    void macDirectoryReportRejectsMalformedOrRelativeLocations() {
        assertThrows(IllegalStateException.class, () -> NativeRuntime.decodeMacUserDirectories(null));
        assertThrows(IllegalStateException.class, () -> NativeRuntime.decodeMacUserDirectories(new String[1]));
        assertThrows(IllegalStateException.class, () -> NativeRuntime.decodeMacUserDirectories(new String[3]));
        assertThrows(IllegalStateException.class,
            () -> NativeRuntime.decodeMacUserDirectories(new String[] {"relative", null}));
        assertThrows(IllegalStateException.class,
            () -> NativeRuntime.decodeMacUserDirectories(new String[] {null, ""}));
    }

    @Test
    void processorCountUsesTheSmallerPositiveDetectionAndFallsBackToJava() {
        assertEquals(6, NativeRuntime.availableProcessors(12, 6));
        assertEquals(4, NativeRuntime.availableProcessors(4, 16));
        assertEquals(8, NativeRuntime.availableProcessors(8, 8));
        assertEquals(1, NativeRuntime.availableProcessors(1, 16));
        for (var invalid : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertEquals(12, NativeRuntime.availableProcessors(12, invalid));
        }
    }

    @Test
    void normalPolicyNeedsNoNativeInitializationOrBinding() {
        assertEquals(ThreadSchedulingResult.Outcome.UNCHANGED,
            NativeRuntime.applyCurrentThreadPolicy(ThreadPolicy.NORMAL).outcome());
        assertEquals(0, ThreadPolicy.NORMAL.nativeCode());
        assertEquals(1, ThreadPolicy.BELOW_NORMAL.nativeCode());
        assertEquals(2, ThreadPolicy.LOW.nativeCode());
    }

    @Test
    void onlyAnExplicitHighResultEnablesHighDefaults() {
        for (var code : new int[] {-1, 0, 1, 2, 3, Integer.MAX_VALUE}) {
            var quality = NativeRuntime.DeviceQuality.decode(code);
            assertEquals(code == 2, quality == NativeRuntime.DeviceQuality.HIGH);
        }
    }

    @Test
    void rejectsAmbiguousSymbolSpellings() {
        assertEquals("YSM_ABI_0_1_0_unstable", NativeRuntime.nativeSymbol("0.1.0-unstable"));
        for (var version : new String[] {"0.1.0-dev.foo", "0.1.0-dev-foo", "0.1.0-dev_foo", "0.1", ""}) {
            assertThrows(IllegalArgumentException.class, () -> NativeRuntime.nativeSymbol(version));
        }
        assertNotEquals(NativeRuntime.nativeSymbol("0.1.0-unstable"), NativeRuntime.nativeSymbol("0.1.0-UNSTABLE"));
    }

    @Test
    void mapsStandardLog4jLevels() {
        assertEquals(0, NativeRuntime.JavaConfig.fromLog4j(Level.ALL).logLevel());
        assertEquals(1, NativeRuntime.JavaConfig.fromLog4j(Level.TRACE).logLevel());
        assertEquals(2, NativeRuntime.JavaConfig.fromLog4j(Level.DEBUG).logLevel());
        assertEquals(3, NativeRuntime.JavaConfig.fromLog4j(Level.INFO).logLevel());
        assertEquals(4, NativeRuntime.JavaConfig.fromLog4j(Level.WARN).logLevel());
        assertEquals(5, NativeRuntime.JavaConfig.fromLog4j(Level.ERROR).logLevel());
        assertEquals(6, NativeRuntime.JavaConfig.fromLog4j(Level.FATAL).logLevel());
        assertEquals(7, NativeRuntime.JavaConfig.fromLog4j(Level.OFF).logLevel());
    }

    @Test
    void mapsCustomLevelToNearestSupportedThreshold() {
        var custom = Level.forName("YSM_NATIVE_RUNTIME_TEST", 350);

        assertEquals(4, NativeRuntime.JavaConfig.fromLog4j(custom).logLevel());
    }

    @Test
    void packsLogLevelIntoJavaConfig() {
        assertEquals(3L, NativeRuntime.JavaConfig.fromLog4j(Level.INFO).pack());
    }

    @Test
    void rejectsInvalidLogLevel() {
        assertThrows(IllegalArgumentException.class, () -> new NativeRuntime.JavaConfig(8));
    }
}
