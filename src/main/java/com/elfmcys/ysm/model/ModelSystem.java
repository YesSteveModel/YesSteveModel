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

/** Process composition root for immutable contracts, storage, and shared catalogs. */
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

    public synchronized void attachClientRuntime(AutoCloseable owner) {
        requireOpen();
        Objects.requireNonNull(owner, "owner");
        if (clientRuntime != null) {
            throw new IllegalStateException("Client runtime is already attached");
        }
        clientRuntime = owner;
    }

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
