package com.elfmcys.ysm.client.demand;

import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * Advances one consumer's demand without waiting for its asynchronous acquisitions.
 *
 * <p>
 * <b>Execution:</b> The demand owner serializes advance, inspection and close. A shared preparation does not merge this
 * consumer's lease or failure with another use.
 *
 * <p>
 * <b>Ownership:</b> This object owns every acquisition until advance returns its Ready lease. The receiving consumer
 * then owns closure. Closing the demand cancels only untransferred interests; the resource owner terminates its own
 * work and unadopted results.
 *
 * <p>
 * <b>Seam:</b> [independent demand reception](docs/architecture/model-management/demand/overview.md#用途准备与独立接收).
 */
public final class ModelResourceDemand implements AutoCloseable {
    public enum Mode {
        READY, CACHED, OFFLINE, ONLINE
    }

    public interface Access {
        boolean permitsAcquisition(ResourceRequest request);

        Optional<ResourceLease> findReady(ResourceRequest request);

        ResourceLease getOrStartCached(ResourceRequest request, WorkPriority priority);

        ResourceLease getOrStartOffline(ResourceRequest request, WorkPriority priority);

        ResourceLease getOrStart(ResourceRequest request, WorkPriority priority);
    }

    private final Access access;
    private final ResourceRequest request;
    private final WorkPriority priority;
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
        return lease != null;
    }

    /**
     * Tries the allowed acquisition level and transfers at most one Ready lease. A lower level retires an incompatible
     * pending attempt. A prior local failure does not prevent reuse of a Ready target produced by another consumer.
     *
     * @param allowed the non-null maximum acquisition level permitted by current demand admission
     * @return an independently owned Ready lease, or null when closed, pending, denied, absent or failed; inspect
     *         {@link #failure()} and {@link #denied()} to distinguish recorded rejection or failure
     */
    public @Nullable ResourceLease advance(Mode allowed) {
        if (closed)
            return null;
        try {
            if (attempted != null && allowed.ordinal() < attempted.ordinal()) {
                releasePending();
                attempted = allowed;
            }
            if (lease == null && !access.permitsAcquisition(request)) {
                denied = true;
                attempted = null;
                return null;
            }
            denied = false;
            var ready = takeReady();
            if (ready != null)
                return ready;
            if (lease == null || attempted == Mode.CACHED || attempted == Mode.OFFLINE) {
                var published = access.findReady(request).orElse(null);
                if (published != null) {
                    releasePending();
                    lease = published;
                    ready = takeReady();
                }
            }
            if (ready != null || failure != null)
                return ready;
            // Offline work can finish without restarting it when online permission becomes available.
            if (lease != null && (attempted != Mode.CACHED || allowed == Mode.CACHED))
                return null;
            if (allowed == Mode.READY || attempted != null && attempted.ordinal() >= allowed.ordinal())
                return null;

            var next = switch (allowed) {
                case CACHED -> access.getOrStartCached(request, priority);
                case OFFLINE -> access.getOrStartOffline(request, priority);
                case ONLINE -> access.getOrStart(request, priority);
                case READY -> throw new IllegalStateException("Ready acquisition was already inspected");
            };
            // Receiving use is established before the old use can release the last retained target.
            releasePending();
            lease = next;
            attempted = allowed;
            return takeReady();
        } catch (RuntimeException error) {
            releasePending();
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
        var result = lease.poll();
        if (result instanceof AcquireResult.Ready) {
            var ready = lease;
            lease = null;
            failure = null;
            return ready;
        }
        if (result instanceof AcquireResult.Failed failed) {
            failure = failed.failure().cause();
            releasePending();
        } else if (result instanceof AcquireResult.Missing) {
            releasePending();
        } else if (result instanceof AcquireResult.Ended) {
            failure = new CancellationException("Resource use ended");
            releasePending();
        }
        return null;
    }

    private void releasePending() {
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

}
