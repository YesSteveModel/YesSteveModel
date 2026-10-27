package com.elfmcys.ysm.model;

import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.ProcessAssetPaths;
import com.elfmcys.ysm.YesSteveModel;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

/** Lifecycle bridge between Forge common setup and the process model-system owner. */
public final class ModelRuntime {
    private static ModelSystem system;
    private static boolean shutdownHookRegistered;
    private static final ProcessAssetPaths directories = new ProcessAssetPaths(ModelRuntime::discoverDirectories);

    private ModelRuntime() {}

    public static synchronized void initialize() {
        if (system != null) {
            return;
        }
        var created = ModelSystem.openDefault(directories.get());
        try {
            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(ModelRuntime::close, "YSM Model Runtime Shutdown"));
                shutdownHookRegistered = true;
            }
            system = created;
        } catch (RuntimeException | Error error) {
            try {
                created.close();
            } catch (RuntimeException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    public static synchronized ModelSystem system() {
        if (system == null) {
            throw new IllegalStateException("Model runtime has not been initialized");
        }
        return system;
    }

    public static AssetPaths paths() {
        return directories.get();
    }

    private static AssetPaths discoverDirectories() {
        var environment = java.util.Map.copyOf(System.getenv());
        var builtin = ModList.get().getModFileById(YesSteveModel.MOD_ID).getFile()
            .findResource("assets", YesSteveModel.MOD_ID, "builtin");
        AssetDirectoryPlatform.MacDirectories mac = null;
        var platform = environment.containsKey("YSM_HOME") ? AssetDirectoryPlatform.Kind.UNSUPPORTED
            : AssetDirectoryPlatform.current();
        if (!environment.containsKey("YSM_HOME") && platform == AssetDirectoryPlatform.Kind.MACOS) {
            try {
                mac = AssetDirectoryPlatform.queryMacDirectories();
            } catch (RuntimeException | LinkageError error) {
                YesSteveModel.LOGGER.error("Global model directory query failed", error);
            }
        }
        var paths = AssetPaths.discover(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get(), builtin,
            environment, platform, mac);
        for (var diagnostic : paths.diagnostics()) {
            YesSteveModel.LOGGER.error("Global model directory {} failed at {}: {}", diagnostic.operation(),
                diagnostic.path(), diagnostic.message());
        }
        return paths;
    }

    public static void close() {
        final ModelSystem current;
        synchronized (ModelRuntime.class) {
            current = system;
            system = null;
        }
        if (current != null) {
            current.close();
        }
    }
}
