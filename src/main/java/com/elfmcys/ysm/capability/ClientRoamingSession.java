package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.model.session.client.PlayerPreferenceSession;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import com.elfmcys.ysm.network.protocol.RoamingDictionary;
import com.elfmcys.ysm.network.protocol.RoamingValues;
import dev.semaweave.RoamingPayload;
import net.minecraft.world.entity.player.Player;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;

/** Owns the current selection projection, unconsumed inputs, and their qualified installation. */
final class ClientRoamingSession {
    private static final RoamingPayload EMPTY = new RoamingPayload(new short[0], new float[0]);
    private final Player player;
    private final boolean localPlayer;
    private final Runnable reloadModel;
    private final Supplier<Object> selectionOperation;
    private ModelId currentHash;
    private PublishedModelId currentIdentity;
    private java.util.List<String> currentFields;
    private EntityExpressionRuntime.Installation currentInstallation;
    private Object currentOperation;
    private PlayerPreferenceSession.Scope currentScope;
    private PlayerPreferenceSession preferenceSession;
    private boolean restored;
    private CommittedSelection remote;
    private Object remoteOperation;
    private RoamingDictionary.Mapping mapping;
    private RemoteSyncBudget remoteBudget;
    private PendingRemoteReplay pending;
    private boolean draining;

    ClientRoamingSession(Player player, boolean localPlayer, Runnable reloadModel) {
        this(player, localPlayer, reloadModel, () -> null);
    }

    ClientRoamingSession(Player player, boolean localPlayer, Runnable reloadModel,
        Supplier<Object> selectionOperation) {
        this.player = player;
        this.localPlayer = localPlayer;
        this.reloadModel = reloadModel;
        this.selectionOperation = selectionOperation;
    }

    void preferences(PlayerPreferenceSession session) {
        preferenceSession = session;
    }

    private PlayerPreferenceSession preferences() {
        return preferenceSession != null ? preferenceSession : com.elfmcys.ysm.model.service.ClientModelService
            .current().map(service -> service.preferences().roaming()).orElse(null);
    }

    Runnable prepare(ModelId key, EntityExpressionRuntime.PreparedInstallation prepared) {
        return prepare(key, prepared, PublishedModelId.model(key));
    }

    Runnable prepare(ModelId key, EntityExpressionRuntime.PreparedInstallation prepared, PublishedModelId identity) {
        var fields = prepared.installation().roamingFields();
        var operation = selectionOperation.get();
        var preferences = localPlayer ? preferences() : null;
        var scope = preferences == null ? null : preferences.scope();
        boolean eligible = scope != null && (operation == null || operation == scope)
            && scope.identity().equals(identity) && (player == null || scope.player().equals(player.getUUID()));
        var baseline = eligible ? preferences.snapshot(scope) : null;
        if (eligible && baseline == null)
            preferences.restore(scope, java.util.Set.copyOf(fields), reloadModel);
        var preparedMapping =
            !localPlayer && remote != null && remoteOperation == operation && remote.model().equals(identity)
                ? remote.dictionary().mapTo(fields) : null;
        if (localPlayer) {
            var payload = baseline == null ? EMPTY : baseline.toPayload(fields);
            prepared.initializeRoaming(payload);
        } else {
            var budget = remoteBudget;
            prepared.initializeRoamingAtCommit(() -> {
                if (selectionOperation.get() != operation || budget != null
                    && (remote == null || remoteOperation != operation || remoteBudget != budget || !budget.isActive()))
                    throw new CancellationException("Remote selection changed before installation");
                var payload = preparedMapping == null ? EMPTY : preparedMapping
                    .decode(pending != null && !pending.started() ? pending.baseline() : remote.roaming().snapshot());
                return payload;
            });
        }
        var installation = prepared.installation();
        return () -> {
            if (!localPlayer && pending != null && pending.started())
                clearPending();
            currentHash = key;
            currentIdentity = identity;
            currentFields = fields;
            currentInstallation = installation;
            currentOperation = operation;
            currentScope = eligible ? scope : null;
            restored = baseline != null;
            mapping = preparedMapping;
        };
    }

    void modelReset() {
        if (pending != null && pending.started())
            clearPending();
        currentHash = null;
        currentIdentity = null;
        currentFields = null;
        currentInstallation = null;
        currentOperation = null;
        currentScope = null;
        restored = false;
        mapping = null;
    }

    ModelId currentRoamingKey() {
        return currentFields == null ? null : currentHash;
    }

    CommittedSelection remoteSelection() {
        return remote;
    }

    Object remoteOperation() {
        return remoteOperation;
    }

    void acceptSelection(EntityExpressionRuntime runtime, CommittedSelection next, Object operation,
        RemoteSyncBudget budget) {
        if (localPlayer)
            return;
        boolean same = remote != null && remoteOperation == operation && remoteBudget == budget
            && remote.intentId() == next.intentId() && remote.model().equals(next.model())
            && java.util.Arrays.equals(remote.dictionary().names(), next.dictionary().names());
        if (!same) {
            clearPending();
            mapping = null;
        }
        remote = next;
        remoteOperation = operation;
        remoteBudget = budget;
        if (mapping == null && currentFields != null && currentOperation == operation
            && next.model().equals(currentIdentity))
            mapping = next.dictionary().mapTo(currentFields);
        var full = next.roaming().snapshot();
        if (pending != null) {
            pending.state(full, true);
        } else if (qualified() && currentInstallation.commit(() -> runtime.applyRoamingFull(mapping.decode(full)))) {
            return;
        } else {
            pending = new PendingRemoteReplay(next.dictionary(), full, budget);
        }
    }

    void updateRemote(EntityExpressionRuntime runtime, RoamingValues delta) {
        if (localPlayer || remote == null)
            return;
        remote.roaming().apply(delta, false);
        if (pending != null) {
            pending.state(delta, false);
        } else if (qualified()) {
            var payload = mapping.decode(delta);
            if (currentInstallation.commit(() -> runtime.applyRoamingPatch(payload))) {
                updateVehicle(payload);
                return;
            }
            pending = new PendingRemoteReplay(remote.dictionary(), remote.roaming().snapshot(), remoteBudget);
        } else {
            pending = new PendingRemoteReplay(remote.dictionary(), remote.roaming().snapshot(), remoteBudget);
        }
    }

    void acceptSync(EntityExpressionRuntime runtime, EventArguments arguments, Consumer<EventArguments> dispatch) {
        if (localPlayer || remote == null || !remoteBudget.isActive())
            return;
        if (pending == null) {
            pending = new PendingRemoteReplay(remote.dictionary(), remote.roaming().snapshot(), remoteBudget);
            if (qualified() && currentInstallation.commit(() -> {
            }))
                pending.start(currentInstallation);
        }
        pending.sync(arguments);
        drain(runtime, dispatch);
    }

    /** Each callback returns after its script borrow and queued actions have completed. */
    void drain(EntityExpressionRuntime runtime, Consumer<EventArguments> dispatch) {
        if (draining || pending == null || !qualified())
            return;
        var replay = pending;
        var installation = currentInstallation;
        if (!replay.active() || replay.started() && !replay.belongsTo(installation)) {
            clearPending();
            return;
        }
        draining = true;
        try {
            if (!replay.started()) {
                var baseline = mapping.decode(replay.baseline());
                if (!installation.commit(() -> runtime.applyRoamingFull(baseline)))
                    return;
                replay.start(installation);
            }
            while (pending == replay && qualified()) {
                if (!installation.commit(() -> {
                })) {
                    clearPending();
                    return;
                }
                var input = replay.poll();
                if (input == null) {
                    clearPending();
                    return;
                }
                if (input instanceof PendingRemoteReplay.State state) {
                    var payload = mapping.decode(state.values());
                    installation.commit(() -> {
                        if (state.full())
                            runtime.applyRoamingFull(payload);
                        else
                            runtime.applyRoamingPatch(payload);
                    });
                    updateVehicle(payload);
                } else if (input instanceof PendingRemoteReplay.Sync sync) {
                    try {
                        dispatch.accept(sync.arguments());
                    } catch (RuntimeException | LinkageError failure) {
                        com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Remote sync execution failed", failure);
                    }
                }
            }
            if (pending == replay)
                clearPending();
        } finally {
            draining = false;
        }
    }

    private boolean qualified() {
        return remote != null && remoteBudget.isActive() && mapping != null && currentFields != null
            && currentOperation == remoteOperation && selectionOperation.get() == remoteOperation
            && currentInstallation != null && remote.model().equals(currentIdentity);
    }

    void clearRemote() {
        clearPending();
        remote = null;
        remoteOperation = null;
        remoteBudget = null;
        mapping = null;
    }

    private void clearPending() {
        if (pending != null) {
            pending.close();
            pending = null;
        }
    }

    void maintainLocalChanges(RoamingPayload delta) {
        var preferences = preferences();
        if (localPlayer && restored && preferences != null && preferences.scope() == currentScope
            && (currentOperation == null || currentOperation == currentScope))
            preferences.adopt(currentScope,
                NamedRoamingSnapshot.fromPayload(currentInstallation.roamingFields(), delta));
    }

    EntityExpressionRuntime.ProjectionSink prepareLocalProjection() {
        var fields = currentFields;
        var source = currentInstallation;
        var scope = currentScope;
        var operation = currentOperation;
        var vehicle = player == null ? null : player.getVehicle();
        var target =
            vehicle == null ? null : vehicle.getCapability(VehicleAnimatableCapabilityProvider.CAP).orElse(null);
        var installation = target == null ? null : target.projectionIdentity();
        return new EntityExpressionRuntime.ProjectionSink() {
            private boolean localApplied;

            public boolean adopt(RoamingPayload delta, boolean finalWindow) {
                if (!localPlayer || !restored || source == null || currentInstallation != source
                    || currentOperation != operation || preferences() == null || preferences().scope() != scope
                    || operation != null && operation != scope)
                    return true;
                if (!localApplied) {
                    if (!source.commit(() -> {
                        maintainLocalChanges(delta);
                    }))
                        return true;
                    localApplied = true;
                }
                if (target == null || player.getVehicle() != vehicle || vehicle.getFirstPassenger() != player
                    || target.projectionIdentity() != installation)
                    return true;
                if (!target.canAdoptProjection(installation))
                    return finalWindow;
                target.adoptRoamingChanges(delta, fields);
                return true;
            }
        };
    }

    PlayerAnimatableCapability.RoamingSnapshot snapshot(EntityExpressionRuntime runtime, PublishedModelId identity) {
        var preferences = localPlayer ? preferences() : null;
        var scope = preferences == null ? null : preferences.scope();
        if (!localPlayer || scope == null || !scope.identity().equals(identity) || !restored || currentScope != scope
            || currentOperation != null && currentOperation != scope || currentInstallation == null)
            return PlayerAnimatableCapability.RoamingSnapshot.unavailable(identity);
        var installation = currentInstallation;
        var operation = currentOperation;
        return runtime
            .sample(snapshot -> new PlayerAnimatableCapability.RoamingSnapshot(identity, snapshot.payload(),
                installation, operation, true))
            .orElseGet(() -> PlayerAnimatableCapability.RoamingSnapshot.unavailable(identity));
    }

    void moveFrom(ClientRoamingSession source) {
        clearRemote();
        remote = source.remote;
        remoteOperation = source.remoteOperation;
        remoteBudget = source.remoteBudget;
        if (source.pending != null && source.pending.started())
            source.clearPending();
        pending = source.pending;
        source.pending = null;
        source.remote = null;
        source.remoteOperation = null;
        source.remoteBudget = null;
        source.modelReset();
    }

    private void updateVehicle(RoamingPayload delta) {
        if (player == null || player.getVehicle() == null || player.getVehicle().getFirstPassenger() != player)
            return;
        player.getVehicle().getCapability(VehicleAnimatableCapabilityProvider.CAP)
            .ifPresent(capability -> capability.adoptRoamingChanges(delta, currentFields));
    }
}
