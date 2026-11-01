package com.elfmcys.ysm.client.demand;

import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Advances one consumer's demand without waiting for its asynchronous acquisitions.
 *
 * <p><b>Execution:</b> The demand owner serializes advance, inspection and close. Access callbacks can
 * complete inline. A shared preparation does not merge this consumer's lease or failure with another use.
 *
 * <p><b>Ownership:</b> This object owns every acquisition until advance returns its Ready lease.
 * The receiving consumer then owns closure. Closing the demand cancels only untransferred interests;
 * a late result is closed by the attempt that still owns it.
 *
 * <p><b>Seam:</b> [independent demand reception](docs/architecture/model-management/demand/overview.md#用途准备与独立接收).
 */
public final class ModelResourceDemand implements AutoCloseable {
    public enum Mode {
        READY, CACHED, OFFLINE, ONLINE
    }

    public interface Access {
        boolean permitsAcquisition(ResourceRequest request);

        Optional<ResourceLease> findReady(ResourceRequest request);

        CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request, WorkPriority priority);

        CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request, WorkPriority priority);

        ResourceLease getOrStart(ResourceRequest request, WorkPriority priority);
    }

    private final Access access;
    private final ResourceRequest request;
    private final WorkPriority priority;
    private Attempt attempt;
    private ResourceLease lease;
    private Mode attempted;
    private Throwable failure;
    private boolean denied;
    private boolean closed;

    public ModelResourceDemand(Access access, ResourceRequest request) {
        this(access, request, WorkPriority.BACKGROUND);
    }

    public ModelResourceDemand(Access access, ResourceRequest request, WorkPriority priority) {
        this.access = Objects.requireNonNull(access, "access");
        this.request = Objects.requireNonNull(request, "request");
        this.priority = Objects.requireNonNull(priority, "priority");
    }

    public @Nullable Throwable failure() {
        return failure;
    }

    public boolean denied() {
        return denied;
    }

    public ResourceRequest request() {
        return request;
    }

    public boolean hasUnfinishedWork() {
        return attempt != null || lease != null;
    }

    /**
     * Tries the allowed acquisition level and transfers at most one Ready lease.
     * A lower level retires an incompatible pending attempt. A prior local failure does not prevent
     * reuse of a Ready target produced by another consumer.
     *
     * @param allowed the non-null maximum acquisition level permitted by current demand admission
     * @return an independently owned Ready lease, or null when closed, pending, denied, absent or failed;
     *     inspect {@link #failure()} and {@link #denied()} to distinguish recorded rejection or failure
     */
    public @Nullable ResourceLease advance(Mode allowed) {
        if (closed)
            return null;
        try {
            if (attempted != null && allowed.ordinal() < attempted.ordinal()) {
                releasePending();
                attempted = allowed;
            }
            // An existing acquisition retains its admission; a cache hit is a new use.
            if (lease == null && attempt == null && !access.permitsAcquisition(request)) {
                denied = true;
                attempted = null;
                return null;
            }
            denied = false;
            if (attempt != null && attempt.isDone()) {
                var completed = attempt;
                attempt = null;
                lease = completed.take();
            }
            if (lease == null) {
                // A previous miss must not hide Ready published by another consumer.
                var ready = access.findReady(request).orElse(null);
                if (ready != null) {
                    releasePending();
                    lease = ready;
                }
            }
            var ready = takeReady();
            if (ready != null || failure != null || lease != null)
                return ready;

            if (allowed == Mode.READY)
                return null;

            if ((attempt == null || attempted != Mode.OFFLINE)
                && (attempted == null || attempted.ordinal() < allowed.ordinal())) {
                releasePending();
                // Offline local work already uses the ordinary Flight. Do not restart it online.
                var next = attempted == null && allowed == Mode.ONLINE ? Mode.OFFLINE : allowed;
                attempted = next;
                if (next == Mode.ONLINE)
                    lease = Objects.requireNonNull(access.getOrStart(request, priority), "Acquisition returned no lease");
                else
                    attempt = new Attempt(
                        next == Mode.CACHED ? access.getOrStartCached(request, priority)
                            : access.getOrStartOffline(request, priority));
                if (attempt != null && attempt.isDone()) {
                    var completed = attempt;
                    attempt = null;
                    lease = completed.take();
                }
                ready = takeReady();
                if (ready == null && lease == null && attempt == null && failure == null
                    && next.ordinal() < allowed.ordinal()) {
                    return advance(allowed);
                }
            }
            return ready;
        } catch (RuntimeException error) {
            releasePending();
            while (error instanceof CompletionException && error.getCause() instanceof RuntimeException cause) {
                error = cause;
            }
            failure = error;
            return null;
        }
    }

    private @Nullable ResourceLease takeReady() {
        if (lease == null)
            return null;
        if (!lease.isCurrent(request)) {
            releasePending();
            failure = new CancellationException("Resource owner is no longer current");
            return null;
        }
        var result = Objects.requireNonNull(lease.poll(), "Acquisition returned no outcome");
        if (result instanceof AcquireResult.Ready) {
            var ready = lease;
            lease = null;
            failure = null;
            return ready;
        }
        if (result instanceof AcquireResult.Failed failed) {
            failure = failed.failure().cause();
            releasePending();
        }
        return null;
    }

    private void releasePending() {
        if (attempt != null) {
            attempt.close();
            attempt = null;
        }
        if (lease != null) {
            lease.close();
            lease = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        releasePending();
    }

    /** Completion and owner retirement race only over this exact acquisition. */
    private static final class Attempt implements AutoCloseable {
        private final CompletableFuture<Optional<ResourceLease>> future;
        private ResourceLease result;
        private Throwable failure;
        private boolean done;
        private boolean closed;

        Attempt(CompletableFuture<Optional<ResourceLease>> future) {
            this.future = Objects.requireNonNull(future, "acquisition future");
            future.whenComplete((acquired, error) -> {
                synchronized (this) {
                    var next = acquired == null ? null : acquired.orElse(null);
                    if (closed) {
                        if (next != null)
                            next.close();
                    } else {
                        result = next;
                        failure = error != null ? error
                            : acquired == null ? new IllegalStateException("Acquisition returned no outcome") : null;
                        done = true;
                    }
                }
            });
        }

        synchronized boolean isDone() {
            return done;
        }

        synchronized ResourceLease take() {
            closed = true;
            var acquired = result;
            result = null;
            if (failure != null) {
                if (acquired != null)
                    acquired.close();
                throw new CompletionException(failure);
            }
            return acquired;
        }

        @Override
        public synchronized void close() {
            if (closed)
                return;
            closed = true;
            if (result != null) {
                result.close();
                result = null;
            }
            future.cancel(false);
        }
    }
}
