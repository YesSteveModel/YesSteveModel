package com.elfmcys.ysm.model.session.server;

import com.elfmcys.ysm.model.catalog.content.AssetRef;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPolicy;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Owns negotiation, committed catalog authority, grants and selection for one adapter-owned connection.
 *
 * <p><b>Invariant:</b> Negotiation ACTIVE does not imply a committed catalog. A committed authority
 * is a complete value; notification failure cannot undo that value. Resource permission applies
 * at request admission, while an accepted effect follows its exact member incarnation.
 *
 * <p><b>Execution:</b> Methods synchronize this object's state. The server adapter still owns
 * exact-connection checks and main-thread capability writes. A returned authority is a snapshot,
 * not a reservation against later policy or catalog changes.
 *
 * <p><b>Seam:</b> [session authority commit](docs/architecture/model-management/sessions/overview.md#权威提交与通知).
 */
public final class ServerModelSession {
    private final Supplier<CatalogSnapshot> offeredCatalog;
    private final boolean syncRoaming;
    private final PlayerStateReportPolicy playerStateReportPolicy;
    private AuthoritySnapshot authority;
    private NegotiationState state = NegotiationState.PENDING;
    private long nextPublicationId;
    private boolean publicationIdExhausted;
    private boolean publicationCommitted;
    private boolean playerStateFullAccepted;
    private Map<ModelFileIdentity, Object> memberIncarnations = Map.of();

    public ServerModelSession(Supplier<CatalogSnapshot> offeredCatalog, boolean syncRoaming) {
        this(offeredCatalog, syncRoaming, 1);
    }

    ServerModelSession(Supplier<CatalogSnapshot> offeredCatalog, boolean syncRoaming, long firstPublicationId) {
        this.offeredCatalog = Objects.requireNonNull(offeredCatalog, "offeredCatalog");
        this.syncRoaming = syncRoaming;
        playerStateReportPolicy = PlayerStateReportPolicy.gameServer(syncRoaming);
        if (firstPublicationId == 0) {
            throw new IllegalArgumentException("Publication ID must be unsigned-positive");
        }
        nextPublicationId = firstPublicationId;
    }

    public synchronized boolean activate() {
        if (state != NegotiationState.PENDING) {
            return false;
        }
        authority = buildAuthority(Objects.requireNonNull(offeredCatalog.get(), "offered catalog"), Set.of(),
            new Selection.IntrinsicDefault(), false, false);
        state = NegotiationState.ACTIVE;
        return true;
    }

    public synchronized boolean decline() {
        if (state != NegotiationState.PENDING) {
            return false;
        }
        state = NegotiationState.DECLINED;
        return true;
    }

    public synchronized boolean pending() {
        return state == NegotiationState.PENDING;
    }

    public synchronized boolean active() {
        return state == NegotiationState.ACTIVE;
    }

    public boolean syncRoaming() {
        return syncRoaming;
    }

    public synchronized PlayerStateReportPolicy playerStateReportPolicy() {
        return playerStateReportPolicy;
    }

    public synchronized boolean acceptsPlayerStateReport(PlayerStateReportProto report) {
        var full = report.mode() == StateWriteMode.FULL;
        return active() && playerStateReportPolicy.acceptsProjection(report) && (full || playerStateFullAccepted);
    }

    /**
     * Records the baseline frontier after the domain owner applies the report's requested facts.
     * Validation or receipt alone must not call this method. Later best-effort notification does not roll it back.
     *
     * <p><b>Seam:</b> [real report baseline](docs/architecture/network/player-state.md#表现报告与真实基线).
     *
     * @param full whether the successfully applied report establishes a FULL baseline
     * @throws IllegalStateException if the session is inactive or a DELTA has no accepted FULL baseline
     */
    public synchronized void commitPlayerStateReport(boolean full) {
        if (!active() || !full && !playerStateFullAccepted) {
            throw new IllegalStateException("Player-state report has no FULL baseline");
        }
        if (full) {
            playerStateFullAccepted = true;
        }
    }

    public synchronized long allocatePublicationId() {
        if (!active() || publicationIdExhausted) {
            throw new IllegalStateException("Publication ID stream is unavailable");
        }
        var result = nextPublicationId;
        if (result == -1L) {
            publicationIdExhausted = true;
        } else {
            nextPublicationId = result + 1;
        }
        return result;
    }

    public synchronized Set<Hash256> grants() {
        return authority == null ? Set.of() : authority.grants();
    }

    public synchronized Selection selection() {
        return authority == null ? new Selection.IntrinsicDefault() : authority.selection();
    }

    public synchronized AuthoritySnapshot authority() {
        if (authority == null) {
            throw new IllegalStateException("Session authority is not active");
        }
        return authority;
    }

    public synchronized void setGrants(Set<Hash256> next) {
        Objects.requireNonNull(next, "next");
        var current = authority();
        if (!current.catalog().byModelId().keySet().containsAll(next)) {
            throw new IllegalArgumentException("Grant references a missing model");
        }
        var nextSelection = current.selection();
        var nextIgnoreGrants = current.ignoreGrants();
        if (nextSelection instanceof Selection.Model model && !canSelect(model.modelId(), current.catalog(), next)
            && !ignoresGrantsFor(model.modelId(), current)) {
            nextSelection = new Selection.IntrinsicDefault();
            nextIgnoreGrants = false;
        }
        replaceAuthority(new AuthoritySnapshot(current.catalog(), current.byContainer(), next, nextSelection,
            nextIgnoreGrants, current.restrictedAuth()));
    }

    public synchronized SelectionResult select(Selection requested) {
        Objects.requireNonNull(requested, "requested");
        if (!active()) {
            return SelectionResult.INVALID_REQUEST;
        }
        var current = authority();
        if (requested instanceof Selection.IntrinsicDefault) {
            selectIntrinsicDefault(current);
            return SelectionResult.ACCEPTED;
        }
        var selected = (Selection.Model)requested;
        var record = current.catalog().byModelId().get(selected.modelId());
        if (record == null) {
            selectIntrinsicDefault(current);
            return SelectionResult.NOT_FOUND;
        }
        if (!canSelect(selected.modelId(), current.catalog(), current.grants())
            && !ignoresGrantsFor(selected.modelId(), current)) {
            selectIntrinsicDefault(current);
            return SelectionResult.UNAUTHORIZED;
        }
        if (!hasTexture(record, selected.textureId())) {
            selectIntrinsicDefault(current);
            return SelectionResult.INVALID_REQUEST;
        }
        var nextIgnoreGrants = current.ignoreGrants();
        if (current.selection() instanceof Selection.Model previous && !previous.modelId().equals(selected.modelId())) {
            nextIgnoreGrants = false;
        }
        replaceAuthority(new AuthoritySnapshot(current.catalog(), current.byContainer(), current.grants(), requested,
            nextIgnoreGrants, current.restrictedAuth()));
        return SelectionResult.ACCEPTED;
    }

    public synchronized SelectionResult selectForced(Selection.Model requested, boolean ignoreGrants) {
        Objects.requireNonNull(requested, "requested");
        if (!active()) {
            return SelectionResult.INVALID_REQUEST;
        }
        var current = authority();
        var record = current.catalog().byModelId().get(requested.modelId());
        if (record == null) {
            return SelectionResult.NOT_FOUND;
        }
        if (!ignoreGrants && !canSelect(requested.modelId(), current.catalog(), current.grants())) {
            return SelectionResult.UNAUTHORIZED;
        }
        if (!hasTexture(record, requested.textureId())) {
            return SelectionResult.INVALID_REQUEST;
        }
        replaceAuthority(new AuthoritySnapshot(current.catalog(), current.byContainer(), current.grants(), requested,
            ignoreGrants, current.restrictedAuth()));
        return SelectionResult.ACCEPTED;
    }

    /**
     * Tests current admission policy for a new request against the committed authority.
     * Callers must apply this gate before acquiring protected sources; a cache hit grants no permission.
     * Forced selection can admit private runtime chunks without a grant under restricted authorization.
     * The architecture leaves that exception's policy precedence unresolved.
     *
     * @param modelId the requested model identity
     * @param kind the requested resource category
     * @param restrictedAuth whether the current server policy restricts private runtime content
     * @return false when the session, publication, member or access policy rejects new use
     */
    public synchronized boolean canRequest(Hash256 modelId, AssetRef.Kind kind, boolean restrictedAuth) {
        if (!active() || !publicationCommitted) {
            return false;
        }
        var current = authority();
        var record = current.catalog().byModelId().get(modelId);
        if (record == null) {
            return false;
        }
        if (record.entry().access() == CatalogAccess.PUBLIC || current.grants().contains(modelId) || !restrictedAuth) {
            return true;
        }
        return kind == AssetRef.Kind.PREVIEW || kind == AssetRef.Kind.ICON
            || kind == AssetRef.Kind.CHUNK && runtimeContentAllowed(modelId, current, restrictedAuth);
    }

    public synchronized boolean updateContentPolicy(boolean restrictedAuth) {
        var current = authority();
        if (current.restrictedAuth() == restrictedAuth)
            return false;
        replaceAuthority(new AuthoritySnapshot(current.catalog(), current.byContainer(), current.grants(),
            current.selection(), current.ignoreGrants(), restrictedAuth));
        return true;
    }

    private static boolean runtimeContentAllowed(Hash256 modelId, AuthoritySnapshot current, boolean restrictedAuth) {
        var record = current.catalog().byModelId().get(modelId);
        return record != null && (record.entry().access() == CatalogAccess.PUBLIC || current.grants().contains(modelId)
            || !restrictedAuth || ignoresGrantsFor(modelId, current));
    }

    /** Used while the initial authority is being assembled as well as after publication. */
    public synchronized boolean containsModel(Hash256 modelId) {
        return authorityCatalog().byModelId().containsKey(modelId);
    }

    /** Asset lookup never falls back to the live global catalog. */
    public synchronized Optional<CatalogRecord> findModel(Hash256 modelId) {
        return !publicationCommitted ? Optional.empty()
            : Optional.ofNullable(authority().catalog().byModelId().get(modelId));
    }

    /** Metadata lookup is exact and private to committed server authority. */
    public synchronized Optional<CatalogRecord> findPublished(Hash256 containerId) {
        return !publicationCommitted ? Optional.empty()
            : Optional.ofNullable(authority().byContainer().get(containerId));
    }

    public synchronized Optional<ModelPackDescriptor> findPack(String hierarchy) {
        if (!publicationCommitted) {
            return Optional.empty();
        }
        return authority().catalog().packs().stream().filter(pack -> pack.hierarchy().equals(hierarchy)).findFirst();
    }

    /** Atomically commits server authority before any matching notification is attempted. */
    public synchronized CatalogTransition commitCatalog(CatalogSnapshot catalog) {
        return commitCatalog(catalog, grants());
    }

    /** Atomically commits server authority before any matching notification is attempted. */
    public synchronized CatalogTransition commitCatalog(CatalogSnapshot catalog, Set<Hash256> requestedGrants) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(requestedGrants, "requestedGrants");
        var previous = authority();
        var nextGrants =
            requestedGrants.stream().filter(catalog.byModelId()::containsKey).collect(Collectors.toUnmodifiableSet());
        var next = buildAuthority(catalog, nextGrants, previous.selection(), previous.ignoreGrants(),
            previous.restrictedAuth());
        var incarnations = new LinkedHashMap<ModelFileIdentity, Object>();
        next.catalog().byModelId().values().forEach(record -> {
            var representation = record.binding().content().representation();
            if (representation == null)
                return;
            var identity = representation.identity();
            incarnations.put(identity, memberIncarnations.getOrDefault(identity, new Object()));
        });
        memberIncarnations = Map.copyOf(incarnations);
        replaceAuthority(next);
        publicationCommitted = true;
        return new CatalogTransition(previous, next);
    }

    public synchronized boolean hasPublishedCatalog() {
        return publicationCommitted;
    }

    public synchronized void close() {
        state = NegotiationState.CLOSED;
        publicationCommitted = false;
        playerStateFullAccepted = false;
        authority = null;
        memberIncarnations = Map.of();
    }

    /**
     * Captures one commit gate for an admitted request's exact published member.
     * This method does not replace the request's access check.
     *
     * @param identity the exact metadata identity admitted by the request owner
     * @return empty if no active committed member exists; otherwise an independent, one-commit gate
     */
    public synchronized Optional<Effect> effect(ModelFileIdentity identity) {
        var incarnation = memberIncarnations.get(identity);
        return active() && publicationCommitted && incarnation != null ? Optional.of(new Effect(identity, incarnation))
            : Optional.empty();
    }

    /**
     * One request's commit right for an exact session member, independent of later grant changes.
     *
     * <p><b>Execution:</b> Commit and retirement share the session monitor. The replacement must execute
     * only the prepared atomic replacement; it must not wait for work that needs this session monitor.
     * Cancellation or member replacement rejects a later commit with {@link StaleEffectException}.
     * An I/O failure from the replacement remains an I/O failure and does not set the committed flag.
     *
     * <p><b>Seam:</b> [request effect lifetime](docs/architecture/model-management/sessions/overview.md#成员观察与管理提交).
     */
    public final class Effect implements AtomicSharedCache.CommitGate {
        private final ModelFileIdentity identity;
        private final Object incarnation;
        private boolean cancelled;
        private boolean committed;

        private Effect(ModelFileIdentity identity, Object incarnation) {
            this.identity = identity;
            this.incarnation = incarnation;
        }

        public void cancel() {
            synchronized (ServerModelSession.this) {
                cancelled = true;
            }
        }

        public boolean current() {
            synchronized (ServerModelSession.this) {
                return !cancelled && !committed && active() && memberIncarnations.get(identity) == incarnation;
            }
        }

        @Override
        public void commit(AtomicSharedCache.AtomicReplacement replacement) throws java.io.IOException {
            synchronized (ServerModelSession.this) {
                if (cancelled || committed || !active() || memberIncarnations.get(identity) != incarnation) {
                    throw new StaleEffectException();
                }
                replacement.run();
                committed = true;
            }
        }
    }

    public static final class StaleEffectException extends java.io.IOException {
        public StaleEffectException() {
            super("Request connection or catalog member retired");
        }
    }

    private CatalogSnapshot authorityCatalog() {
        return authority == null ? Objects.requireNonNull(offeredCatalog.get(), "offered catalog")
            : authority.catalog();
    }

    private static AuthoritySnapshot buildAuthority(CatalogSnapshot catalog, Set<Hash256> grants, Selection selection,
        boolean ignoreGrants, boolean restrictedAuth) {
        var byContainer = new LinkedHashMap<Hash256, CatalogRecord>();
        catalog.byModelId().values().forEach(record -> {
            var representation = record.binding().content().representation();
            if (representation == null) {
                return;
            }
            var containerId = representation.containerId();
            if (byContainer.putIfAbsent(containerId, record) != null) {
                throw new IllegalArgumentException("Published catalog reuses a container identity");
            }
        });
        var nextSelection = selection;
        var nextIgnoreGrants = ignoreGrants;
        if (nextSelection instanceof Selection.Model selected) {
            var record = catalog.byModelId().get(selected.modelId());
            if (record == null || !canSelect(selected.modelId(), catalog, grants) && !nextIgnoreGrants
                || !hasTexture(record, selected.textureId())) {
                nextSelection = new Selection.IntrinsicDefault();
                nextIgnoreGrants = false;
            }
        }
        return new AuthoritySnapshot(catalog, byContainer, grants, nextSelection, nextIgnoreGrants, restrictedAuth);
    }

    private static boolean canSelect(Hash256 modelId, CatalogSnapshot catalog, Set<Hash256> grants) {
        var record = catalog.byModelId().get(modelId);
        return record != null && (record.entry().access() == CatalogAccess.PUBLIC || grants.contains(modelId));
    }

    private static boolean hasTexture(CatalogRecord record, String textureId) {
        if (record == null || record.binding().content().modelFile() == null) {
            return true;
        }
        return record.binding().content().modelFile().getPlayer().getTextureNames().contains(textureId);
    }

    private static boolean ignoresGrantsFor(Hash256 modelId, AuthoritySnapshot authority) {
        return authority.ignoreGrants() && authority.selection() instanceof Selection.Model selected
            && selected.modelId().equals(modelId);
    }

    private void selectIntrinsicDefault(AuthoritySnapshot current) {
        replaceAuthority(new AuthoritySnapshot(current.catalog(), current.byContainer(), current.grants(),
            new Selection.IntrinsicDefault(), false, current.restrictedAuth()));
    }

    private void replaceAuthority(AuthoritySnapshot next) {
        if (authority != null && !Objects.equals(reportModelId(authority), reportModelId(next))) {
            playerStateFullAccepted = false;
        }
        authority = next;
    }

    private static Hash256 reportModelId(AuthoritySnapshot snapshot) {
        if (snapshot.selection() instanceof Selection.Model selected) {
            return selected.modelId();
        }
        return snapshot.catalog().byModelId().values().stream()
            .filter(record -> record.location().rootKind() == CatalogRootKind.BUILTIN)
            .filter(record -> record.location().path().value().equals("default"))
            .map(record -> record.entry().modelId()).findFirst().orElse(null);
    }

    public record AuthoritySnapshot(CatalogSnapshot catalog, Map<Hash256, CatalogRecord> byContainer,
        Set<Hash256> grants, Selection selection, boolean ignoreGrants, boolean restrictedAuth) {
        public boolean runtimeContentAllowed(Hash256 modelId) {
            return ServerModelSession.runtimeContentAllowed(modelId, this, restrictedAuth);
        }

        public AuthoritySnapshot {
            Objects.requireNonNull(catalog, "catalog");
            byContainer = Map.copyOf(byContainer);
            grants = Set.copyOf(grants);
            Objects.requireNonNull(selection, "selection");
            if (!catalog.byModelId().keySet().containsAll(grants)) {
                throw new IllegalArgumentException("Grant references a missing publication entry");
            }
            if (selection instanceof Selection.Model selected) {
                var record = catalog.byModelId().get(selected.modelId());
                if (record == null || record.entry().access() == CatalogAccess.AUTHORIZED
                    && !grants.contains(selected.modelId()) && !ignoreGrants) {
                    throw new IllegalArgumentException("Selection is not eligible in server authority");
                }
            } else if (ignoreGrants) {
                throw new IllegalArgumentException("Intrinsic default cannot ignore grants");
            }
        }
    }

    public record CatalogTransition(AuthoritySnapshot previous, AuthoritySnapshot current) {
        public CatalogTransition {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
        }
    }

    public enum SelectionResult {
        ACCEPTED, UNAUTHORIZED, NOT_FOUND, INVALID_REQUEST
    }

    private enum NegotiationState {
        PENDING, ACTIVE, DECLINED, CLOSED
    }
}
