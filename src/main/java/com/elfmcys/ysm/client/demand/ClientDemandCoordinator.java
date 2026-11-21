package com.elfmcys.ysm.client.demand;

import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.storage.StorageWriteBuffer;
import com.elfmcys.ysm.model.storage.PlayerPreferences;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import com.elfmcys.ysm.network.protocol.RoamingDictionary;
import org.jetbrains.annotations.Nullable;

import com.elfmcys.ysm.client.entity.EntityModelDemand;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/** Owns active demand rules; resource owners and installation endpoints retain their own facts. */
public final class ClientDemandCoordinator implements AutoCloseable {
    public static final long WAIT_MILLIS = 30_000;

    public record Target(@Nullable ModelId modelId, String texture) {
        public Target {
            texture = Objects.requireNonNullElse(texture, "");
        }

        public PublishedModelId identity() {
            return modelId == null ? PublishedModelId.INTRINSIC_DEFAULT : PublishedModelId.model(modelId);
        }
    }

    public interface Resources extends ModelResourceDemand.Access {
        @Nullable
        ModelContent content(ModelId modelId);

        default @Nullable ModelContent content(PublishedModelId modelId) {
            return modelId instanceof PublishedModelId.Model model ? content(model.hash()) : null;
        }

        ResourceRequest resourceRequest(ModelId modelId, String target, String texture, ModelResourcePurpose purpose,
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
        ModelResourceDemand.Mode mode, boolean permitted, @Nullable Object operation) {
        public Assignment(@Nullable ResourceRequest request, @Nullable ResourceRequest fallback,
            ModelResourceDemand.Mode mode, boolean permitted) {
            this(request, fallback, mode, permitted, null);
        }
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

        default InstallationResult install(ResourceRequest request, ResourceLease lease, boolean fallback, boolean full,
            Object operation) {
            return install(request, lease, fallback, full);
        }
    }

    public interface CardDisplay {
        void activate(ModelResourceDemand.Mode mode);

        void deactivate();
    }

    public record SelectionContext(Object owner, UUID player, Object preferenceBinding, boolean remote,
        boolean available) {
        public boolean matches(SelectionContext other) {
            return other != null && owner == other.owner && Objects.equals(player, other.player)
                && preferenceBinding == other.preferenceBinding && remote == other.remote;
        }
    }

    public record InitialRoaming(NamedRoamingSnapshot payload, boolean persistable) {
        public InitialRoaming {
            Objects.requireNonNull(payload);
        }
    }

    public interface Selections {
        default boolean updateTexture(Target target) {
            return false;
        }

        @Nullable
        SelectionContext context();

        Target authority();

        default boolean installed(Target target) {
            return installed(target, null);
        }

        /** A non-null application requires publication for that exact identity. */
        boolean installed(Target target, @Nullable Object application);

        default @Nullable Throwable failure(Target target) {
            return null;
        }

        @Nullable
        InitialRoaming retainedInitial(SelectionContext context, Target target);

        void send(SelectionContext context, Target target, long intentId, @Nullable NamedRoamingSnapshot initial,
            boolean reinitialize);

        void reportFailure(Throwable failure);

        void reportPreferenceReadFailure(Throwable failure);
    }

    public record Feedback(boolean waiting, @Nullable Throwable failure) {
        private static final Feedback IDLE = new Feedback(false, null);
    }

    public enum FailureStage {
        PREPARATION, PREFERENCES, SEND, REJECTED, INSTALLATION, TIMEOUT
    }

    public static final class SelectionFailure extends RuntimeException {
        private final FailureStage stage;

        public SelectionFailure(FailureStage stage, Throwable cause) {
            super("Model selection failed during " + stage, cause);
            this.stage = stage;
        }

        public FailureStage stage() {
            return stage;
        }
    }

    public enum SelectionKind {
        SELECT, RESELECT, CLEAR
    }

    public record SelectionAssociation(SelectionContext context, Target target, long id, SelectionKind kind,
        @Nullable InitialRoaming initial, @Nullable RoamingDictionary dictionary) {
    }

    /** Proves that transport submission was never entered. Other send failures are ambiguous. */
    public static final class NotSubmitted extends IllegalStateException {
        public NotSubmitted(String message) {
            super(message);
        }
    }

    private SelectionAssociation association;
    private final LongSupplier clock;
    private final Resources resources;
    private final Selections selections;
    private final PlayerPreferences preferences;
    private final BooleanSupplier eagerLoading;
    private final List<EntityModelDemand> entities = new ArrayList<>();
    private final Set<PageScope> pages = new HashSet<>();
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
        this(clock, resources, selections, null, eagerLoading);
    }

    public ClientDemandCoordinator(LongSupplier clock, Resources resources, Selections selections,
        @Nullable PlayerPreferences preferences, BooleanSupplier eagerLoading) {
        this.clock = Objects.requireNonNull(clock);
        this.resources = Objects.requireNonNull(resources);
        this.selections = Objects.requireNonNull(selections);
        this.preferences = preferences;
        this.eagerLoading = Objects.requireNonNull(eagerLoading);
    }

    public EntityModelDemand register(Installation endpoint) {
        return register(endpoint, WorkPriority.BACKGROUND);
    }

    public EntityModelDemand register(Installation endpoint, WorkPriority priority) {
        if (closed)
            throw new IllegalStateException("Demand owner is closed");
        var scope = new EntityModelDemand(endpoint, priority, resources, clock, entities::remove);
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
        return !feedback.waiting() && (Objects.equals(observedAuthority, authority) || selections.installed(authority));
    }

    public void select(Target target) {
        select(target, SelectionKind.SELECT);
    }

    private void select(Target target, SelectionKind kind) {
        Objects.requireNonNull(target);
        var context = synchronizeOwner();
        if (closed || context == null || !context.available())
            return;
        if (kind == SelectionKind.SELECT && target.identity().equals(selections.authority().identity())
            && !target.texture().equals(selections.authority().texture()) && selections.updateTexture(target))
            return;
        var previous = current;
        retire(previous);
        association = null;
        var preparation = previous != null && !previous.preparation.ended && previous.target.equals(target)
            ? previous.preparation : new Preparation(target, ModelResourcePurpose.FULL, WorkPriority.LOCAL_PLAYER);
        var now = clock.getAsLong();
        if (observedAuthority == null)
            observedAuthority = selections.authority();
        selectionTimeline.observe(target, now);
        current = new Intent(context, target, preparation, now, kind);
        if (idsExhausted) {
            failIntent(FailureStage.SEND,
                new IllegalStateException("Model selection intent identifiers are exhausted"));
        } else {
            current.id = nextIntentId;
            if (nextIntentId == Long.MAX_VALUE)
                idsExhausted = true;
            else
                nextIntentId++;
        }
        if (!current.terminal) {
            feedback =
                new Feedback(context.remote() || kind != SelectionKind.SELECT || !selections.installed(target), null);
            feedbackTarget = target;
            if (context.remote() || kind == SelectionKind.CLEAR) {
                try {
                    current.initial = kind == SelectionKind.CLEAR
                        ? new InitialRoaming(new NamedRoamingSnapshot(new String[0], new float[0]), true)
                        : selections.retainedInitial(context, target);
                } catch (RuntimeException failure) {
                    failIntent(FailureStage.PREFERENCES, failure);
                }
            }
        }
        closeUnused(previous == null ? null : previous.preparation);
    }

    public void reselect(Target target) {
        select(target, SelectionKind.RESELECT);
    }

    public void clearAndReselect(Target target) {
        select(target, SelectionKind.CLEAR);
    }

    public void cancelSelection(PublishedModelId identity) {
        if (association != null && association.target().identity().equals(identity))
            association = null;
        if (current == null || !current.target.identity().equals(identity))
            return;
        retire(current);
        feedback = Feedback.IDLE;
        feedbackTarget = null;
        authorityWaiting = false;
        closeUnused(current.preparation);
    }

    /** Immutable input retained independently of feedback and resource preparation. */
    public @Nullable SelectionAssociation association(SelectionContext context, long intentId, Target target) {
        var value = association;
        return value != null && value.id() == intentId && intentId != 0 && value.context().matches(context)
            && value.target().equals(target) ? value : null;
    }

    public void selectionCommitted(SelectionContext context, long intentId, Target target, Object application) {
        if (association(context, intentId, target) == null)
            return;
        association = null;
        if (current != null && current.id == intentId && current.context.matches(context)) {
            current.application = application;
            current.initial = null;
        }
    }

    public void tick() {
        if (closed)
            return;
        var context = synchronizeOwner();
        var now = clock.getAsLong();
        for (var page : List.copyOf(pages))
            page.advance(now);
        if (current != null && !current.terminal) {
            var intent = current;
            var mode = resourceMode(selectionTimeline, now);
            if (now - intent.started >= WAIT_MILLIS)
                failIntent(FailureStage.TIMEOUT, new IllegalStateException("Model selection timed out"));
            if (!intent.terminal && (!selections.installed(intent.target)
                || intent.context.remote() && !intent.sent && (intent.initial == null || intent.dictionary == null)))
                intent.preparation.advance(mode);
            if (!intent.terminal && !intent.sending && !intent.sent && context != null && context.available()
                && selectionTimeline.effectEligible(!selections.installed(intent.target) && !intent.preparation.ready(),
                    now, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS)) {
                try {
                    prepareInitial(intent);
                } catch (RuntimeException failure) {
                    failIntent(FailureStage.PREPARATION, failure);
                }
                if (current == intent && !intent.terminal
                    && (!context.remote() || intent.initial != null && intent.dictionary != null)) {
                    var latest = synchronizeOwner();
                    if (current == intent && intent.context.matches(latest) && latest.available()) {
                        // Record the attempt before transport can synchronously reenter this owner.
                        intent.sending = true;
                        intent.sent = true;
                        association = new SelectionAssociation(intent.context, intent.target, intent.id, intent.kind,
                            intent.initial, intent.dictionary);
                        try {
                            selections.send(intent.context, intent.target, intent.id,
                                intent.initial == null ? null : intent.initial.payload(),
                                intent.kind != SelectionKind.SELECT);
                        } catch (RuntimeException failure) {
                            if (failure instanceof NotSubmitted && current == intent)
                                association = null;
                            if (current == intent)
                                failIntent(FailureStage.SEND, failure);
                        } finally {
                            intent.sending = false;
                        }
                    }
                }
            }
        }
        if (hover != null)
            hover.advance(pageResourceMode(hoverPage.timeline, now));
        // Receivers acquire independent leases while the preparation anchors are still alive.
        for (var scope : List.copyOf(entities))
            scope.advance(now);
        if (current != null && !current.terminal) {
            if (!current.sending && current.sent && (!current.context.remote() || current.application != null)
                && (current.application == null ? selections.installed(current.target)
                    : selections.installed(current.target, current.application)))
                finishIntent();
            else if ((!current.context.remote() || current.application != null)
                && selections.failure(current.target) != null)
                failIntent(FailureStage.INSTALLATION, selections.failure(current.target));
        }
        observeAuthority(now);
    }

    private void prepareInitial(Intent intent) {
        if (!intent.context.remote())
            return;
        var operation = intent.preparation.operation;
        if (intent.dictionary == null) {
            if (operation == null || operation.anchor == null) {
                if (intent.preparation.failure() != null)
                    failIntent(FailureStage.PREPARATION, intent.preparation.failure());
                return;
            }
            var ready = (AcquireResult.Ready)operation.anchor.poll();
            var fields = ready.target().animationStore().metadata().roamingFields();
            intent.fields = Set.copyOf(fields);
            intent.dictionary = RoamingDictionary.declarations(fields);
        }
        if (intent.initial != null)
            return;
        Throwable readFailure;
        try {
            if (intent.restore == null) {
                intent.restore = Objects.requireNonNull(preferences, "Remote selection requires player preferences")
                    .restoreRoaming(intent.context.player(), intent.target.identity(), intent.fields);
                intent.restoreResult = intent.restore.result().toCompletableFuture();
            }
            if (!intent.restoreResult.isDone())
                return;
            var result = intent.restoreResult.join();
            if (!result.succeeded() || result.rows().size() != 1) {
                readFailure = new IllegalStateException("Selection roaming restore failed: " + result);
            } else {
                intent.restore = null;
                intent.restoreResult = null;
                intent.initial = new InitialRoaming(result.rows().get(0), true);
                return;
            }
        } catch (RuntimeException failure) {
            readFailure =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        }
        intent.restore = null;
        intent.restoreResult = null;
        intent.initial = new InitialRoaming(new NamedRoamingSnapshot(new String[0], new float[0]), false);
        selections.reportPreferenceReadFailure(readFailure);
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
        if (current != null && !current.context.matches(context)) {
            retire(current);
            association = null;
            authorityWaiting = false;
            feedback = Feedback.IDLE;
            feedbackTarget = null;
            closeUnused(current.preparation);
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
            var failure = new SelectionFailure(FailureStage.INSTALLATION, selections.failure(authority));
            feedback = new Feedback(false, failure);
            feedbackTarget = authority;
            selections.reportFailure(failure);
        } else if (authorityWaiting && now - authorityStarted >= WAIT_MILLIS) {
            authorityWaiting = false;
            var failure = new SelectionFailure(FailureStage.TIMEOUT,
                new IllegalStateException("Authoritative model installation timed out"));
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
        retire(current);
        authorityWaiting = false;
        feedback = Feedback.IDLE;
        feedbackTarget = null;
        closeUnused(current.preparation);
    }

    private void failIntent(FailureStage stage, Throwable cause) {
        if (current == null || current.terminal)
            return;
        var failure = new SelectionFailure(stage, cause);
        retire(current);
        authorityWaiting = false;
        feedback = new Feedback(false, failure);
        feedbackTarget = current.target;
        closeUnused(current.preparation);
        selections.reportFailure(failure);
    }

    private void retire(@Nullable Intent intent) {
        if (intent == null)
            return;
        intent.terminal = true;
        intent.initial = null;
        var restore = intent.restore;
        intent.restore = null;
        intent.restoreResult = null;
        if (restore != null)
            restore.cancel();
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
        association = null;
        hover = null;
        hoverPage = null;
        var failure = cleanup(null, () -> retire(previous));
        failure = cleanup(failure, () -> closeUnused(previous == null ? null : previous.preparation));
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
        private final Set<EntityModelDemand> pageEntities = Collections.newSetFromMap(new IdentityHashMap<>());
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

        public void entity(EntityModelDemand scope) {
            if (!ended) {
                pageEntities.removeIf(EntityModelDemand::isClosed);
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
            pageEntities.forEach(EntityModelDemand::seal);
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
        private final SelectionKind kind;
        private final SelectionContext context;
        private final Target target;
        private final Preparation preparation;
        private final long started;
        private long id;
        private boolean sending;
        private boolean sent;
        private boolean terminal;
        private Object application;
        private InitialRoaming initial;
        private RoamingDictionary dictionary;
        private Set<String> fields;
        private StorageWriteBuffer.Operation<NamedRoamingSnapshot> restore;
        private CompletableFuture<StorageResult<NamedRoamingSnapshot>> restoreResult;

        private Intent(SelectionContext context, Target target, Preparation preparation, long started,
            SelectionKind kind) {
            this.context = context;
            this.target = target;
            this.preparation = preparation;
            this.started = started;
            this.kind = kind;
        }
    }
}
