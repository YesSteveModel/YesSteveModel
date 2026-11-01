package com.elfmcys.ysm.model.session.client;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.session.client.state.ActivationFailure;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.version.VersionCompatibility;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns remote publication and content activation for one connection chosen by the adapter.
 *
 * <p><b>Invariant:</b> ACTIVE means that remote authority exists; individual content can remain
 * Pending or Failed. Catalog queries expose Ready content plus the intrinsic default.
 * Publication, activation and render-target installation are separate transitions.
 *
 * <p><b>Execution:</b> Methods serialize this object's state. The adapter must reject a retired
 * connection before calling them; this object has no Minecraft connection identity.
 * Previously returned immutable snapshots remain values after replacement or close.
 *
 * <p><b>Seam:</b> [remote publication and activation](docs/architecture/model-management/sessions/overview.md#目录权威与内容激活).
 */
public final class ClientModelSession {
    private final CatalogSnapshot localCatalog;
    private final CatalogSnapshot intrinsicDefault;
    private final boolean explicitLocal;
    private State state;
    private RemotePublicationSnapshot remote;
    private ActivationSnapshot activation;
    private String offeredTransportVersion;
    private Boolean offeredSyncRoaming;
    private boolean declined;
    private final LinkedHashMap<ModelFileIdentity, PreviewObservation> previews = new LinkedHashMap<>();

    public ClientModelSession(SessionMode requestedMode, boolean channelPresent, CatalogSnapshot localCatalog) {
        Objects.requireNonNull(localCatalog, "localCatalog");
        explicitLocal = requestedMode == SessionMode.LOCAL;
        state = explicitLocal || !channelPresent ? State.LOCAL : State.NEGOTIATING;
        this.localCatalog = state == State.LOCAL ? localCatalog : CatalogSnapshot.empty();
        intrinsicDefault = localCatalog.intrinsicDefaultOnly();
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Optional<Response> onServerHello(String transportVersion, boolean syncRoaming) {
        if (state == State.CLOSED) {
            return Optional.empty();
        }
        if (explicitLocal) {
            if (declined) {
                return Optional.empty();
            }
            declined = true;
            return Optional.of(new Response(false));
        }
        if (!VersionCompatibility.isCompatible(ProtocolVersion.TRANSPORT_VERSION, transportVersion, String::equals)) {
            failCompleteTransfer();
            return Optional.empty();
        }
        if (state != State.NEGOTIATING) {
            return Optional.empty();
        }
        if (offeredTransportVersion == null) {
            offeredTransportVersion = transportVersion;
            offeredSyncRoaming = syncRoaming;
        } else if (!offeredTransportVersion.equals(transportVersion) || offeredSyncRoaming != syncRoaming) {
            failCompleteTransfer();
            return Optional.empty();
        }
        return Optional.of(new Response(true));
    }

    /**
     * Commits all remote collections together and starts their content as Pending.
     * An ACTIVE session instead applies the publication replacement rules.
     *
     * @param snapshot the non-null, fully assembled publication admitted by the receiver
     * @throws IllegalStateException if the initial publication has no accepted hello or the state is invalid
     */
    public synchronized void publishFull(RemotePublicationSnapshot snapshot) {
        if (state == State.ACTIVE) {
            publishPublication(snapshot);
            return;
        }
        requireState(State.NEGOTIATING);
        if (offeredTransportVersion == null) {
            throw new IllegalStateException("Full publication arrived before an accepted hello");
        }
        remote = Objects.requireNonNull(snapshot, "snapshot");
        activation = ActivationSnapshot.pending(snapshot);
        state = State.ACTIVE;
        reconcilePreviews(snapshot);
    }

    /**
     * Replaces authority and preserves activation only for unchanged exact content tuples.
     * Grant changes affect new use; this transition does not close leases already held by consumers.
     *
     * @param snapshot the non-null, fully assembled replacement publication
     * @throws IllegalStateException if the session is not ACTIVE
     */
    public synchronized void publishPublication(RemotePublicationSnapshot snapshot) {
        requireState(State.ACTIVE);
        Objects.requireNonNull(snapshot, "snapshot");
        var states = new LinkedHashMap<Hash256, ActivationSnapshot.State>();
        snapshot.entries().forEach((modelId, entry) -> {
            var previousEntry = remote.entries().get(modelId);
            var previous = activation.entries().get(modelId);
            if (entry.sameContent(previousEntry) && previous != null) {
                if (previous instanceof ActivationSnapshot.Ready ready) {
                    states.put(modelId, new ActivationSnapshot.Ready(entry, ready.record()));
                } else if (previous instanceof ActivationSnapshot.Failed failed) {
                    states.put(modelId, new ActivationSnapshot.Failed(entry, failed.failure()));
                } else {
                    states.put(modelId, new ActivationSnapshot.Pending(entry));
                }
            } else {
                states.put(modelId, new ActivationSnapshot.Pending(entry));
            }
        });
        remote = snapshot;
        activation = new ActivationSnapshot(snapshot, states);
        reconcilePreviews(snapshot);
    }

    private void reconcilePreviews(RemotePublicationSnapshot snapshot) {
        var members = new LinkedHashSet<ModelFileIdentity>();
        snapshot.entries().values().forEach(entry -> members.add(entry.identity()));
        previews.keySet().retainAll(members);
        members.forEach(identity -> previews.computeIfAbsent(identity, key -> new PreviewObservation(this, key)));
    }

    public synchronized boolean previewAbsent(ModelFileIdentity identity) {
        var observation = previews.get(identity);
        return state == State.ACTIVE && observation != null && observation.absent;
    }

    /** Captures member incarnation and invalidation eligibility before a request is sent. */
    public synchronized Optional<PreviewObservation> observePreview(ModelFileIdentity identity) {
        return state == State.ACTIVE ? Optional.ofNullable(previews.get(identity)) : Optional.empty();
    }

    public synchronized boolean completePreviewAbsent(PreviewObservation observation) {
        if (!previewObservationCurrent(observation)) {
            return false;
        }
        observation.absent = true;
        return true;
    }

    public synchronized boolean previewObservationCurrent(PreviewObservation observation) {
        return state == State.ACTIVE && observation != null && observation.owner == this
            && previews.get(observation.identity) == observation;
    }

    public synchronized Object imageMember(ModelFileIdentity identity) {
        var observation = state == State.ACTIVE ? previews.get(identity) : null;
        return observation == null ? null : observation.member;
    }

    public synchronized boolean imageMemberCurrent(ModelFileIdentity identity, Object member) {
        var observation = state == State.ACTIVE ? previews.get(identity) : null;
        return member != null && observation != null && observation.member == member;
    }

    public synchronized void invalidatePreview(ModelFileIdentity identity) {
        var previous = previews.get(identity);
        if (state == State.ACTIVE && previous != null) {
            previews.put(identity, new PreviewObservation(this, identity, previous.member));
        }
    }

    public static final class PreviewObservation {
        private final ClientModelSession owner;
        private final ModelFileIdentity identity;
        private final Object member;
        private final Object contentVersion = new Object();
        private boolean absent;

        private PreviewObservation(ClientModelSession owner, ModelFileIdentity identity) {
            this(owner, identity, new Object());
        }

        private PreviewObservation(ClientModelSession owner, ModelFileIdentity identity, Object member) {
            this.owner = owner;
            this.identity = identity;
            this.member = member;
        }

        /** Content keys must not retain this observation's session owner. */
        public Object contentVersion() {
            return contentVersion;
        }
    }

    /**
     * Publishes a terminal activation result for the current publication.
     * A Ready entry establishes content readiness, not a baked target or an installed entity model.
     *
     * @param snapshot the non-null terminal result whose entries belong to the current publication
     * @throws IllegalStateException if the session is not ACTIVE
     * @throws IllegalArgumentException if the result is incomplete or belongs to another publication
     */
    public synchronized void publishActivation(ActivationSnapshot snapshot) {
        requireState(State.ACTIVE);
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.terminal() || !snapshot.publication().entries().equals(remote.entries())) {
            throw new IllegalArgumentException("Activation does not belong to the current publication owner");
        }
        var states = new LinkedHashMap<Hash256, ActivationSnapshot.State>();
        remote.entries().forEach((modelId, entry) -> {
            var entryState = snapshot.entries().get(modelId);
            if (entryState instanceof ActivationSnapshot.Ready ready) {
                states.put(modelId, new ActivationSnapshot.Ready(entry, ready.record()));
            } else if (entryState instanceof ActivationSnapshot.Failed failed) {
                states.put(modelId, new ActivationSnapshot.Failed(entry, failed.failure()));
            } else {
                throw new IllegalArgumentException("Initial activation is not terminal");
            }
        });
        activation = new ActivationSnapshot(remote, states);
    }

    public synchronized Set<Hash256> retryTransient(Hash256 modelId) {
        requireState(State.ACTIVE);
        var states = new LinkedHashMap<>(activation.entries());
        var retries = new LinkedHashSet<Hash256>();
        for (var entry : activation.entries().entrySet()) {
            if (modelId != null && !modelId.equals(entry.getKey())) {
                continue;
            }
            if (entry.getValue() instanceof ActivationSnapshot.Failed failed
                && failed.failure().kind() == ActivationFailure.Kind.TRANSIENT_ACCESS) {
                states.put(entry.getKey(), new ActivationSnapshot.Pending(failed.publication()));
                retries.add(entry.getKey());
            }
        }
        if (!retries.isEmpty()) {
            activation = new ActivationSnapshot(remote, states);
        }
        return Set.copyOf(retries);
    }

    /**
     * Retires remote authority and enters intrinsic-default-only mode unless LOCAL or CLOSED.
     * The adapter separately owns handshake business eligibility, transfer cancellation and playback shutdown.
     */
    public synchronized void failCompleteTransfer() {
        if (state != State.CLOSED && state != State.LOCAL) {
            remote = null;
            activation = null;
            previews.clear();
            offeredTransportVersion = null;
            offeredSyncRoaming = null;
            state = State.INTRINSIC_DEFAULT_ONLY;
        }
    }

    public synchronized CatalogSnapshot catalog() {
        return switch (state) {
            case LOCAL -> localCatalog;
            case ACTIVE -> activation.readyCatalog().withIntrinsicDefaultFrom(intrinsicDefault);
            case NEGOTIATING, INTRINSIC_DEFAULT_ONLY -> defaultOnly();
            case CLOSED -> CatalogSnapshot.empty();
        };
    }

    private CatalogSnapshot defaultOnly() {
        return CatalogSnapshot.empty().withIntrinsicDefaultFrom(intrinsicDefault);
    }

    public synchronized Optional<RemotePublicationSnapshot> remoteSnapshot() {
        return state == State.ACTIVE ? Optional.of(remote) : Optional.empty();
    }

    public synchronized Optional<ActivationSnapshot> activationSnapshot() {
        return state == State.ACTIVE ? Optional.of(activation) : Optional.empty();
    }

    public synchronized void close() {
        previews.clear();
        remote = null;
        activation = null;
        offeredTransportVersion = null;
        offeredSyncRoaming = null;
        state = State.CLOSED;
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + state);
        }
    }

    public enum State {
        LOCAL, NEGOTIATING, ACTIVE, INTRINSIC_DEFAULT_ONLY, CLOSED
    }

    public record Response(boolean accepted) {
    }
}
