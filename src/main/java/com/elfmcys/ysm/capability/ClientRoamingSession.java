package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;

/** Owns client roaming-variable synchronization independently from animation/render compatibility. */
final class ClientRoamingSession {
    private final Player player;
    private final boolean localPlayer;
    private final Runnable reloadModel;
    private final Int2ReferenceOpenHashMap<RemoteStorage> storage = new Int2ReferenceOpenHashMap<>(8);
    private final Int2ReferenceOpenHashMap<Int2FloatOpenHashMap> retained = new Int2ReferenceOpenHashMap<>(8);

    private int currentHash;
    private RenderTargetExpressionModule currentModule;
    private EntityExpressionRuntime.Installation currentInstallation;
    private boolean restored;

    ClientRoamingSession(Player player, boolean localPlayer, Runnable reloadModel) {
        this.player = player;
        this.localPlayer = localPlayer;
        this.reloadModel = reloadModel;
    }

    Runnable prepare(int roamingHash, RenderTargetExpressionModule module,
        EntityExpressionRuntime.PreparedInstallation prepared) {
        return prepare(roamingHash, module, prepared, false);
    }

    Runnable prepare(int roamingHash, RenderTargetExpressionModule module,
        EntityExpressionRuntime.PreparedInstallation prepared, boolean localOnly) {
        var current = storage.get(roamingHash);
        var baseline = retained.get(roamingHash);
        var known = baseline != null || localOnly;
        var values = localPlayer
            ? baseline == null ? new Int2FloatOpenHashMap() : new Int2FloatOpenHashMap(baseline)
            : current == null ? new Int2FloatOpenHashMap() : current.candidate(module);
        var payload = RoamingPayloads.fromNetwork(values);
        module.validateRoamingFull(payload);
        prepared.initializeRoaming(payload);
        var installation = prepared.installation();
        return () -> {
            if (localPlayer && known) {
                retained.put(roamingHash, values);
            } else if (current != null) {
                current.variables = values;
                current.pendingFull = null;
                current.pendingChanges.clear();
            }
            currentHash = roamingHash;
            currentModule = module;
            currentInstallation = installation;
            restored = known;
        };
    }

    void modelReset() {
        currentHash = 0;
        currentModule = null;
        currentInstallation = null;
        restored = false;
    }

    /** The roaming namespace of the currently applied model, or null before one is loaded. */
    Integer currentRoamingKey() {
        return currentModule == null ? null : currentHash;
    }

    void resetFromServer(EntityExpressionRuntime runtime, int roamingHash, Int2FloatOpenHashMap variables) {
        var payload = RoamingPayloads.fromNetwork(variables);
        if (localPlayer) {
            if (retained.containsKey(roamingHash)) {
                return;
            }
            retained.put(roamingHash, new Int2FloatOpenHashMap(variables));
            if (currentHash == roamingHash) {
                reloadCurrent(runtime);
            }
            return;
        }
        var remote = storage.get(roamingHash);
        if (currentModule == null || roamingHash != currentHash) {
            storage.computeIfAbsent(roamingHash, ignored -> new RemoteStorage()).stageFull(variables);
            return;
        }
        requireCurrentModule().validateRoamingFull(payload);
        remote = storage.computeIfAbsent(roamingHash, ignored -> new RemoteStorage());
        remote.replaceFull(variables);
        if (runtime.module().isPresent()) {
            runtime.applyRoamingFull(payload);
        }
    }

    void clearFromServer(EntityExpressionRuntime runtime) {
        if (localPlayer) {
            retained.clear();
            if (currentModule != null) {
                clearFromServer(runtime, currentHash);
            }
            return;
        }
        var values = new Int2FloatOpenHashMap();
        var payload = RoamingPayloads.fromNetwork(values);
        if (currentModule != null) {
            currentModule.validateRoamingFull(payload);
        }
        storage.clear();
        if (currentModule == null) {
            return;
        }
        if (runtime.module().isPresent()) {
            runtime.applyRoamingFull(payload);
        }
    }

    void clearFromServer(EntityExpressionRuntime runtime, int roamingHash) {
        if (!localPlayer) {
            clearFromServer(runtime);
            return;
        }
        retained.clear();
        retained.put(roamingHash, new Int2FloatOpenHashMap());
        reloadCurrent(runtime);
    }

    private void reloadCurrent(EntityExpressionRuntime runtime) {
        if (currentModule == null) {
            return;
        }
        restored = false;
        runtime.invalidate();
        reloadModel.run();
    }

    boolean hasStorage(int roamingHash) {
        return localPlayer ? retained.containsKey(roamingHash) : storage.containsKey(roamingHash);
    }

    void updateRemote(EntityExpressionRuntime runtime, int roamingHash, Int2FloatMap variables) {
        // The server also sends replay compatibility updates to LocalPlayer; discard them in normal play.
        if (localPlayer || variables.isEmpty()) {
            return;
        }
        var payload = RoamingPayloads.fromNetwork(variables);
        if (currentModule == null || roamingHash != currentHash) {
            storage.computeIfAbsent(roamingHash, ignored -> new RemoteStorage()).stagePatch(variables);
            return;
        }
        requireCurrentModule().validateRoamingPatch(payload);
        var remote = storage.computeIfAbsent(roamingHash, ignored -> new RemoteStorage());
        if (remote.variables == null) {
            remote.stagePatch(variables);
        } else {
            remote.variables.putAll(variables);
        }
        if (roamingHash == currentHash && runtime.module().isPresent()) {
            runtime.applyRoamingPatch(payload);
        }
        updateVehicle(roamingHash, variables);
    }

    void maintainLocalChanges(dev.semaweave.RoamingPayload delta) {
        if (!localPlayer || !restored) {
            return;
        }
        var changes = RoamingPayloads.toNetwork(delta);
        retained.get(currentHash).putAll(changes);
    }

    EntityExpressionRuntime.ProjectionSink prepareLocalProjection() {
        var module = currentModule;
        var source = currentInstallation;
        var vehicle = player.getVehicle();
        var target = vehicle == null ? null
            : vehicle.getCapability(VehicleAnimatableCapabilityProvider.CAP).orElse(null);
        var installation = target == null ? null : target.projectionIdentity();
        return new EntityExpressionRuntime.ProjectionSink() {
            private boolean localApplied;
            public boolean adopt(dev.semaweave.RoamingPayload delta, boolean finalWindow) {
                if (!localPlayer || !restored || source == null || currentInstallation != source)
                    return true;
                if (!localApplied) {
                    if (!source.commit(() -> {
                        module.validateRoamingPatch(delta);
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
                target.updateRoamingVars(RoamingPayloads.toNetwork(delta));
                return true;
            }
        };
    }

    PlayerAnimatableCapability.RoamingSnapshot snapshot(EntityExpressionRuntime runtime, int authoritativeHash) {
        if (!localPlayer) {
            return runtime
                .sampleInstallation(installation -> new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash,
                    storedValues(authoritativeHash), installation))
                .orElseGet(() -> new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash,
                    storedValues(authoritativeHash), null));
        }
        var baseline = retained.get(authoritativeHash);
        if (baseline == null) {
            return PlayerAnimatableCapability.RoamingSnapshot.unavailable(authoritativeHash);
        }
        if (authoritativeHash == currentHash && restored && currentInstallation != null) {
            var installation = currentInstallation;
            return runtime.sample(snapshot -> new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash,
                RoamingPayloads.toNetwork(snapshot.payload()), installation))
                .orElseGet(() -> PlayerAnimatableCapability.RoamingSnapshot.unavailable(authoritativeHash));
        }
        return new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash, storedValues(authoritativeHash), null);
    }

    private Int2FloatOpenHashMap storedValues(int authoritativeHash) {
        if (localPlayer) {
            var values = retained.get(authoritativeHash);
            return values == null ? new Int2FloatOpenHashMap() : new Int2FloatOpenHashMap(values);
        }
        var remote = storage.get(authoritativeHash);
        return remote == null || remote.variables == null ? new Int2FloatOpenHashMap()
            : new Int2FloatOpenHashMap(remote.variables);
    }

    void moveFrom(ClientRoamingSession source) {
        storage.putAll(source.storage);
        source.storage.clear();
        retained.putAll(source.retained);
        source.retained.clear();
        source.modelReset();
    }

    private void updateVehicle(int roamingHash, Int2FloatMap variables) {
        if (roamingHash != currentHash || player.getVehicle() == null
            || player.getVehicle().getFirstPassenger() != player) {
            return;
        }
        player.getVehicle().getCapability(VehicleAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
            NetworkHandler.traceNetwork("vehicle-roaming-local-apply", "playerId=" + player.getId(),
                "vehicleId=" + player.getVehicle().getId(), "modelKey=" + roamingHash, "variables=" + variables.size());
            capability.updateRoamingVars(variables);
        });
    }

    private RenderTargetExpressionModule requireCurrentModule() {
        if (currentModule == null) {
            throw new IllegalStateException("current roaming layout is unavailable");
        }
        return currentModule;
    }

    private static final class RemoteStorage {
        private Int2FloatOpenHashMap variables;
        private Int2FloatOpenHashMap pendingFull;
        private final ArrayDeque<Int2FloatMap> pendingChanges = new ArrayDeque<>(4);

        private void stageFull(Int2FloatMap values) {
            pendingFull = new Int2FloatOpenHashMap(values);
            pendingChanges.clear();
        }

        private void stagePatch(Int2FloatMap values) {
            if (pendingFull != null) {
                pendingFull.putAll(values);
            } else {
                pendingChanges.addLast(new Int2FloatOpenHashMap(values));
            }
        }

        private Int2FloatOpenHashMap candidate(RenderTargetExpressionModule module) {
            var candidate = pendingFull != null ? new Int2FloatOpenHashMap(pendingFull)
                : variables == null ? new Int2FloatOpenHashMap() : new Int2FloatOpenHashMap(variables);
            for (var pending : pendingChanges) {
                candidate.putAll(pending);
            }
            module.validateRoamingFull(RoamingPayloads.fromNetwork(candidate));
            return candidate;
        }

        private void replaceFull(Int2FloatMap values) {
            variables = new Int2FloatOpenHashMap(values);
            pendingFull = null;
            pendingChanges.clear();
        }
    }
}
