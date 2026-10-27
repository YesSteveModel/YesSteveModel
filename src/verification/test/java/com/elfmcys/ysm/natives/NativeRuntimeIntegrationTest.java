package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.compatibility.AbiVersion;
import com.sun.jna.NativeLibrary;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeRuntimeIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void processorLimitsAndEarlyFallbackWorkInIndependentProcesses() throws Exception {
        for (var javaProcessors : new int[] {1, 3, 64}) {
            var outputFile = directory.resolve("processors-" + javaProcessors + ".log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:ActiveProcessorCount=" + javaProcessors, "-cp", System.getProperty("java.class.path"),
                getClass().getName(), System.getenv("YSM_NATIVE_PATH"), Integer.toString(javaProcessors))
                .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Processor count process timed out");
            }
            assertEquals(0, process.exitValue(), Files.readString(outputFile));
        }
    }

    public static void main(String[] args) throws Exception {
        var javaProcessors = Integer.parseInt(args[1]);
        if (NativeRuntime.availableProcessors() != javaProcessors) {
            throw new AssertionError("Uninitialized runtime did not preserve the Java processor count");
        }
        NativeRuntime.load(args[0]);
        NativeRuntime.initialize(new NativeRuntime.JavaConfig(3));
        var entry = NativeRuntime.class.getDeclaredMethod("nPhysicalCoreCount");
        entry.setAccessible(true);
        var physicalCores = (int)entry.invoke(null);
        if (physicalCores <= 0) {
            throw new AssertionError("Physical core query failed");
        }
        for (var repeat = 0; repeat < 3; repeat++) {
            if (NativeRuntime.availableProcessors() != Math.min(javaProcessors, physicalCores)) {
                throw new AssertionError("Initialized runtime did not respect both processor limits");
            }
        }
    }

    @Test
    void physicalCoreEntryIsRegisteredAndLimitsJavaProcessorCount() throws Exception {
        var entry = NativeRuntime.class.getDeclaredMethod("nPhysicalCoreCount");
        entry.setAccessible(true);
        var physicalCores = (int)entry.invoke(null);
        assertTrue(physicalCores > 0);
        assertEquals(Math.min(Runtime.getRuntime().availableProcessors(), physicalCores),
            NativeRuntime.availableProcessors());
    }

    @Test
    void probesTheCompleteNativeAbiWithoutNormalization() {
        var library = NativeLibrary.getInstance(System.getenv("YSM_NATIVE_PATH"));
        try {
            assertTrue(library.getGlobalVariableAddress(NativeRuntime.nativeSymbol(AbiVersion.NATIVE)) != null);
            for (var candidate : new String[] {AbiVersion.NATIVE.replace("unstable", "UNSTABLE"),
                AbiVersion.NATIVE.replace("-unstable", ""), "0.1.1-unstable"}) {
                org.junit.jupiter.api.Assertions.assertThrows(UnsatisfiedLinkError.class,
                    () -> library.getGlobalVariableAddress(NativeRuntime.nativeSymbol(candidate)));
            }
        } finally {
            library.dispose();
        }
    }

    @Test
    void initializesIdempotentlyWithTheSameJavaConfig() {
        assertDoesNotThrow(() -> NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(Level.INFO)));
    }

    @Test
    void opensProfilerZones() {
        assertDoesNotThrow(() -> {
            try (var ignored = NativeProfiler.beginAnimatableUpdate()) {
            }
            try (var ignored = NativeProfiler.beginFallbackVertexWrite()) {
            }
            try (var ignored = NativeProfiler.beginAnimatableUpdate()) {
            }
        });
    }
}
