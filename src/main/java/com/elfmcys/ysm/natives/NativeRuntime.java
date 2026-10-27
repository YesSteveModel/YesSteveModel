package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.util.NativeLibUtil;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;

import com.sun.jna.NativeLibrary;
import org.apache.logging.log4j.Level;

import java.nio.file.Path;

import java.util.Objects;

public final class NativeRuntime {
    private static final int LOG_LEVEL_SHIFT = 0;
    private static final long LOG_LEVEL_MASK = 0x7L << LOG_LEVEL_SHIFT;
    private static final long TRACY_ENABLED_MASK = 1L;
    private static final long DEBUG_LOGGING_ENABLED_MASK = 1L << 1;
    private static final long NATIVE_CONFIG_MASK = TRACY_ENABLED_MASK | DEBUG_LOGGING_ENABLED_MASK;

    private static volatile NativeConfig nativeConfig;
    private static long initializedJavaConfig = -1;

    private NativeRuntime() {}

    public static int availableProcessors() {
        var physicalCores = nativeConfig == null ? 0 : PhysicalCoreCountHolder.VALUE;
        return availableProcessors(Runtime.getRuntime().availableProcessors(), physicalCores);
    }

    static int availableProcessors(int javaProcessors, int physicalCores) {
        return physicalCores > 0 ? Math.min(javaProcessors, physicalCores) : javaProcessors;
    }

    private static final class PhysicalCoreCountHolder {
        private static final int VALUE = queryPhysicalCoreCount();
    }

    private static int queryPhysicalCoreCount() {
        try {
            return nPhysicalCoreCount();
        } catch (UnsatisfiedLinkError | SecurityException failure) {
            return 0;
        }
    }

    public static MacUserDirectories macUserDirectories() {
        if (nativeConfig == null) {
            throw new IllegalStateException("Native runtime is not initialized");
        }
        return decodeMacUserDirectories(nMacUserDirectories());
    }

    static MacUserDirectories decodeMacUserDirectories(String[] directories) {
        if (directories == null || directories.length != 2) {
            throw new IllegalStateException("Native runtime returned an invalid macOS directory report");
        }
        return new MacUserDirectories(macDirectory(directories[0]), macDirectory(directories[1]));
    }

    private static Path macDirectory(String directory) {
        if (directory == null) {
            return null;
        }
        var path = Path.of(directory);
        if (!path.isAbsolute()) {
            throw new IllegalStateException("Native runtime returned a relative macOS directory: " + directory);
        }
        return path.normalize();
    }

    public record MacUserDirectories(Path applicationSupport, Path caches) {}

    public static ThreadSchedulingResult applyCurrentThreadPolicy(ThreadPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy == ThreadPolicy.NORMAL) {
            return ThreadSchedulingResult.unchanged();
        }
        // Reuse initialization publication without triggering NativeLibUtil's loading initializer.
        if (nativeConfig == null) {
            return ThreadSchedulingResult.unavailable("Native runtime is not initialized");
        }
        try {
            return ThreadSchedulingResult.decode(nApplyThreadPolicy(policy.nativeCode()));
        } catch (UnsatisfiedLinkError | SecurityException failure) {
            return ThreadSchedulingResult.unavailable(failure.getClass().getName());
        }
    }

    public static DeviceQuality deviceQuality() {
        return DeviceQualityHolder.VALUE;
    }

    private static final class DeviceQualityHolder {
        private static final DeviceQuality VALUE = queryDeviceQuality();
    }

    private static DeviceQuality queryDeviceQuality() {
        if (!NativeLibUtil.isAvailable()) {
            return DeviceQuality.UNKNOWN;
        }
        try {
            return DeviceQuality.decode(nClassifyDevice(Runtime.getRuntime().maxMemory()));
        } catch (UnsatisfiedLinkError | SecurityException failure) {
            return DeviceQuality.UNKNOWN;
        }
    }

    public enum DeviceQuality {
        UNKNOWN, LOW, HIGH;

        static DeviceQuality decode(int code) {
            return switch (code) {
                case 1 -> LOW;
                case 2 -> HIGH;
                default -> UNKNOWN;
            };
        }
    }

    public static synchronized NativeConfig initialize(JavaConfig javaConfig) {
        Objects.requireNonNull(javaConfig, "javaConfig");
        var packedJavaConfig = javaConfig.pack();
        if (nativeConfig != null) {
            if (initializedJavaConfig != packedJavaConfig) {
                throw new IllegalStateException("Native runtime was initialized with different Java config");
            }
            return nativeConfig;
        }

        var packedNativeConfig = nInitialize(packedJavaConfig);
        var decoded = NativeConfig.decode(packedNativeConfig);
        initializedJavaConfig = packedJavaConfig;
        nativeConfig = decoded;
        return decoded;
    }

    public static synchronized void load(String libraryPath) {
        var path = Path.of(libraryPath);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Native library path must be absolute: " + path);
        }
        var symbol = nativeSymbol(AbiVersion.NATIVE);
        var library = NativeLibrary.getInstance(path.normalize().toString());
        try {
            try {
                library.getGlobalVariableAddress(symbol);
            } catch (UnsatisfiedLinkError cause) {
                var failure = new UnsatisfiedLinkError(
                    "Native ABI mismatch: expected " + AbiVersion.NATIVE + " (" + symbol + ") in " + path);
                failure.initCause(cause);
                throw failure;
            }
            // The JVM holds its own library reference before the JNA handle closes.
            System.load(path.normalize().toString());
        } finally {
            library.dispose();
        }
    }

    static String nativeSymbol(String version) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9]+)?")) {
            throw new IllegalArgumentException("Invalid native ABI: " + version);
        }
        return "YSM_ABI_" + version.replace('.', '_').replace('-', '_');
    }

    static boolean isTracyEnabled() {
        var config = nativeConfig;
        return config != null && config.tracyEnabled();
    }

    public record JavaConfig(int logLevel) {
        public JavaConfig {
            if (logLevel < 0 || logLevel > 7) {
                throw new IllegalArgumentException("Invalid native log level: " + logLevel);
            }
        }

        public static JavaConfig fromLog4j(Level level) {
            Objects.requireNonNull(level, "level");
            if (level == Level.ALL) {
                return new JavaConfig(0);
            }
            if (level == Level.OFF) {
                return new JavaConfig(7);
            }

            int value = level.intLevel();
            if (value >= Level.TRACE.intLevel()) {
                return new JavaConfig(1);
            }
            if (value >= Level.DEBUG.intLevel()) {
                return new JavaConfig(2);
            }
            if (value >= Level.INFO.intLevel()) {
                return new JavaConfig(3);
            }
            if (value >= Level.WARN.intLevel()) {
                return new JavaConfig(4);
            }
            if (value >= Level.ERROR.intLevel()) {
                return new JavaConfig(5);
            }
            if (value >= Level.FATAL.intLevel()) {
                return new JavaConfig(6);
            }
            return new JavaConfig(7);
        }

        long pack() {
            return (long)logLevel << LOG_LEVEL_SHIFT;
        }
    }

    public record NativeConfig(boolean tracyEnabled, boolean debugLoggingEnabled) {
        private static NativeConfig decode(long packed) {
            if ((packed & ~NATIVE_CONFIG_MASK) != 0) {
                throw new IllegalStateException("Native runtime returned unknown config bits: " + packed);
            }
            return new NativeConfig((packed & TRACY_ENABLED_MASK) != 0, (packed & DEBUG_LOGGING_ENABLED_MASK) != 0);
        }
    }

    private static native long nInitialize(long javaConfig);

    private static native int nPhysicalCoreCount();

    private static native String[] nMacUserDirectories();

    private static native int nClassifyDevice(long maxHeapBytes);

    private static native long[] nApplyThreadPolicy(int policy);
}
