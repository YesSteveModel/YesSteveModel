package com.elfmcys.ysm.natives;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.NativeLibUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
public class NativeRuntimeDeviceIntegrationTest {
    @TempDir Path directory;

    @Test
    void realRegistrationPreservesLongHeapValuesAndConservativeErrors() throws Exception {
        NativeLibraryExtension.loadNativeLibrary();
        var entry = NativeRuntime.class.getDeclaredMethod("nClassifyDevice", long.class);
        entry.setAccessible(true);
        for (var invalid : new long[] {0, -1, Long.MIN_VALUE}) assertEquals(0, entry.invoke(null, invalid));
        assertEquals(1, entry.invoke(null, 4L << 30));
        int result = (int)entry.invoke(null, Long.MAX_VALUE);
        assertTrue(result >= 0 && result <= 2);
        if (System.getProperty("os.name").startsWith("Windows")) assertTrue(result != 0);
    }

    @Test
    void independentProcessesCacheOneRealCallAndReloadExplicitConfig() throws Exception {
        var library = Path.of(System.getenv("YSM_NATIVE_PATH"));
        for (var heap : new String[] {"256m", "6g"}) {
            var config = directory.resolve(heap + ".toml");
            String first = run("native", heap, library, config);
            assertEquals(1, first.lines().filter(line -> line.contains("Device classification requested:")).count(), first);
            if (heap.equals("256m")) assertTrue(first.contains("DEVICE_DEFAULTS=LOW"), first);
            String restarted = run("native", heap, library, config);
            assertTrue(restarted.contains("EXPLICIT_VALUES_PRESERVED"), restarted);
            assertEquals(1, restarted.lines().filter(line -> line.contains("Device classification requested:")).count(), restarted);
        }
    }

    @Test
    void unavailableAndMissingEntryResultsStayCached() throws Exception {
        for (var mode : new String[] {"unavailable", "missing"}) {
            var output = run(mode, "6g", Path.of(System.getenv("YSM_NATIVE_PATH")), directory.resolve(mode + ".toml"));
            assertTrue(output.contains("DEVICE_DEFAULTS=UNKNOWN"), output);
            assertEquals(0, output.lines().filter(line -> line.contains("Device classification requested:")).count(), output);
        }
    }

    private String run(String mode, String heap, Path library, Path config) throws Exception {
        var outputFile = directory.resolve(mode + "-" + heap + "-" + System.nanoTime() + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8", "-Xmx" + heap, "-cp", System.getProperty("java.class.path"), getClass().getName(),
            mode, library.toString(), config.toString()).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Device defaults process timed out");
        }
        var output = Files.readString(outputFile);
        assertEquals(0, process.exitValue(), output);
        System.out.println(output);
        return output;
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("native")) {
            NativeRuntime.load(args[1]);
            NativeRuntime.initialize(new NativeRuntime.JavaConfig(3));
        }
        var available = NativeLibUtil.class.getDeclaredField("AVAILABLE");
        available.setAccessible(true);
        available.setBoolean(null, !args[0].equals("unavailable"));
        var quality = NativeRuntime.deviceQuality();
        System.out.println("DEVICE_DEFAULTS=" + quality);
        if (!args[0].equals("native")) {
            NativeRuntime.load(args[1]);
            NativeRuntime.initialize(new NativeRuntime.JavaConfig(3));
            available.setBoolean(null, true);
        }
        var file = Path.of(args[2]);
        boolean existing = Files.exists(file);
        try (var config = CommentedFileConfig.of(file)) {
            if (existing) config.load();
            for (int repeat = 0; repeat < 10; repeat++) {
                ClientConfig.init().setConfig(config);
                if (NativeRuntime.deviceQuality() != quality) throw new AssertionError("Device tier changed");
                boolean expectedEager = existing ? false : quality == NativeRuntime.DeviceQuality.HIGH;
                int expectedRetention = existing ? 123 : quality == NativeRuntime.DeviceQuality.HIGH ? 30 : 10;
                if (ClientConfig.MODEL_SCREEN_EAGER_LOADING.get() != expectedEager ||
                    ClientConfig.MODEL_RETENTION_COUNT.get() != expectedRetention) throw new AssertionError("Config mismatch");
            }
            if (existing) System.out.println("EXPLICIT_VALUES_PRESERVED");
            config.set("general.ModelScreenEagerLoading", false);
            config.set("general.ModelRetentionCount", 123);
            config.save();
        }
    }
}
