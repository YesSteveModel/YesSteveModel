package com.elfmcys.ysm.testutil;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.natives.NativeRuntime;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Files;
import java.nio.file.Path;

public final class NativeLibraryExtension implements BeforeAllCallback {
    private static final String NATIVE_PATH_ENV = "YSM_NATIVE_PATH";

    private static boolean loaded;

    @Override
    public void beforeAll(ExtensionContext context) {
        loadNativeLibrary();
    }

    public static synchronized void loadNativeLibrary() {
        if (loaded) {
            return;
        }

        String value = System.getenv(NATIVE_PATH_ENV);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(NATIVE_PATH_ENV + " is not set");
        }

        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalStateException(NATIVE_PATH_ENV + " must be an absolute path: " + value);
        }
        path = path.normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(NATIVE_PATH_ENV + " is not a file: " + path);
        }

        YesSteveModel.LOGGER.error("Loading native lib: {}", path);
        try {
            NativeRuntime.load(path.toString());
            NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(Level.INFO));
        } catch (LinkageError | SecurityException exception) {
            throw new IllegalStateException("Failed to load native library: " + path, exception);
        }
        loaded = true;
    }
}
