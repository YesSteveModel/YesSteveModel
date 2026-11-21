package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Maintenance;
import com.elfmcys.ysm.natives.storage.NativeCacheCatalog;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Owns one root's startup maintenance attempt. Commands change persistent revisions, never this startup snapshot.
 *
 * <p>
 * Execution: initialization precedes consumer delivery. The catalog coordinator calls attempt once after complete
 * source observation and index publication. The root window covers both pools and the conditional completion commit.
 *
 * <p>
 * Seam: consumes [shared reclamation](docs/architecture/model-management/storage/overview.md#共享存储与清理) and [database
 * execution](docs/architecture/model-management/storage/overview.md#数据库执行).
 */
public final class CacheMaintenance {
    private static final long INTERVAL_SECONDS = Duration.ofHours(72).toSeconds();
    private final AtomicSharedCache cache;
    private final CachePruner pruner;
    private final boolean local;
    private final Clock clock;
    private final Checkpoint checkpoint;
    private OptionalLong startupRevision = OptionalLong.empty();
    private boolean attempted;

    CacheMaintenance(CacheRegion region, boolean local, Clock clock) {
        this(region.cache(), region.pruner(), local, clock, (phase, state) -> {
        });
    }

    CacheMaintenance(AtomicSharedCache cache, CachePruner pruner, boolean local, Clock clock, Checkpoint checkpoint) {
        this.cache = cache;
        this.pruner = pruner;
        this.local = local;
        this.clock = clock;
        this.checkpoint = checkpoint;
    }

    void initialize() throws IOException {
        startupRevision = OptionalLong.of(read().resetRevision());
        cache.coordinator().cleanup(pruner::initializeReferences, false);
    }

    Result attempt(Set<String> completeScopes) throws IOException {
        synchronized (this) {
            if (attempted)
                return Result.of(Status.ALREADY_ATTEMPTED);
            attempted = true;
        }
        if (startupRevision.isEmpty())
            return Result.of(Status.NO_STARTUP_SNAPSHOT);
        if (completeScopes.isEmpty())
            return Result.of(Status.INCOMPLETE_OBSERVATION);
        try {
            var observed = read();
            var eligibility = eligibility(observed);
            if (eligibility != null)
                return Result.of(eligibility);
            checkpoint.reached(Phase.BEFORE_WINDOW, observed);
            return cache.coordinator().cleanup(() -> {
                var accepted = read();
                var currentEligibility = eligibility(accepted);
                if (currentEligibility != null)
                    return Result.of(currentEligibility);
                checkpoint.reached(Phase.ACCEPTED, accepted);
                var reclaimed = pruner.prune(completeScopes, local || accepted.pendingAll());
                if (!reclaimed.completed())
                    return new Result(Status.PRUNE_FAILED, Optional.of(reclaimed), Optional.empty());
                checkpoint.reached(Phase.BEFORE_COMPLETE, accepted);
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Cache maintenance cancelled before completion");
                var completed = cache.catalog().execute((db, token) -> db.completeMaintenance(accepted.resetRevision(),
                    clock.instant().getEpochSecond(), token));
                if (completed.outcome() == StorageResult.Outcome.REJECTED
                    && completed.failure() == StorageResult.Failure.NONE)
                    return new Result(Status.COMPLETION_SUPERSEDED, Optional.of(reclaimed), Optional.empty());
                StorageIndex.require(completed);
                if (!completed.committed())
                    throw new IOException("Cache maintenance completion did not commit");
                return new Result(Status.RECORDED, Optional.of(reclaimed), Optional.empty());
            }, Result.of(Status.PEER_PRESENT));
        } catch (IOException | SecurityException failure) {
            if (failure instanceof InterruptedIOException || failure instanceof ClosedChannelException)
                throw failure;
            return new Result(Status.FAILED, Optional.empty(), Optional.of(
                failure instanceof IOException io ? io : new IOException("Cache maintenance access denied", failure)));
        }
    }

    private Maintenance read() throws IOException {
        var result = StorageIndex.require(cache.catalog().read(NativeCacheCatalog::readMaintenance));
        return result.rows().get(0);
    }

    private Status eligibility(Maintenance state) {
        if (state.resetRevision() != startupRevision.orElseThrow())
            return Status.RESET_AFTER_STARTUP;
        var now = clock.instant().getEpochSecond();
        if (now < 0 || state.lastSuccessUtc().isPresent() && (now < state.lastSuccessUtc().getAsLong()
            || now - state.lastSuccessUtc().getAsLong() < INTERVAL_SECONDS))
            return Status.NOT_DUE;
        return null;
    }

    public enum Status {
        RECORDED, NOT_DUE, RESET_AFTER_STARTUP, COMPLETION_SUPERSEDED, PEER_PRESENT, INCOMPLETE_OBSERVATION,
        NO_STARTUP_SNAPSHOT, ALREADY_ATTEMPTED, PRUNE_FAILED, FAILED
    }

    public record Result(Status status, Optional<CachePruner.Result> reclaimed, Optional<IOException> failure) {
        static Result of(Status status) {
            return new Result(status, Optional.empty(), Optional.empty());
        }
    }

    enum Phase {
        BEFORE_WINDOW, ACCEPTED, BEFORE_COMPLETE
    }

    @FunctionalInterface
    interface Checkpoint {
        void reached(Phase phase, Maintenance state) throws IOException;
    }
}
