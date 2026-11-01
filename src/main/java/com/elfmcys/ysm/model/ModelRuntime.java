package com.elfmcys.ysm.model;

import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.ProcessAssetPaths;
import com.elfmcys.ysm.YesSteveModel;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Lifecycle bridge between Forge common setup and the process model-system owner.
 *
 * <p>Execution: startup and process shutdown must not overlap. Synchronization protects publication,
 * not the lifetime of a system returned to a caller.
 *
 * <p>Seam: implements the [process lifecycle](docs/architecture/runtime/overview.md#结束与替换); game-service and connection shutdown do not close this owner.
 */
public final class ModelRuntime {
    private static ModelSystem system;
    private static boolean shutdownHookRegistered;
    private static final ProcessAssetPaths directories = new ProcessAssetPaths(ModelRuntime::discoverDirectories);

    private ModelRuntime() {}

    /**
     * Publishes a complete system once; repeated calls keep the published instance.
     *
     * <p>Requires: Forge paths and the active mod container are available. Ordinary catalog activation
     * and required client preparation have separate owners.
     *
     * <p>Failure: construction failure propagates without publishing a partial system.
     */
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

    /**
     * Returns the borrowed process system; callers do not acquire a close right or a lifetime pin.
     *
     * @throws IllegalStateException if initialization has not published a system, or shutdown detached it
     */
    public static synchronized ModelSystem system() {
        if (system == null) {
            throw new IllegalStateException("Model runtime has not been initialized");
        }
        return system;
    }

    /** Returns the process directory configuration, retained across system close and reconstruction. */
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

    /**
     * Detaches the current system before closing it outside the publication monitor.
     *
     * <p>Execution: may wait for owned work without a timeout. A concurrent second close can return
     * before the first close finishes; callers must not treat that return as a join.
     *
     * <p>Failure: system cleanup failures propagate after detachment. Repeated calls do not retry cleanup.
     */
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
