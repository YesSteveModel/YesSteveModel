package com.elfmcys.ysm.model;

import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Process composition root for immutable contracts, storage, and shared catalogs.
 *
 * <p>Ownership: accessors borrow child owners. Their return does not retain the system or transfer a
 * close right. Each child keeps its own execution and resource-retention contract.
 *
 * <p>Execution: callers arrange lifecycle exclusion for child use. The open check is not a lease;
 * attachment and shutdown sealing are serialized, while cleanup runs outside this monitor.
 *
 * <p>Seam: implements [process ownership](docs/architecture/runtime/overview.md#运行作用域与所有权) and [ordered shutdown](docs/architecture/runtime/overview.md#结束与替换).
 */
public final class ModelSystem implements AutoCloseable {
    private final ModelStorageInfra storage;
    private final BuiltinModelCatalog builtins;
    private final ReloadableModelCatalog catalog;
    private final PreviewStorage previews;
    private final ServerResourceRuntime serverResources;
    private AutoCloseable clientRuntime;
    private boolean closed;

    private ModelSystem(ModelStorageInfra storage, BuiltinModelCatalog builtins, ReloadableModelCatalog catalog) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.builtins = Objects.requireNonNull(builtins, "builtins");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        previews = PreviewStorage.routed();
        serverResources = new ServerResourceRuntime(previews,
            error -> YesSteveModel.LOGGER.warn("Server model source instance became corrupted", error));
        storage.regions().forEach(region -> region.previews()
            .storedObserver(containerId -> serverResources.invalidatePreview(region.previews(), containerId)));
    }

    /**
     * Creates an unactivated system owned by the caller until process publication.
     *
     * <p>Requires: non-null process paths and an active mod container. Construction performs storage
     * and builtin I/O synchronously; it does not start an ordinary catalog scan or prepare client Ready.
     *
     * @throws UncheckedIOException if the builtin contract cannot be read
     */
    public static ModelSystem openDefault(AssetPaths paths) {
        final BuiltinModelIndex contract;
        try {
            contract = BuiltinModelIndex.read(paths.builtinIndex());
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to load the builtin model contract", error);
        }
        var fullModVersion = Objects.requireNonNull(YesSteveModel.MOD, "Active mod container is unavailable")
            .getModInfo().getVersion().toString();
        var storage = ModelStorageInfra.open(paths, fullModVersion);
        BuiltinModelCatalog builtins = null;
        ReloadableModelCatalog catalog = null;
        try {
            builtins = BuiltinModelCatalog.open(contract, paths.builtinModelsResource(),
                ContentStorageBinding.builtin(storage.local()));
            catalog = new ReloadableModelCatalog(storage, ModelCatalogSources.sources(paths), builtins);
            return new ModelSystem(storage, builtins, catalog);
        } catch (RuntimeException | Error error) {
            if (catalog != null) {
                try {
                    catalog.close();
                } catch (RuntimeException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            if (builtins != null) {
                try {
                    builtins.close();
                } catch (RuntimeException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            closeOwned(storage, error);
            throw error;
        }
    }

    public BuiltinModelIndex builtinContract() {
        requireOpen();
        return builtins.contract();
    }

    public ModelStorageInfra storage() {
        requireOpen();
        return storage;
    }

    public AssetPaths paths() {
        requireOpen();
        return storage.paths();
    }

    public BuiltinModelCatalog builtins() {
        requireOpen();
        return builtins;
    }

    public ReloadableModelCatalog catalog() {
        requireOpen();
        return catalog;
    }

    public ServerResourceRuntime serverResources() {
        requireOpen();
        return serverResources;
    }

    public PreviewStorage previews() {
        requireOpen();
        return previews;
    }

    public void tickCatalog() {
        requireOpen();
        catalog.tick();
    }

    public void tickServerRuntime() {
        requireOpen();
        serverResources.tick();
    }

    /**
     * Transfers one non-null client runtime's close responsibility to this system on normal return.
     * Rejection leaves that responsibility with the caller and does not close the supplied owner.
     *
     * @throws IllegalStateException if this system is sealed or already has a client runtime
     */
    public synchronized void attachClientRuntime(AutoCloseable owner) {
        requireOpen();
        Objects.requireNonNull(owner, "owner");
        if (clientRuntime != null) {
            throw new IllegalStateException("Client runtime is already attached");
        }
        clientRuntime = owner;
    }

    /**
     * Seals new access, then closes catalog, client, server resources, storage, and builtins in order.
     *
     * <p>Execution: the first call may block on owned work. A concurrent or repeated call only observes
     * the seal and can return before cleanup completes. Children must not require future host ticks
     * to terminate.
     *
     * <p>Failure: later owners are still closed after an earlier failure. The first cause propagates
     * with later causes suppressed; another close does not retry failed cleanup.
     */
    @Override
    public void close() {
        final AutoCloseable client;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            client = clientRuntime;
            clientRuntime = null;
        }
        Throwable failure = closeOwned(catalog, null);
        failure = closeOwned(client, failure);
        failure = closeOwned(serverResources, failure);
        failure = closeOwned(storage, failure);
        failure = closeOwned(builtins, failure);
        if (failure != null) {
            ModelSystem.<RuntimeException>throwUnchecked(failure);
        }
    }

    private synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Model system is closed");
        }
    }

    private static Throwable closeOwned(AutoCloseable owner, Throwable failure) {
        if (owner != null) {
            try {
                owner.close();
            } catch (Throwable next) {
                if (failure == null) {
                    return next;
                }
                if (failure != next) {
                    failure.addSuppressed(next);
                }
            }
        }
        return failure;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable failure) throws E {
        throw (E)failure;
    }
}
