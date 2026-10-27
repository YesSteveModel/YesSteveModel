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

    private int currentHash;
    private RenderTargetExpressionModule currentModule;

    ClientRoamingSession(Player player, boolean localPlayer, Runnable reloadModel) {
        this.player = player;
        this.localPlayer = localPlayer;
        this.reloadModel = reloadModel;
    }

    Runnable prepare(int roamingHash, RenderTargetExpressionModule module,
        EntityExpressionRuntime.PreparedInstallation prepared) {
        var current = storage.get(roamingHash);
        var values = current == null ? new Int2FloatOpenHashMap() : current.candidate(module);
        var payload = RoamingPayloads.fromNetwork(values);
        module.validateRoamingFull(payload);
        prepared.initializeRoaming(payload);
        return () -> {
            if (current != null) {
                current.variables = values;
                current.pendingFull = null;
                current.pendingChanges.clear();
            }
            currentHash = roamingHash;
            currentModule = module;
        };
    }

    void modelReset() {
        currentHash = 0;
        currentModule = null;
    }

    /** The roaming namespace of the currently applied model, or null before one is loaded. */
    Integer currentRoamingKey() {
        return currentModule == null ? null : currentHash;
    }

    void resetFromServer(EntityExpressionRuntime runtime, int roamingHash, Int2FloatOpenHashMap variables) {
        var payload = RoamingPayloads.fromNetwork(variables);
        var remote = storage.get(roamingHash);
        if (localPlayer && remote != null && remote.variables != null) {
            return;
        }
        if (roamingHash != currentHash) {
            storage.computeIfAbsent(roamingHash, ignored -> new RemoteStorage()).stageFull(variables);
            return;
        }
        requireCurrentModule().validateRoamingFull(payload);
        remote = storage.computeIfAbsent(roamingHash, ignored -> new RemoteStorage());
        remote.replaceFull(variables);
        if (localPlayer) {
            reloadModel.run();
        } else if (runtime.module().isPresent()) {
            runtime.applyRoamingFull(payload);
        }
    }

    void clearFromServer(EntityExpressionRuntime runtime) {
        var values = new Int2FloatOpenHashMap();
        var payload = RoamingPayloads.fromNetwork(values);
        if (currentModule != null) {
            currentModule.validateRoamingFull(payload);
        }
        storage.clear();
        if (currentModule == null) {
            return;
        }
        if (localPlayer) {
            reloadModel.run();
        } else if (runtime.module().isPresent()) {
            runtime.applyRoamingFull(payload);
        }
    }

    boolean hasStorage(int roamingHash) {
        return storage.containsKey(roamingHash);
    }

    void updateRemote(EntityExpressionRuntime runtime, int roamingHash, Int2FloatMap variables) {
        // The server also sends replay compatibility updates to LocalPlayer; discard them in normal play.
        if (localPlayer || variables.isEmpty()) {
            return;
        }
        var payload = RoamingPayloads.fromNetwork(variables);
        if (roamingHash != currentHash) {
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
        if (!localPlayer || currentHash == 0) {
            return;
        }
        var changes = RoamingPayloads.toNetwork(delta);
        var remote = storage.computeIfAbsent(currentHash, ignored -> new RemoteStorage());
        if (remote.variables == null) {
            remote.variables = new Int2FloatOpenHashMap();
        }
        remote.variables.putAll(changes);
        updateVehicle(currentHash, changes);

    }

    PlayerAnimatableCapability.RoamingSnapshot snapshot(EntityExpressionRuntime runtime, int authoritativeHash) {
        if (!localPlayer) {
            return runtime
                .sampleInstallation(installation -> new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash,
                    storedValues(authoritativeHash), installation))
                .orElseGet(() -> new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash,
                    storedValues(authoritativeHash), null));
        }
        var sampled = runtime.sample(snapshot -> {
            if (localPlayer && authoritativeHash == currentHash) {
                var values = RoamingPayloads.toNetwork(snapshot.payload());
                var remote = storage.computeIfAbsent(authoritativeHash, ignored -> new RemoteStorage());
                remote.variables = new Int2FloatOpenHashMap(values);
                return new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash, values,
                    snapshot.installation());
            }
            return new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash, storedValues(authoritativeHash),
                snapshot.installation());
        });
        return sampled.orElseGet(() -> new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash,
            storedValues(authoritativeHash), null));
    }

    private Int2FloatOpenHashMap storedValues(int authoritativeHash) {
        var remote = storage.get(authoritativeHash);
        return remote == null || remote.variables == null ? new Int2FloatOpenHashMap()
            : new Int2FloatOpenHashMap(remote.variables);
    }

    void moveFrom(ClientRoamingSession source) {
        storage.putAll(source.storage);
        source.storage.clear();
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
