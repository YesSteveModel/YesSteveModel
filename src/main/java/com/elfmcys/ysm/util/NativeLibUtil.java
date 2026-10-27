package com.elfmcys.ysm.util;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

public final class NativeLibUtil {
    /**
     * Native 库在 Mod 包内的路径
     */
    private static final String LIB_PATH = "/META-INF/native/";

    /**
     * 各平台 jar 内 Native 库文件名
     */
    private static final String WINDOWS_LIB_NAME = "ysm.dll";
    private static final String LINUX_LIB_NAME = "libysm.so";
    private static final String ANDROID_LIB_NAME = "libysm-android.so";

    /**
     * 在开发环境中运行时，Native 库的路径环境变量
     */
    private static final String DEV_LIB_ENV = "YSM_NATIVE_PATH";

    /**
     * X86 64 位架构标识
     */
    private static final String AMD64_ARCH_1 = "amd64";
    private static final String AMD64_ARCH_2 = "x86_64";

    /**
     * ARM 64 位架构标识
     */
    private static final String ARM64_ARCH = "aarch64";

    /**
     * Android 环境下的 Java 启动器运行时提供的必须的环境变量
     */
    private static final String MOD_ANDROID_RUNTIME_ENV = "MOD_ANDROID_RUNTIME";

    /**
     * FCL 启动器版本环境变量
     */
    private static final String FCL_VERSION_CODE_ENV = "FCL_VERSION_CODE";

    /**
     * Zalith 2 启动器版本环境变量
     */
    private static final String ZALITH_VERSION_CODE_ENV = "ZALITH_VERSION_CODE";

    /**
     * Zalith 2 启动器最低支持版本号
     */
    private static final int ZALITH_2_MIN_VERSION = 190000;

    /**
     * 当前环境是否可以加载运行 Native 库
     */
    private static boolean AVAILABLE = false;

    /**
     * 是否运行于手机平台，用于添加特定兼容性内容
     */
    private static boolean MOBILE_PLATFORM = false;

    /**
     * 不支持当前平台的提示信息
     */
    private static Component UNSUPPORTED_MSG;
    private static String UNSUPPORTED_MSG_KEY;
    private static Object[] UNSUPPORTED_MSG_CTX;
    private static String UNSUPPORTED_MSG_STR;

    /**
     * 加载 Native 核心库
     */
    public static void load() throws IOException {
        String libPath = getDevLibraryPath();

        if (libPath == null) {
            libPath = setupLib();
            if (libPath == null) {
                return;
            }
        }

        if (!loadLibrary(libPath)) {
            return;
        }

        AVAILABLE = true;
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static boolean isMobilePlatform() {
        return MOBILE_PLATFORM;
    }

    public static Component getUnsupportedMsg() {
        return UNSUPPORTED_MSG;
    }

    public static String getUnsupportedMsgStr() {
        return UNSUPPORTED_MSG_STR;
    }

    /**
     * 获取开发环境中的库路径
     */
    @Nullable
    private static String getDevLibraryPath() {
        if (!FMLEnvironment.production) {
            String libPath = System.getenv(DEV_LIB_ENV);
            return StringUtil.isNullOrEmpty(libPath) ? null : libPath;
        }
        return null;
    }

    /**
     * 加载 Native 库文件
     */
    private static boolean loadLibrary(String libPath) {
        YesSteveModel.LOGGER.info("Loading native lib: {}", libPath);
        Throwable failure;
        try {
            failure = loadLibraryOnLifetimeThread(libPath);
        } catch (Throwable e) {
            return failLibraryLoad(e);
        }
        if (failure != null) {
            return failLibraryLoad(failure);
        }
        return true;
    }

    @Nullable
    private static Throwable loadLibraryOnLifetimeThread(String libPath) {
        var initialized = new CompletableFuture<Throwable>();
        var lifetime = new CountDownLatch(1);
        var thread = new Thread(() -> {
            try {
                NativeRuntime.load(libPath);
                NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(YesSteveModel.LOGGER.getLevel()));
            } catch (Throwable failure) {
                initialized.complete(failure);
                return;
            }
            initialized.complete(null);
            // make mimalloc happy ＾－＾
            while (true) {
                try {
                    lifetime.await();
                    return;
                } catch (InterruptedException ignored) {
                }
            }
        }, "YSM Native Holder");
        thread.setDaemon(true);
        thread.start();
        return initialized.join();
    }

    private static boolean failLibraryLoad(Throwable failure) {
        YesSteveModel.LOGGER.error("Failed to load native lib", failure);
        setUnsatisfiedRuntimeEnvironmentMsg(failure.getMessage());
        return false;
    }

    /**
     * 设置并提取 Native 库到本地文件系统
     */
    @Nullable
    private static String setupLib() throws IOException {
        String modVersion = getModVersion();

        // 检测当前平台和架构
        PlatformConfig config = detectPlatformConfig(modVersion);
        if (config == null) {
            return null;
        }

        // 读取嵌入的库文件
        byte[] libData = readEmbeddedFile(LIB_PATH + config.libFileName);
        if (libData == null) {
            setUnsupportedBuildMsg();
            return null;
        }

        // 准备提取目录
        Path extractPath = prepareExtractPath(config.targetDir);
        String libPath = extractPath.resolve(config.outputFileName).toAbsolutePath().normalize().toString();

        // 写入库文件
        writeLibData(libPath, libData);

        return libPath;
    }

    /**
     * 检测平台配置
     */
    @Nullable
    private static PlatformConfig detectPlatformConfig(String modVersion) {
        boolean isX64 = isX64Architecture();
        boolean isAArch64 = isAArch64Architecture();

        if (SystemUtils.IS_OS_WINDOWS) {
            return detectWindowsConfig(modVersion, isX64);
        } else if (SystemUtils.IS_OS_LINUX) {
            return detectLinuxConfig(modVersion, isX64, isAArch64);
        } else {
            setUnsupportedPlatformMsg(SystemUtils.OS_NAME);
            return null;
        }
    }

    /**
     * 检测 Windows 平台配置
     */
    @Nullable
    private static PlatformConfig detectWindowsConfig(String modVersion, boolean isX64) {
        if (!isX64) {
            setUnsupportedPlatformMsg(null);
            return null;
        }

        return new PlatformConfig(WINDOWS_LIB_NAME, "ysm-core-" + modVersion + ".dll",
            Path.of(System.getProperty("java.io.tmpdir"), "ysm", "unstable"));
    }

    /**
     * 检测 Linux 平台配置
     */
    @Nullable
    private static PlatformConfig detectLinuxConfig(String modVersion, boolean isX64, boolean isAArch64) {
        LibcType libcType = detectLibc();

        if (libcType == LibcType.GNU) {
            return detectGnuLinuxConfig(modVersion, isX64);
        } else if (libcType == LibcType.BIONIC) {
            return detectAndroidConfig(isAArch64);
        } else {
            setUnsupportedPlatformMsg("Linux with unsupported libc");
            return null;
        }
    }

    /**
     * 检测 GNU/Linux 配置
     */
    @Nullable
    private static PlatformConfig detectGnuLinuxConfig(String modVersion, boolean isX64) {
        if (!isX64) {
            setUnsupportedPlatformMsg(null);
            return null;
        }

        return new PlatformConfig(LINUX_LIB_NAME, "libysm-core-" + modVersion + ".so",
            Path.of(System.getProperty("user.home"), ".ysm", "unstable"));
    }

    /**
     * 检测 Android 配置
     */
    @Nullable
    private static PlatformConfig detectAndroidConfig(boolean isAArch64) {
        if (!isAArch64) {
            setUnsupportedPlatformMsg(null);
            return null;
        }

        String modAndroidRuntimeDir = System.getenv(MOD_ANDROID_RUNTIME_ENV);
        if (modAndroidRuntimeDir == null) {
            setUnsupportedLauncherMsg();
            return null;
        }

        MOBILE_PLATFORM = true;
        return new PlatformConfig(ANDROID_LIB_NAME, "libysm-core.so",
            Path.of(modAndroidRuntimeDir).resolve("unstable"));
    }

    /**
     * 准备提取目录
     */
    private static Path prepareExtractPath(Path preferredPath) {
        try {
            if (!Files.isDirectory(preferredPath)) {
                Files.createDirectories(preferredPath);
            }
            return preferredPath;
        } catch (Throwable t) {
            YesSteveModel.LOGGER.warn("Failed to create preferred directory, using fallback", t);
            return FMLPaths.GAMEDIR.get().resolve(YesSteveModel.MOD_ID).resolve("cache").resolve("unstable");
        }
    }

    private static String getModVersion() {
        return ModList.get().getModFileById(YesSteveModel.MOD_ID).getFile().getModInfos().get(0).getVersion()
            .toString();
    }

    /**
     * 检查是否为 x86-64 架构
     */
    private static boolean isX64Architecture() {
        return SystemUtils.OS_ARCH.equals(AMD64_ARCH_1) || SystemUtils.OS_ARCH.equals(AMD64_ARCH_2);
    }

    /**
     * 检查是否为 ARM64 架构
     */
    private static boolean isAArch64Architecture() {
        return SystemUtils.OS_ARCH.equals(ARM64_ARCH);
    }

    /**
     * 写入库文件数据（考虑多实例并发）
     */
    private static void writeLibData(String libPath, byte[] libData) throws IOException {
        File libFile = new File(libPath);

        if (libFile.exists()) {
            byte[] existingLibData = FileUtils.readFileToByteArray(libFile);
            if (Arrays.equals(libData, existingLibData)) {
                // 文件已存在且内容相同，无需重写
                return;
            }
        }

        FileUtils.writeByteArrayToFile(libFile, libData, false);
    }

    /**
     * 从 JAR 中读取嵌入的文件
     */
    private static byte @Nullable [] readEmbeddedFile(String filePath) throws IOException {
        URL url = YesSteveModel.class.getResource(filePath);
        if (url == null) {
            return null;
        }

        try (InputStream stream = url.openStream()) {
            return IOUtils.readFully(stream, stream.available());
        }
    }

    /**
     * 检测 Linux 系统的 C 库类型
     */
    public static LibcType detectLibc() {
        try {
            NativeLibrary lib = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);
            if (lib != null) {
                // 检测 Android Bionic
                if (hasFunction(lib, "android_set_abort_message")) {
                    return LibcType.BIONIC;
                }
                // 检测 GNU libc
                if (hasFunction(lib, "gnu_get_libc_version")) {
                    return LibcType.GNU;
                }
            }
        } catch (Throwable e) {
            YesSteveModel.LOGGER.error("Unable to detect libc type", e);
        }
        return LibcType.UNSUPPORTED;
    }

    /**
     * 检查库中是否存在指定函数
     */
    private static boolean hasFunction(NativeLibrary lib, String functionName) {
        try {
            return lib.getFunction(functionName) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setUnsupportedPlatformMsg(@Nullable String hint) {
        if (hint == null) {
            hint = SystemUtils.OS_NAME + " " + SystemUtils.OS_ARCH;
        }
        UNSUPPORTED_MSG = Component.translatable("error.yes_steve_model.unsupported_platform", hint);
        UNSUPPORTED_MSG_KEY = "error.yes_steve_model.unsupported_platform_ext";
        UNSUPPORTED_MSG_CTX = new Object[] {hint};
        UNSUPPORTED_MSG_STR = "[YSM] Current platform is unsupported: " + hint;
    }

    private static void setUnsatisfiedRuntimeEnvironmentMsg(@NotNull String hint) {
        UNSUPPORTED_MSG = Component.translatable("error.yes_steve_model.unsatisfied_runtime_env", hint);
        UNSUPPORTED_MSG_KEY = "error.yes_steve_model.unsatisfied_runtime_env_ext";
        UNSUPPORTED_MSG_CTX = new Object[] {hint};
        UNSUPPORTED_MSG_STR = "[YSM] Unsatisfied runtime environment: " + hint;
    }

    private static void setUnsupportedBuildMsg() {
        String hint = SystemUtils.OS_NAME + " " + SystemUtils.OS_ARCH;
        UNSUPPORTED_MSG = Component.translatable("error.yes_steve_model.unsatisfied_build", hint);
        UNSUPPORTED_MSG_KEY = "error.yes_steve_model.unsatisfied_build_ext";
        UNSUPPORTED_MSG_CTX = new Object[] {hint};
        UNSUPPORTED_MSG_STR = "[YSM] This build does not support current platform: " + hint;
    }

    private static void setUnsupportedLauncherMsg() {
        // FCL 版本信息
        String fclVersion = System.getenv(FCL_VERSION_CODE_ENV);
        if (StringUtils.isNotBlank(fclVersion)) {
            UNSUPPORTED_MSG = Component.translatable("error.yes_steve_model.old_launcher", "FCL", "1.2.6.7");
            UNSUPPORTED_MSG_KEY = "error.yes_steve_model.old_launcher_ext";
            UNSUPPORTED_MSG_CTX = new Object[] {"FCL", "1.2.6.7"};
            UNSUPPORTED_MSG_STR = "[YSM] Current FCL launcher is old version";
            return;
        }

        // Zalith 版本信息
        String zalithVersion = System.getenv(ZALITH_VERSION_CODE_ENV);
        if (StringUtils.isNotBlank(zalithVersion)) {
            int version = Integer.parseInt(zalithVersion);
            if (version < ZALITH_2_MIN_VERSION) {
                UNSUPPORTED_MSG = Component.translatable("error.yes_steve_model.old_launcher", "Zalith 1", "1.4.1.1");
                UNSUPPORTED_MSG_KEY = "error.yes_steve_model.old_launcher_ext";
                UNSUPPORTED_MSG_CTX = new Object[] {"Zalith 1", "1.4.1.1"};
                UNSUPPORTED_MSG_STR = "[YSM] Current Zalith 1 launcher is old version";
            } else {
                UNSUPPORTED_MSG =
                    Component.translatable("error.yes_steve_model.old_launcher", "Zalith 2", "2.0.0_beta-20251118a");
                UNSUPPORTED_MSG_KEY = "error.yes_steve_model.old_launcher_ext";
                UNSUPPORTED_MSG_CTX = new Object[] {"Zalith 2", "2.0.0_beta-20251118a"};
                UNSUPPORTED_MSG_STR = "[YSM] Current Zalith 2 launcher is old version";
            }
            return;
        }

        UNSUPPORTED_MSG = Component.translatable("error.yes_steve_model.unsupported_launcher");
        UNSUPPORTED_MSG_STR = "[YSM] Current launcher is unsupported";
    }

    @SuppressWarnings("removal")
    public static ModLoadingWarning getUnavailableWarning() {
        return new ModLoadingWarning(ModLoadingContext.get().getActiveContainer().getModInfo(),
            ModLoadingStage.SIDED_SETUP, UNSUPPORTED_MSG_KEY, UNSUPPORTED_MSG_CTX);
    }

    /**
     * 平台配置信息
     *
     * @param libFileName JAR 内的库文件名
     * @param outputFileName 输出的库文件名
     * @param targetDir 目标目录
     */
    private record PlatformConfig(String libFileName, String outputFileName, Path targetDir) {
    }

    /**
     * C 库类型枚举
     */
    public enum LibcType {
        /**
         * 不支持的类型
         */
        UNSUPPORTED,
        /**
         * GNU C Library (glibc) - 标准 Linux 下的 C 标准库实现
         */
        GNU,
        /**
         * MUSL C Library - 轻量级的 C 标准库实现，常用于 Alpine Linux 等轻量级发行版
         */
        MUSL,
        /**
         * Bionic C Library - Android 系统下的 C 标准库实现
         */
        BIONIC
    }
}
