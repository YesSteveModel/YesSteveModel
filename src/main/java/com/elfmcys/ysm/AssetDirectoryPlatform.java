package com.elfmcys.ysm;

import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.util.NativeLibUtil;
import java.nio.file.Path;
import java.util.Locale;

/** Host facts only; launcher admission does not participate in directory selection. */
public final class AssetDirectoryPlatform {
    private AssetDirectoryPlatform() {}

    public enum Kind { WINDOWS, LINUX, ANDROID, MACOS, UNSUPPORTED }

    public record MacDirectories(Path applicationSupport, Path caches) {}

    public static Kind current() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) return Kind.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return Kind.MACOS;
        if (os.contains("linux")) {
            return NativeLibUtil.detectLibc() == NativeLibUtil.LibcType.BIONIC ? Kind.ANDROID : Kind.LINUX;
        }
        return Kind.UNSUPPORTED;
    }

    public static MacDirectories queryMacDirectories() {
        var directories = NativeRuntime.macUserDirectories();
        return new MacDirectories(directories.applicationSupport(), directories.caches());
    }
}
