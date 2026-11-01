package com.elfmcys.ysm.client.demand;

import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.*;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/** Owns active demand rules; resource owners and installation endpoints retain their own facts. */
public final class ClientDemandCoordinator implements AutoCloseable {
    public static final long WAIT_MILLIS = 30_000;

    public record Target(@Nullable Hash256 modelId, String texture) {
        public Target {
            texture = Objects.requireNonNullElse(texture, "");
            if (modelId == null)
                texture = "";
        }
    }

    public interface Resources extends ModelResourceDemand.Access {
        @Nullable
        ModelContent content(Hash256 modelId);

        ResourceRequest resourceRequest(Hash256 modelId, String target, String texture, ModelResourcePurpose purpose,
            Class<?> subject);

        ResourceRequest defaultResourceRequest(String target, ModelResourcePurpose purpose, Class<?> subject);

        void reportActiveModelUse(ResourceRequest request);

        void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable);

        default ResourceRequest playerRequest(Target target) {
            return target.modelId() == null ? defaultResourceRequest("player", ModelResourcePurpose.FULL, null)
                : resourceRequest(target.modelId(), "player", target.texture(), ModelResourcePurpose.FULL, null);
        }
    }

    public record Assignment(@Nullable ResourceRequest request, @Nullable ResourceRequest fallback,
        ModelResourceDemand.Mode mode, boolean permitted) {
    }

    public enum InstallationResult {
        BUSY, INSTALLED, REJECTED, STALE
    }

    public interface Installation {
        Assignment assignment();

        boolean hasInstallation();

        boolean matches(ResourceRequest request, boolean full);

        @Nullable
        Throwable failure(ResourceRequest request);

        /** BUSY retains caller ownership; every other outcome consumes this independent lease. */
        InstallationResult install(ResourceRequest request, ResourceLease lease, boolean fallback, boolean full);
    }

    public interface CardDisplay {
        void activate(ModelResourceDemand.Mode mode);

        void deactivate();
    }

    public record SelectionContext(Object owner, boolean remote, boolean available) {
    }

    public interface Selections {
        @Nullable
        SelectionContext context();

        Target authority();

        boolean installed(Target target);

        default @Nullable Throwable failure(Target target) {
            return null;
        }

        void send(Target target, long intentId);

        void reportFailure(Throwable failure);
    }

    public record Feedback(boolean waiting, @Nullable Throwable failure) {
        private static final Feedback IDLE = new Feedback(false, null);
    }

    private final LongSupplier clock;
    private final Resources resources;
    private final Selections selections;
    private final BooleanSupplier eagerLoading;
    private final java.util.concurrent.ConcurrentLinkedQueue<EntityScope> entities =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Set<PageScope> pages = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ContinuousDemand<Target> selectionTimeline = new ContinuousDemand<>();
    private Object owner;
    private long nextIntentId = 1;
    private boolean idsExhausted;
    private Intent current;
    private Preparation hover;
    private PageScope hoverPage;
    private Target observedAuthority;
    private long authorityStarted;
    private boolean authorityWaiting;
    private Feedback feedback = Feedback.IDLE;
    private Target feedbackTarget;
    private boolean closed;

    public ClientDemandCoordinator(LongSupplier clock, Resources resources, Selections selections) {
        this(clock, resources, selections, () -> false);
    }

    public ClientDemandCoordinator(LongSupplier clock, Resources resources, Selections selections,
        BooleanSupplier eagerLoading) {
        this.clock = Objects.requireNonNull(clock);
        this.resources = Objects.requireNonNull(resources);
        this.selections = Objects.requireNonNull(selections);
        this.eagerLoading = Objects.requireNonNull(eagerLoading);
    }

    public EntityScope register(Installation endpoint) {
        return register(endpoint, WorkPriority.BACKGROUND);
    }

    public EntityScope register(Installation endpoint, WorkPriority priority) {
        if (closed)
            throw new IllegalStateException("Demand owner is closed");
        var scope = new EntityScope(endpoint, priority);
        entities.add(scope);
        return scope;
    }

    public PageScope openPage(Object semanticIdentity) {
        if (closed)
            throw new IllegalStateException("Demand owner is closed");
        var page = new PageScope(Objects.requireNonNull(semanticIdentity), new ContinuousDemand<>());
        page.timeline.observe(semanticIdentity, clock.getAsLong());
        pages.add(page);
        return page;
    }

    public PageScope replacePage(@Nullable PageScope previous, Object semanticIdentity) {
        if (previous != null && previous.matches(semanticIdentity))
            return previous;
        var timeline = previous == null || previous.ended ? new ContinuousDemand<Object>() : previous.timeline;
        timeline.observe(semanticIdentity, clock.getAsLong());
        var page = new PageScope(semanticIdentity, timeline);
        pages.add(page);
        if (previous != null)
            previous.close();
        return page;
    }

    public Feedback feedback() {
        return feedback;
    }

    public Feedback feedback(Target target) {
        return target.equals(feedbackTarget) ? feedback : Feedback.IDLE;
    }

    public Target authority() {
        return selections.authority();
    }

    public boolean canOpenTextures() {
        var authority = selections.authority();
        return !feedback.waiting()
            && (Objects.equals(observedAuthority, authority) || selections.installed(authority));
    }

    public void select(Target target) {
        Objects.requireNonNull(target);
        var context = synchronizeOwner();
        if (closed || context == null || !context.available())
            return;
        if (current != null && !current.terminal && current.target.equals(target))
            return;
        var previous = current;
        var preparation = previous != null && !previous.preparation.ended && previous.target.equals(target)
            ? previous.preparation
                : new Preparation(target, ModelResourcePurpose.FULL, WorkPriority.LOCAL_PLAYER);
        var now = clock.getAsLong();
        selectionTimeline.observe(target, now);
        current = new Intent(target, preparation, now);
        if (context.remote()) {
            if (idsExhausted) {
                current.sent = true;
                failIntent(new IllegalStateException("Model selection intent identifiers are exhausted"));
            } else {
                current.id = nextIntentId;
                if (nextIntentId == -1L)
                    idsExhausted = true;
                else
                    nextIntentId++;
            }
        }
        if (!current.terminal) {
            feedback = new Feedback(!selections.installed(target), null);
            feedbackTarget = target;
        }
        closeUnused(previous == null ? null : previous.preparation);
    }

    public void disposition(Object source, long intentId, boolean accepted, boolean rejected) {
        if (current == null || current.terminal || source != owner || intentId == 0 || current.id != intentId)
            return;
        if (rejected)
            failIntent(new IllegalStateException("Model selection was rejected"));
        // ACCEPTED is a disposition, never an installation fact.
    }

    public void tick() {
        if (closed)
            return;
        var context = synchronizeOwner();
        var now = clock.getAsLong();
        for (var page : List.copyOf(pages))
            page.advance(now);
        if (current != null) {
            var intent = current;
            var mode = resourceMode(selectionTimeline, now);
            if (!intent.terminal && !selections.installed(intent.target))
                intent.preparation.advance(mode);
            if (!intent.sent && context != null && context.available()
                && selectionTimeline.effectEligible(!selections.installed(intent.target) && !intent.preparation.ready(),
                    now, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS)) {
                intent.sent = true;
                try {
                    selections.send(intent.target, intent.id);
                } catch (RuntimeException failure) {
                    failIntent(failure);
                }
            }
        }
        if (hover != null)
            hover.advance(pageResourceMode(hoverPage.timeline, now));
        // Receivers acquire independent leases while the preparation anchors are still alive.
        for (var scope : List.copyOf(entities))
            scope.advance(now);
        if (current != null && !current.terminal) {
            if (current.sent && selections.installed(current.target))
                finishIntent();
            else if (selections.failure(current.target) != null)
                failIntent(selections.failure(current.target));
            else if (current.preparation.failure() != null)
                failIntent(current.preparation.failure());
            else if (now - current.started >= WAIT_MILLIS)
                failIntent(new IllegalStateException("Model selection installation timed out"));
        }
        observeAuthority(now);
    }

    private static ModelResourceDemand.Mode resourceMode(ContinuousDemand<?> timeline, long now) {
        if (timeline.effectEligible(true, now, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS))
            return ModelResourceDemand.Mode.ONLINE;
        if (timeline.effectEligible(true, now, ContinuousDemand.LOCAL_SWITCH_DWELL_MILLIS))
            return ModelResourceDemand.Mode.OFFLINE;
        return ModelResourceDemand.Mode.READY;
    }

    private static ModelResourceDemand.Mode pageResourceMode(ContinuousDemand<?> timeline, long now) {
        if (timeline.pageEffectEligible(true, now, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS))
            return ModelResourceDemand.Mode.ONLINE;
        if (timeline.pageEffectEligible(true, now, ContinuousDemand.LOCAL_SWITCH_DWELL_MILLIS))
            return ModelResourceDemand.Mode.OFFLINE;
        return ModelResourceDemand.Mode.READY;
    }

    private @Nullable SelectionContext synchronizeOwner() {
        var context = selections.context();
        var nextOwner = context == null ? null : context.owner();
        if (owner != nextOwner) {
            if (owner != null)
                reset();
            owner = nextOwner;
            nextIntentId = 1;
            idsExhausted = false;
        }
        return context;
    }

    private void observeAuthority(long now) {
        var authority = selections.authority();
        if (!Objects.equals(observedAuthority, authority)) {
            observedAuthority = authority;
            authorityStarted = now;
            authorityWaiting = owner != null && !selections.installed(authority);
        }
        if (current != null && !current.terminal)
            return;
        if (authorityWaiting && selections.installed(authority))
            authorityWaiting = false;
        if (authorityWaiting && selections.failure(authority) != null) {
            authorityWaiting = false;
            var failure = selections.failure(authority);
            feedback = new Feedback(false, failure);
            feedbackTarget = authority;
            selections.reportFailure(failure);
        } else if (authorityWaiting && now - authorityStarted >= WAIT_MILLIS) {
            authorityWaiting = false;
            var failure = new IllegalStateException("Authoritative model installation timed out");
            feedback = new Feedback(false, failure);
            feedbackTarget = authority;
            selections.reportFailure(failure);
        } else if (authorityWaiting) {
            feedback = new Feedback(true, null);
            feedbackTarget = authority;
        } else if (feedback.waiting()) {
            feedback = Feedback.IDLE;
            feedbackTarget = null;
        }
    }

    private void finishIntent() {
        current.terminal = true;
        authorityWaiting = false;
        feedback = Feedback.IDLE;
        feedbackTarget = null;
        closeUnused(current.preparation);
    }

    private void failIntent(Throwable failure) {
        if (current == null || current.terminal)
            return;
        current.terminal = true;
        authorityWaiting = false;
        feedback = new Feedback(false, failure);
        feedbackTarget = current.target;
        selections.reportFailure(failure);
        closeUnused(current.preparation);
    }

    private void closeUnused(@Nullable Preparation preparation) {
        if (preparation != null && preparation != hover
            && (current == null || current.terminal || preparation != current.preparation))
            preparation.close();
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

    public void reset() {
        var previous = current;
        var previousHover = hover;
        current = null;
        hover = null;
        hoverPage = null;
        var failure = cleanup(null, () -> closeUnused(previous == null ? null : previous.preparation));
        failure = cleanup(failure, () -> closeUnused(previousHover));
        for (var page : List.copyOf(pages))
            failure = cleanup(failure, page::close);
        for (var scope : List.copyOf(entities))
            failure = cleanup(failure, scope::close);
        selectionTimeline.reset();
        feedback = Feedback.IDLE;
        feedbackTarget = null;
        observedAuthority = null;
        authorityWaiting = false;
        throwCleanup(failure);
    }

    @Override
    public void close() {
        closed = true;
        reset();
    }

    public final class PageScope implements AutoCloseable {
        private final Object identity;
        private final ContinuousDemand<Object> timeline;
        private final ContinuousDemand<Target> pointer = new ContinuousDemand<>();
        private final Map<CardDisplay, Target> cards = new WeakHashMap<>();
        private final Set<EntityScope> pageEntities = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<PageAction> actions = new ArrayList<>();
        private boolean ended;
        private boolean admitted;
        private boolean eager;
        private boolean frameActive;
        private boolean cleaned;
        private Preparation retiringHover;

        private PageScope(Object identity, ContinuousDemand<Object> timeline) {
            this.identity = identity;
            this.timeline = timeline;
            eager = eagerLoading.getAsBoolean();
        }

        public boolean matches(Object nextIdentity) {
            return !ended && identity.equals(nextIdentity);
        }

        public boolean isClosed() {
            return ended;
        }

        public boolean eagerLoading() {
            return eager;
        }

        public void advance() {
            advance(clock.getAsLong());
        }

        /** Keeps page-owned inputs alive until every accepted task has returned to the frame owner. */
        public FrameUse beginFrame() {
            if (ended || frameActive)
                throw new IllegalStateException("Page cannot begin another frame");
            frameActive = true;
            return new FrameUse();
        }

        public final class FrameUse implements AutoCloseable {
            private boolean released;

            private FrameUse() {}

            @Override
            public void close() {
                if (released)
                    return;
                released = true;
                frameActive = false;
                if (ended)
                    finishClose();
            }
        }

        public void pointer(@Nullable Target target) {
            if (ended)
                return;
            if (target == null) {
                pointer.end(clock.getAsLong());
                admitted = false;
            } else if (pointer.observe(target, clock.getAsLong())) {
                admitted = false;
            }
            publishCards(clock.getAsLong());
        }

        public void card(CardDisplay display, Target target) {
            if (ended)
                display.deactivate();
            else
                cards.put(display, target);
        }

        public void entity(EntityScope scope) {
            if (!ended) {
                pageEntities.removeIf(EntityScope::isClosed);
                pageEntities.add(scope);
            } else
                scope.close();
        }

        public Feedback feedback(Target target) {
            return ClientDemandCoordinator.this.feedback(target);
        }

        public boolean preparing(Target target) {
            if (ended)
                return false;
            var preparation = hover != null && hoverPage == this && hover.target.equals(target) ? hover : null;
            return preparation != null && !preparation.ended && preparation.failure() == null
                && (preparation.operation == null || preparation.operation.unfinished());
        }

        public void removeCard(CardDisplay display) {
            cards.remove(display);
        }

        public void action(PageAction action) {
            if (ended)
                action.close();
            else
                actions.add(action);
        }

        private void advance(long now) {
            if (ended)
                return;
            eager = eagerLoading.getAsBoolean();
            var mode = pageResourceMode(timeline, now);
            if (!admitted && mode != ModelResourceDemand.Mode.READY
                && pointer.elapsedAtLeast(now, ContinuousDemand.HOVER_DWELL_MILLIS)) {
                admitted = true;
                var previous = hover;
                var target = pointer.current();
                hover = previous != null && !previous.ended && hoverPage == this && previous.target.equals(target)
                    ? previous : new Preparation(target, ModelResourcePurpose.GUI, WorkPriority.BACKGROUND);
                hoverPage = this;
                closeUnused(previous);
            }
            publishCards(now);
            actions.forEach(action -> action.advance(mode));
        }

        private void publishCards(long now) {
            var mode = pageResourceMode(timeline, now) == ModelResourceDemand.Mode.READY
                ? ModelResourceDemand.Mode.READY : ModelResourceDemand.Mode.OFFLINE;
            for (var card : cards.entrySet()) {
                var target = card.getValue();
                if (!eager && !(pointer.isCurrent(target)
                    && pointer.elapsedAtLeast(now, ContinuousDemand.HOVER_DWELL_MILLIS))) {
                    card.getKey().deactivate();
                } else {
                    var prepared = hover != null && hoverPage == this && hover.target.equals(target);
                    card.getKey().activate(prepared ? ModelResourceDemand.Mode.READY : mode);
                }
            }
        }

        @Override
        public void close() {
            if (ended)
                return;
            ended = true;
            pages.remove(this);
            if (hoverPage == this) {
                retiringHover = hover;
                hover = null;
                hoverPage = null;
            }
            pageEntities.forEach(scope -> scope.sealed = true);
            if (!frameActive)
                finishClose();
        }

        private void finishClose() {
            if (cleaned)
                return;
            cleaned = true;
            Throwable failure = null;
            var previousHover = retiringHover;
            retiringHover = null;
            failure = cleanup(failure, () -> closeUnused(previousHover));
            var previousCards = List.copyOf(cards.keySet());
            cards.clear();
            for (var card : previousCards)
                failure = cleanup(failure, card::deactivate);
            var previousEntities = List.copyOf(pageEntities);
            pageEntities.clear();
            for (var scope : previousEntities)
                failure = cleanup(failure, scope::close);
            var previousActions = List.copyOf(actions);
            actions.clear();
            for (var action : previousActions)
                failure = cleanup(failure, action::close);
            pointer.reset();
            throwCleanup(failure);
        }
    }

    public interface PageAction extends AutoCloseable {
        void advance(ModelResourceDemand.Mode allowed);

        @Override
        void close();
    }

    public final class EntityScope implements AutoCloseable {
        private final WeakReference<Installation> endpoint;
        private final ContinuousDemand<Use> timeline = new ContinuousDemand<>();
        private Operation operation;
        private Operation fallback;
        private Throwable resourceFailure;
        private Throwable reportedFailure;
        private boolean ended;

        private boolean frameInstallation;
        private boolean sealed;
        private ReadyInstallation claimed;

        private final WorkPriority priority;

        private EntityScope(Installation endpoint, WorkPriority priority) {
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
            return !ended && (claimed != null || operation != null && operation.unfinished()
                || fallback != null && fallback.unfinished());
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

        /** Acquires the receiver before releasing the source anchor; this path starts no resource work. */
        public @Nullable ReadyInstallation claimReadyInstallation() {
            var candidate = readyCandidate();
            if (candidate == null)
                return null;
            var lease = findReady(candidate.request);
            if (lease == null)
                return null;
            var intrinsic = candidate == fallback;
            var next = new ReadyInstallation(candidate.request, lease, intrinsic);
            claimed = next;
            if (intrinsic)
                fallback = null;
            else
                operation = null;
            try {
                candidate.close();
            } catch (RuntimeException | Error failure) {
                try {
                    next.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure)
                        failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            return next;
        }

        private @Nullable ResourceLease findReady(ResourceRequest request) {
            if (!resources.permitsAcquisition(request))
                return null;
            var lease = resources.findReady(request).orElse(null);
            if (lease == null)
                return null;
            final boolean current;
            try {
                current = lease.isCurrent(request) && lease.poll() instanceof AcquireResult.Ready;
            } catch (RuntimeException | Error failure) {
                try {
                    lease.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure)
                        failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            if (!current) {
                lease.close();
                return null;
            }
            return lease;
        }

        private @Nullable Operation readyCandidate() {
            if (ended || sealed || !frameInstallation || claimed != null)
                return null;
            var host = endpoint.get();
            if (host == null)
                return null;
            var input = host.assignment();
            if (!input.permitted())
                return null;
            var ready = readyOperation(host, operation, input.request());
            if (ready != null) {
                if (ready != operation) {
                    var previous = operation;
                    try {
                        timeline.observe(new Use(ready.request, resources.content(ready.request.modelId())),
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
            ready = readyOperation(host, fallback, input.fallback());
            if (ready != null && ready != fallback) {
                var previous = fallback;
                fallback = ready;
                if (previous != null)
                    previous.close();
            }
            return ready;
        }

        private @Nullable Operation readyOperation(Installation host, @Nullable Operation candidate,
            @Nullable ResourceRequest request) {
            if (request == null || request.purpose() != ModelResourcePurpose.GUI || host.matches(request, false)
                || host.failure(request) != null || !resources.permitsAcquisition(request))
                return null;
            if (candidate != null && candidate.request.equals(request) && candidate.ready()
                && candidate.anchor.isCurrent(request))
                return candidate;
            // A miss cannot lower the admission of work already in flight.
            var lease = findReady(request);
            if (lease == null)
                return null;
            var ready = new Operation(request, priority);
            ready.anchor = lease;
            return ready;
        }

        public final class ReadyInstallation implements AutoCloseable {
            private final ResourceRequest request;
            private final boolean fallback;
            private ResourceLease lease;
            private boolean completed;

            private ReadyInstallation(ResourceRequest request, ResourceLease lease, boolean fallback) {
                this.request = request;
                this.lease = lease;
                this.fallback = fallback;
            }

            public ResourceRequest request() {
                return request;
            }

            public boolean fallback() {
                return fallback;
            }

            /** The task now owns closure until installation consumes this independent capability. */
            public ResourceLease takeLease() {
                if (completed || lease == null)
                    throw new IllegalStateException("Ready installation lease has already transferred");
                var transferred = lease;
                lease = null;
                return transferred;
            }

            /** Called by the frame owner after joining; BUSY leaves the task responsible for its lease. */
            public void complete(InstallationResult outcome) {
                Objects.requireNonNull(outcome);
                if (completed)
                    throw new IllegalStateException("Ready installation is already complete");
                if (lease != null)
                    throw new IllegalStateException("Ready installation lease has not transferred");
                completed = true;
                if (claimed == this)
                    claimed = null;
                if (ended || sealed)
                    return;
                var host = endpoint.get();
                if (outcome == InstallationResult.REJECTED && host != null)
                    resourceFailure = Objects.requireNonNullElseGet(host.failure(request),
                        () -> new IllegalStateException("Model installation was rejected"));
                else if (outcome == InstallationResult.INSTALLED)
                    resources.reportActiveModelUse(request);
            }

            @Override
            public void close() {
                if (completed)
                    return;
                completed = true;
                if (claimed == this)
                    claimed = null;
                var owned = lease;
                lease = null;
                if (owned != null)
                    owned.close();
            }
        }

        private void advance(long now) {
            var host = endpoint.get();
            if (ended || host == null) {
                close();
                return;
            }
            if (sealed || claimed != null)
                return;
            var input = host.assignment();
            if (!input.permitted()) {
                clearOperations();
                return;
            }
            var request = input.request();
            if (request != null) {
                var use = new Use(request, resources.content(request.modelId()));
                if (timeline.observe(use, now)) {
                    var previous = operation;
                    operation = new Operation(request, priority);
                    if (previous != null)
                        previous.close();
                    resourceFailure = null;
                }
                if (host.matches(request, false)) {
                    if (operation != null) {
                        operation.close();
                        operation = null;
                    }
                    resources.reportActiveModelUse(request);
                } else if (host.failure(request) != null) {
                    resourceFailure = host.failure(request);
                    if (operation != null) {
                        operation.close();
                        operation = null;
                    }
                } else {
                    if (operation == null)
                        operation = new Operation(request, priority);
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
                        resources.reportActiveModelUse(request);
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
                if (fallback == null || !fallback.request.equals(input.fallback())) {
                    if (fallback != null)
                        fallback.close();
                    fallback = new Operation(input.fallback(), priority);
                }
                fallback.advance(ModelResourceDemand.Mode.ONLINE);
                if (!frameInstallation)
                    install(host, fallback, true);
            }
            if (resourceFailure != null && resourceFailure != reportedFailure && request != null) {
                reportedFailure = resourceFailure;
                var content = resources.content(request.modelId());
                if (content != null && !request.modelId()
                    .equals(resources.defaultResourceRequest("player", ModelResourcePurpose.FULL, null).modelId()))
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
            final InstallationResult result;
            try {
                result = host.install(candidate.request, lease, intrinsic, false);
            } catch (RuntimeException | Error failure) {
                candidate.close();
                throw failure;
            }
            if (result != InstallationResult.BUSY)
                candidate.anchor = null;
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
            entities.remove(this);
            clearOperations();
        }
    }

    private record Use(ResourceRequest request, @Nullable ModelContent content) {
    }

    private final class Preparation implements AutoCloseable {
        private final Target target;
        private final ModelResourcePurpose purpose;
        private final WorkPriority priority;
        private Operation operation;
        private ModelContent content;
        private Throwable failure;
        private boolean ended;

        private Preparation(Target target, ModelResourcePurpose purpose, WorkPriority priority) {
            this.target = target;
            this.purpose = purpose;
            this.priority = priority;
        }

        private void advance(ModelResourceDemand.Mode mode) {
            if (ended || failure != null)
                return;
            try {
                var request = purpose == ModelResourcePurpose.FULL ? resources.playerRequest(target)
                    : resources.resourceRequest(target.modelId(), "player", target.texture(), purpose, null);
                var nextContent = resources.content(request.modelId());
                if (operation == null || !operation.request.equals(request) || content != nextContent) {
                    var previous = operation;
                    var replacement = new Operation(request, priority);
                    replacement.advance(mode);
                    operation = replacement;
                    content = nextContent;
                    if (previous != null)
                        previous.close();
                } else
                    operation.advance(mode);
            } catch (RuntimeException error) {
                failure = error;
            }
        }

        private boolean ready() {
            return operation != null && operation.ready();
        }

        private Throwable failure() {
            return failure != null ? failure : operation != null ? operation.failure() : null;
        }

        @Override
        public void close() {
            ended = true;
            if (operation != null)
                operation.close();
        }
    }

    private final class Operation implements AutoCloseable {
        private final ResourceRequest request;
        private final WorkPriority priority;
        private final ModelResourceDemand acquisition;
        private ResourceLease anchor;

        private Operation(ResourceRequest request, WorkPriority priority) {
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

    private final class Intent {
        private final Target target;
        private final Preparation preparation;
        private final long started;
        private long id;
        private boolean sent;
        private boolean terminal;

        private Intent(Target target, Preparation preparation, long started) {
            this.target = target;
            this.preparation = preparation;
            this.started = started;
        }
    }
}
