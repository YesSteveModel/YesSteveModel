package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Installation;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult;
import com.elfmcys.ysm.client.demand.ContinuousDemand;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import org.jetbrains.annotations.Nullable;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Owns an entity's assignment candidates and nonblocking installation attempts. The coordinator schedules this owner;
 * it does not receive installation outcomes.
 *
 * <p>
 * Execution: the main-thread owner serializes advancement, frame acquisition and closure. An accepted GUI task owns its
 * independent candidate until the existing frame join.
 *
 * <p>
 * Seam: implements [entity installation demand](docs/architecture/animation/entity-lifecycle.md#模型使用与完整安装).
 */
public final class EntityModelDemand implements AutoCloseable {
    private final WeakReference<Installation> endpoint;
    private final ContinuousDemand<Use> timeline = new ContinuousDemand<>();
    private Operation operation;
    private Operation fallback;
    private Throwable resourceFailure;
    private Throwable reportedFailure;
    private boolean ended;

    private boolean frameInstallation;
    private boolean sealed;

    private final ClientDemandCoordinator.Resources resources;
    private final LongSupplier clock;
    private final java.util.function.Consumer<EntityModelDemand> unregister;
    private final WorkPriority priority;

    public EntityModelDemand(Installation endpoint, WorkPriority priority, ClientDemandCoordinator.Resources resources,
        LongSupplier clock, java.util.function.Consumer<EntityModelDemand> unregister) {
        this.resources = resources;
        this.clock = clock;
        this.unregister = unregister;
        this.priority = Objects.requireNonNull(priority);
        this.endpoint = new WeakReference<>(Objects.requireNonNull(endpoint));
    }

    public boolean isClosed() {
        return ended;
    }

    public @Nullable Throwable failure() {
        return resourceFailure;
    }

    public boolean hasUnfinishedWork() {
        return !ended && (operation != null && operation.unfinished() || fallback != null && fallback.unfinished());
    }

    /** Only explicitly adopted GUI scopes stop installing from the client tick. */
    public void useFrameInstallation() {
        if (ended || sealed)
            throw new IllegalStateException("Demand scope is closed");
        frameInstallation = true;
    }

    public boolean hasReadyInstallation() {
        return readyCandidate() != null;
    }

    /** The frame owns an independent candidate; the source survives rejection or Busy. */
    public @Nullable ReadyCandidate claimReadyInstallation() {
        var candidate = readyCandidate();
        if (candidate == null)
            return null;
        var lease = findReady(candidate.request);
        return lease == null ? null
            : new ReadyCandidate(candidate.request, lease, candidate == fallback, candidate.selectionOperation);
    }

    private @Nullable ResourceLease findReady(ResourceRequest request) {
        if (!resources.permitsAcquisition(request))
            return null;
        return resources.findReady(request).orElse(null);
    }

    private @Nullable Operation readyCandidate() {
        if (ended || sealed || !frameInstallation)
            return null;
        var host = endpoint.get();
        if (host == null)
            return null;
        var input = host.assignment();
        if (!input.permitted())
            return null;
        var ready = readyOperation(host, operation, input.request(), input.operation());
        if (ready != null) {
            if (ready != operation) {
                var previous = operation;
                try {
                    timeline.observe(
                        new Use(ready.request, resources.content(ready.request.modelId()), input.operation()),
                        clock.getAsLong());
                } catch (RuntimeException | Error failure) {
                    cleanup(failure, ready::close);
                    throw failure;
                }
                operation = ready;
                resourceFailure = null;
                if (previous != null)
                    previous.close();
            }
            return ready;
        }
        if (host.hasInstallation())
            return null;
        ready = readyOperation(host, fallback, input.fallback(), input.operation());
        if (ready != null && ready != fallback) {
            var previous = fallback;
            fallback = ready;
            if (previous != null)
                previous.close();
        }
        return ready;
    }

    private @Nullable Operation readyOperation(Installation host, @Nullable Operation candidate,
        @Nullable ResourceRequest request, Object selectionOperation) {
        if (request == null || request.purpose() != ModelResourcePurpose.GUI || host.matches(request, false)
            || host.failure(request) != null || !resources.permitsAcquisition(request))
            return null;
        if (candidate != null && candidate.request.equals(request) && candidate.selectionOperation == selectionOperation
            && candidate.ready() && candidate.anchor.isCurrent(request))
            return candidate;
        // A miss cannot lower the admission of work already in flight.
        var lease = findReady(request);
        if (lease == null)
            return null;
        var ready = new Operation(request, priority, selectionOperation);
        ready.anchor = lease;
        return ready;
    }

    /** One frame's independently owned candidate; no installation outcome returns to demand. */
    public static final class ReadyCandidate implements AutoCloseable {
        private final ResourceRequest request;
        private final boolean fallback;
        private final Object operation;
        private ResourceLease lease;

        private ReadyCandidate(ResourceRequest request, ResourceLease lease, boolean fallback, Object operation) {
            this.operation = operation;
            this.request = request;
            this.lease = lease;
            this.fallback = fallback;
        }

        public ResourceRequest request() {
            return request;
        }

        public Object operation() {
            return operation;
        }

        public boolean fallback() {
            return fallback;
        }

        public ResourceLease takeLease() {
            if (lease == null)
                throw new IllegalStateException("Candidate lease has already transferred");
            var transferred = lease;
            lease = null;
            return transferred;
        }

        @Override
        public void close() {
            var owned = lease;
            lease = null;
            if (owned != null)
                owned.close();
        }
    }

    public void seal() {
        sealed = true;
    }

    void reportInstalledUse(@Nullable ResourceRequest installed) {
        if (installed != null)
            resources.reportActiveModelUse(installed);
    }

    /** The joined entity owner reads its binding's publication before retiring source uses. */
    void observeInstallation(@Nullable ResourceRequest installed) {
        Throwable failure = null;
        if (operation != null && operation.request.equals(installed)) {
            var previous = operation;
            operation = null;
            failure = cleanup(failure, previous::close);
        }
        if (installed != null && fallback != null) {
            var previous = fallback;
            fallback = null;
            failure = cleanup(failure, previous::close);
        }
        throwCleanup(failure);
    }

    public void advance(long now) {
        var host = endpoint.get();
        if (ended || host == null) {
            close();
            return;
        }
        if (sealed)
            return;
        var input = host.assignment();
        if (!input.permitted()) {
            clearOperations();
            return;
        }
        var request = input.request();
        if (request != null) {
            var use = new Use(request, resources.content(request.modelId()), input.operation());
            if (timeline.observe(use, now)) {
                var previous = operation;
                operation = new Operation(request, priority, input.operation());
                if (previous != null)
                    previous.close();
                resourceFailure = null;
            }
            if (host.matches(request, false)) {
                if (operation != null) {
                    operation.close();
                    operation = null;
                }
            } else if (host.failure(request) != null) {
                resourceFailure = host.failure(request);
                if (operation != null) {
                    operation.close();
                    operation = null;
                }
            } else {
                if (operation == null)
                    operation = new Operation(request, priority, input.operation());
                var mode = input.mode() == ModelResourceDemand.Mode.ONLINE
                    && !timeline.effectEligible(true, now, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS)
                        ? ModelResourceDemand.Mode.OFFLINE : input.mode();
                operation.advance(mode);
                resourceFailure = operation.failure();
                if (!frameInstallation)
                    install(host, operation, false);
                if (host.matches(request, false)) {
                    operation.close();
                    operation = null;
                }
            }
        } else {
            if (operation != null)
                operation.close();
            operation = null;
            timeline.end(now);
        }
        if (input.fallback() != null && host.failure(input.fallback()) != null) {
            if (fallback != null)
                fallback.close();
            fallback = null;
        } else if (input.fallback() != null && !host.hasInstallation()) {
            if (fallback == null
                || (!fallback.request.equals(input.fallback()) || fallback.selectionOperation != input.operation())) {
                if (fallback != null)
                    fallback.close();
                fallback = new Operation(input.fallback(), priority, input.operation());
            }
            fallback.advance(ModelResourceDemand.Mode.ONLINE);
            if (!frameInstallation)
                install(host, fallback, true);
        }
        if (resourceFailure != null && resourceFailure != reportedFailure && request != null) {
            reportedFailure = resourceFailure;
            var content = resources.content(request.modelId());
            if (content != null && !request.modelId().isIntrinsicDefault())
                resources.reportActiveModelFailure(content, input.fallback() != null);
        }
        if (host.hasInstallation() && fallback != null) {
            fallback.close();
            fallback = null;
        }
    }

    private void install(Installation host, Operation candidate, boolean intrinsic) {
        if (!candidate.ready())
            return;
        // This scope already acquired its independent receiving lease with its original admission.
        var lease = candidate.anchor;
        candidate.anchor = null;
        final InstallationResult result;
        try {
            result = host.install(candidate.request, lease, intrinsic, false, candidate.selectionOperation);
        } catch (RuntimeException | Error failure) {
            candidate.close();
            throw failure;
        }
        if (result == InstallationResult.BUSY)
            candidate.anchor = lease;
        if (result == InstallationResult.REJECTED) {
            resourceFailure = Objects.requireNonNullElseGet(host.failure(candidate.request),
                () -> new IllegalStateException("Model installation was rejected"));
            candidate.close();
        }
    }

    private void clearOperations() {
        var previous = operation;
        var previousFallback = fallback;
        operation = null;
        fallback = null;
        Throwable failure = null;
        try {
            if (previous != null)
                previous.close();
        } catch (RuntimeException | Error primary) {
            failure = primary;
        }
        try {
            if (previousFallback != null)
                previousFallback.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else if ((cleanupFailure instanceof VirtualMachineError && !(cleanupFailure instanceof OutOfMemoryError)
                || cleanupFailure instanceof ThreadDeath)
                && !(failure instanceof VirtualMachineError || failure instanceof ThreadDeath)) {
                cleanupFailure.addSuppressed(failure);
                failure = cleanupFailure;
            } else if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
    }

    @Override
    public void close() {
        if (ended)
            return;
        ended = true;
        unregister.accept(this);
        clearOperations();
    }

    private final class Operation implements AutoCloseable {
        private final ResourceRequest request;
        private final WorkPriority priority;
        private final Object selectionOperation;
        private final ModelResourceDemand acquisition;
        private ResourceLease anchor;

        private Operation(ResourceRequest request, WorkPriority priority, Object selectionOperation) {
            this.selectionOperation = selectionOperation;
            this.request = request;
            this.priority = priority;
            acquisition = new ModelResourceDemand(resources, request, priority);
        }

        private void advance(ModelResourceDemand.Mode mode) {
            if (anchor != null && !anchor.isCurrent(request)) {
                anchor.close();
                anchor = null;
            }
            if (anchor == null)
                anchor = acquisition.advance(mode);
        }

        private boolean ready() {
            return anchor != null;
        }

        private boolean unfinished() {
            return anchor != null || acquisition.hasUnfinishedWork();
        }

        private Throwable failure() {
            return acquisition.denied() ? new IllegalStateException("Current content policy denies new model use")
                : acquisition.failure();
        }

        @Override
        public void close() {
            var owned = anchor;
            anchor = null;
            var failure = cleanup(null, acquisition::close);
            if (owned != null)
                failure = cleanup(failure, owned::close);
            throwCleanup(failure);
        }
    }

    private record Use(ResourceRequest request, @Nullable ModelContent content, Object operation) {
    }

    private static Throwable cleanup(@Nullable Throwable failure, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure == null)
                return cleanupFailure;
            if (failure != cleanupFailure)
                failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    private static void throwCleanup(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
    }

}
