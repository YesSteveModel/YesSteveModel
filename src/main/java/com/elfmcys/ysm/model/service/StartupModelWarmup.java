package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.PlayerPreferences;
import com.elfmcys.ysm.model.storage.PlayerPreferences.LocalSelection;
import com.elfmcys.ysm.model.storage.StorageQuery;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.network.session.SessionMode;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Owns one optional preparation opportunity after required service publication.
 *
 * <p>
 * Execution: the published service serializes tick and close on its owner window. Catalog callbacks only invalidate the
 * next tick's observation; construction starts no work.
 *
 * <p>
 * Ownership: closes only its query, subscription and independent resource lease. Ready releases this use into the
 * ordinary cache without waiting for persistence or retaining the target until world entry.
 *
 * <p>
 * Seam: implements [startup warmup](docs/architecture/runtime/overview.md#启动模型一次性预热).
 */
final class StartupModelWarmup implements AutoCloseable {
    record HostState(boolean playerPresent, SessionMode mode, UUID playerId) {
    }

    interface Inputs {
        AutoCloseable subscribe(Runnable changed);

        CatalogSnapshot catalog();

        StorageQuery<LocalSelection> readSelection(UUID playerId);

        /** Returns null when the current projection denies local acquisition; never fetches remotely. */
        ResourceLease acquire(LocalSelection selection);
    }

    record ClientInputs(ReloadableModelCatalog local, PlayerPreferences preferences, ClientCatalogManager projection,
        ClientModelRenderTargetManager resources) implements Inputs {
        @Override
        public AutoCloseable subscribe(Runnable changed) {
            return local.subscribe(ignored -> changed.run());
        }

        @Override
        public CatalogSnapshot catalog() {
            return local.current();
        }

        @Override
        public StorageQuery<LocalSelection> readSelection(UUID playerId) {
            return preferences.readSelection(playerId);
        }

        @Override
        public ResourceLease acquire(LocalSelection selection) {
            var entry = projection.snapshot().find(selection.identity()).orElse(null);
            if (entry == null || !entry.hasLocalSource())
                return null;
            var request = resources.request(selection.identity(), RenderTargetIds.PLAYER, selection.texture(),
                ModelResourcePurpose.FULL, AbstractClientPlayer.class);
            if (!resources.permitsAcquisition(request))
                return null;
            return resources.getOrStartOffline(request, WorkPriority.BACKGROUND);
        }
    }

    private enum State {
        NEW, WAITING, LOADING, DONE, CLOSED
    }

    private final Supplier<HostState> host;
    private final Inputs inputs;
    private final Consumer<Throwable> failures;
    private State state = State.NEW;
    private HostState initialHost;
    private AutoCloseable subscription;
    private StorageQuery<LocalSelection> query;
    private LocalSelection selection;
    private ResourceLease lease;
    private boolean builtinTarget;
    private boolean catalogChanged;

    StartupModelWarmup(Supplier<HostState> host, Inputs inputs, Consumer<Throwable> failures) {
        this.host = host;
        this.inputs = inputs;
        this.failures = failures;
    }

    void tick() {
        if (state == State.DONE || state == State.CLOSED)
            return;
        try {
            if (state == State.NEW)
                begin();
            if (state == State.WAITING)
                awaitTarget();
            if (state == State.LOADING) {
                var result = lease.poll();
                if (!(result instanceof AcquireResult.Pending)) {
                    finish(State.DONE);
                    if (result instanceof AcquireResult.Failed failed)
                        report(failed.failure().cause());
                }
            }
        } catch (Throwable failure) {
            try {
                finish(State.DONE);
            } catch (Throwable closeFailure) {
                if (closeFailure != failure)
                    failure.addSuppressed(closeFailure);
            }
            report(failure);
        }
    }

    private void begin() {
        initialHost = host.get();
        if (initialHost.playerPresent()) {
            finish(State.DONE);
            return;
        }
        state = State.WAITING;
        // subscribe replays inline before returning its handle; adoption and cleanup stay in tick.
        subscription = inputs.subscribe(() -> {
            if (state == State.WAITING)
                catalogChanged = true;
        });
        if (initialHost.mode() != SessionMode.LOCAL) {
            builtinTarget = true;
            return;
        }
        try {
            query = inputs.readSelection(initialHost.playerId());
        } catch (Throwable failure) {
            if (cancelled(failure))
                finish(State.DONE);
            else {
                report(failure);
                builtinTarget = true;
            }
        }
    }

    private void awaitTarget() {
        if (!eligible(initialHost, host.get())) {
            finish(State.DONE);
            return;
        }
        if (query != null) {
            var completion = query.poll();
            if (completion == null)
                return;
            query.close();
            query = null;
            if (completion.closed() || cancelled(completion.failure())
                || completion.result() != null && (completion.result().outcome() == StorageResult.Outcome.CANCELLED
                    || completion.result().failure() == StorageResult.Failure.CANCELLED)) {
                finish(State.DONE);
                return;
            }
            var result = completion.result();
            if (completion.failure() != null || !result.succeeded()) {
                report(completion.failure() != null ? completion.failure()
                    : new IllegalStateException("Startup Local selection read failed: " + result));
                builtinTarget = true;
            } else if (result.rows().isEmpty())
                builtinTarget = true;
            else {
                selection = result.rows().get(0);
                if (selection.identity().isIntrinsicDefault()) {
                    finish(State.DONE);
                    return;
                }
            }
            catalogChanged = true;
        }
        if (!catalogChanged)
            return;
        catalogChanged = false;
        var catalog = inputs.catalog();
        var target = selection;
        if (builtinTarget) {
            var identity = catalog.resolve(CatalogLocation.BUILTIN_DEFAULT).orElse(null);
            if (identity == null)
                return;
            target = new LocalSelection(PublishedModelId.model(identity), "");
        } else if (catalog.binding(target.identity()).isEmpty())
            return;

        var expectedHost = initialHost;
        // Consume the opportunity before external cleanup or acquisition can fail or notify again.
        finish(State.DONE);
        if (!eligible(expectedHost, host.get()))
            return;
        lease = inputs.acquire(target);
        if (lease != null)
            state = State.LOADING;
    }

    private static boolean eligible(HostState expected, HostState current) {
        return !current.playerPresent() && current.mode() == expected.mode()
            && current.playerId().equals(expected.playerId());
    }

    private void report(Throwable failure) {
        if (!cancelled(failure))
            failures.accept(failure);
    }

    private static boolean cancelled(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null)
            failure = failure.getCause();
        return failure instanceof CancellationException;
    }

    @Override
    public void close() {
        finish(State.CLOSED);
    }

    private void finish(State terminal) {
        if (state != State.CLOSED)
            state = terminal;
        var oldQuery = query;
        var oldSubscription = subscription;
        var oldLease = lease;
        query = null;
        subscription = null;
        lease = null;
        selection = null;
        initialHost = null;
        Throwable failure = closeOwned(oldQuery, null);
        failure = closeOwned(oldSubscription, failure);
        failure = closeOwned(oldLease, failure);
        if (failure instanceof Error error)
            throw error;
        if (failure instanceof RuntimeException exception)
            throw exception;
        if (failure != null)
            throw new IllegalStateException("Failed to release startup model warmup", failure);
    }

    private static Throwable closeOwned(AutoCloseable resource, Throwable failure) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                if (failure == null)
                    return closeFailure;
                if (failure != closeFailure)
                    failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }
}
