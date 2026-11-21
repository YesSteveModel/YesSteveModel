package com.elfmcys.ysm.model.session.client;

import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.storage.StorageQuery;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.util.concurrent.CompletableFuture;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import com.elfmcys.ysm.model.storage.PlayerPreferences;
import com.elfmcys.ysm.model.storage.StorageWriteBuffer;
import java.util.Set;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Owns one local producer's restoration qualification independently of entity installation.
 *
 * <p>
 * Execution: the client owner serializes all methods and adopts restored input in tick. ownerExecutor dispatches
 * side-effect feedback. A Scope is an incarnation, never a permission to follow a later selection or account. Storage
 * completion cannot update runtime or submit a network report.
 *
 * <p>
 * Seam: implements [local restoration](docs/architecture/network/player-state.md#表现报告与真实基线).
 */
public final class PlayerPreferenceSession {
    public record Scope(UUID player, Object authority, PublishedModelId identity, long intentId) {
    }

    private final PlayerPreferences preferences;
    private final StorageWriteBuffer writes;
    private final Executor ownerExecutor;
    private final Consumer<Throwable> failures;
    private final Consumer<Throwable> readFailures;
    private Scope scope;
    private NamedRoamingSnapshot payload;
    private StorageWriteBuffer.Operation<NamedRoamingSnapshot> roamingRestore;
    private CompletableFuture<StorageResult<NamedRoamingSnapshot>> roamingResult;
    private Runnable roamingRestored;
    private boolean persistable;
    private StorageQuery<PlayerPreferences.LocalSelection> localSelectionRead;
    private PlayerPreferences.LocalSelection localFallback;
    private Consumer<PlayerPreferences.LocalSelection> localRestored;

    public PlayerPreferenceSession(PlayerPreferences preferences, StorageWriteBuffer writes, Executor ownerExecutor,
        Consumer<Throwable> failures, Consumer<Throwable> readFailures) {
        this.preferences = preferences;
        this.writes = writes;
        this.ownerExecutor = ownerExecutor;
        this.failures = failures;
        this.readFailures = readFailures;
    }

    public Scope select(UUID player, Object authority, PublishedModelId identity, long intentId,
        NamedRoamingSnapshot initial) {
        return select(player, authority, identity, intentId, initial, true);
    }

    public Scope select(UUID player, Object authority, PublishedModelId identity, long intentId,
        NamedRoamingSnapshot initial, boolean persistable) {
        if (intentId <= 0)
            throw new IllegalArgumentException("Selection intent must be positive");
        scope = new Scope(player, authority, identity, intentId);
        payload = initial;
        this.persistable = persistable;
        cancelRoamingRestore();
        return scope;
    }

    public void retire() {
        cancelLocalSelection();
        scope = null;
        payload = null;
        persistable = false;
        cancelRoamingRestore();
    }

    private void cancelRoamingRestore() {
        var job = roamingRestore;
        roamingRestore = null;
        roamingResult = null;
        roamingRestored = null;
        if (job != null)
            job.cancel();
    }

    /** The binding owner retires this session on UUID or authority replacement. */
    public void restoreLocalSelection(UUID player, PlayerPreferences.LocalSelection fallback,
        Consumer<PlayerPreferences.LocalSelection> restored) {
        cancelLocalSelection();
        localFallback = fallback;
        localRestored = restored;
        localSelectionRead = preferences.readSelection(player);
    }

    private void cancelLocalSelection() {
        if (localSelectionRead != null)
            localSelectionRead.close();
        localSelectionRead = null;
        localFallback = null;
        localRestored = null;
    }

    /** Publishes input only at the existing client owner boundary; animation reads snapshot without polling handles. */
    public void tick() {
        if (localSelectionRead != null) {
            var completion = localSelectionRead.poll();
            if (completion != null) {
                var restored = localRestored;
                var fallback = localFallback;
                cancelLocalSelection();
                if (!completion.closed()) {
                    var result = completion.result();
                    if (completion.failure() != null || !result.succeeded()) {
                        readFailures.accept(completion.failure() != null ? completion.failure()
                            : new IllegalStateException("Local selection restore failed: " + result));
                        restored.accept(fallback);
                    } else
                        restored.accept(result.rows().isEmpty() ? fallback : result.rows().get(0));
                }
            }
        }
        if (roamingResult != null && roamingResult.isDone()) {
            var future = roamingResult;
            var restored = roamingRestored;
            roamingRestore = null;
            roamingResult = null;
            roamingRestored = null;
            StorageResult<NamedRoamingSnapshot> result = null;
            Throwable failure = null;
            try {
                result = future.join();
            } catch (java.util.concurrent.CompletionException failed) {
                failure = failed.getCause();
            }
            if (failure != null || !result.succeeded()) {
                payload = new NamedRoamingSnapshot(new String[0], new float[0]);
                persistable = false;
                readFailures
                    .accept(failure != null ? failure : new IllegalStateException("Roaming restore failed: " + result));
            } else
                payload = result.rows().get(0);
            restored.run();
        }
    }

    public void writeLocalSelection(UUID player, PlayerPreferences.LocalSelection selection) {
        cancelLocalSelection();
        preferences.writeSelection(player, selection).result()
            .whenComplete((result, failure) -> ownerExecutor.execute(() -> {
                if (failure != null || !result.succeeded())
                    failures.accept(failure != null ? failure
                        : new IllegalStateException("Local selection was not saved: " + result));
            }));
    }

    public Scope scope() {
        return scope;
    }

    public NamedRoamingSnapshot snapshot(Scope expected) {
        return expected != null && scope == expected ? payload : null;
    }

    public boolean persistable(Scope expected) {
        return expected != null && scope == expected && persistable;
    }

    /** Only the current scope can supply a report snapshot. */
    public NamedRoamingSnapshot reportableSnapshot(Scope expected) {
        return snapshot(expected);
    }

    /** A failed read permits runtime changes, but they cannot overwrite the unread preference in this scope. */
    public void restore(Scope expected, Set<String> fields, Runnable restored) {
        if (scope != expected || expected == null || payload != null || roamingRestore != null)
            return;
        roamingRestore = preferences.restoreRoaming(expected.player(), expected.identity(), fields);
        roamingResult = roamingRestore.result().toCompletableFuture();
        roamingRestored = restored;
    }

    /** The installation owner converts a finite exported delta with its exact module layout. */
    public boolean adopt(Scope expected, NamedRoamingSnapshot delta) {
        if (snapshot(expected) == null)
            return false;
        int extra = 0;
        for (var name : delta.names()) {
            if (indexOf(payload.names(), name) < 0)
                extra++;
        }
        var names = Arrays.copyOf(payload.names(), payload.names().length + extra);
        var values = Arrays.copyOf(payload.values(), names.length);
        int next = payload.names().length;
        for (int index = 0; index < delta.names().length; index++) {
            int target = indexOf(payload.names(), delta.names()[index]);
            if (target < 0) {
                target = next++;
                names[target] = delta.names()[index];
            }
            values[target] = delta.values()[index];
        }
        var complete = new NamedRoamingSnapshot(names, values);
        payload = complete;
        if (persistable) {
            var admission = writes.offerRoaming(expected.player(), expected.identity(), complete);
            if (admission != StorageWriteBuffer.Admission.QUEUED)
                failures.accept(new IllegalStateException("Roaming buffer rejected snapshot: " + admission));
        }
        return true;
    }

    private static int indexOf(String[] names, String expected) {
        for (int index = 0; index < names.length; index++) {
            if (names[index].equals(expected))
                return index;
        }
        return -1;
    }

    /** Invalidates old installations before the independent persistence attempt begins. */
    public void clear(UUID player, PublishedModelId identity, Consumer<StorageResult<NamedRoamingSnapshot>> completed) {
        if (scope != null && scope.player().equals(player) && scope.identity().equals(identity)) {
            scope = new Scope(scope.player(), scope.authority(), scope.identity(), scope.intentId());
            payload = new NamedRoamingSnapshot(new String[0], new float[0]);
            persistable = true;
            cancelRoamingRestore();
        }
        clearStored(player, identity, completed);
    }

    /** Persistence is independent of the already published authority and producer scope. */
    public void clearStored(UUID player, PublishedModelId identity,
        Consumer<StorageResult<NamedRoamingSnapshot>> completed) {
        writes.clearRoaming(player, identity).result().whenComplete((result, failure) -> ownerExecutor.execute(() -> {
            if (failure != null)
                failures.accept(failure);
            else
                completed.accept(result);
        }));
    }
}
