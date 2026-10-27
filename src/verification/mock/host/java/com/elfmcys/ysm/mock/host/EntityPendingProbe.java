package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.model.service.ClientModelService;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/** Holds real model-worker admission; acquisition results and host adapters remain unchanged. */
final class EntityPendingProbe {
    private final Minecraft minecraft;
    private final HostAction action;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private PlayerAnimatableCapability owner;
    private Field workerField;
    private Object manager;
    private ScheduledExecutorService original;
    private final ArrayList<Runnable> queued = new ArrayList<>();
    private boolean released;
    private CompletableFuture<?> pending;
    private Object pendingMarker;
    private final java.util.concurrent.atomic.AtomicInteger pendingLeaseCloses =
        new java.util.concurrent.atomic.AtomicInteger();
    private int step;
    private int ticks;

    EntityPendingProbe(Minecraft minecraft, HostAction action) {
        this.minecraft = minecraft;
        this.action = action;
    }

    Map<String, ?> poll() throws Exception {
        if (step == 0) {
            if (ClientModelService.instance().resolvePath(action.require("path")).isEmpty())
                return null;
            var player = action.arguments().getOrDefault("subject", "local").equals("local") ? minecraft.player
                : minecraft.level.players().stream().filter(value -> value.getScoreboardName().equals("YsmHostB"))
                    .findFirst().orElseThrow();
            owner =
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElseThrow(IllegalStateException::new);
            owner.setDisabled(false);
            minecraft.setScreen(null);
            minecraft.options.setCameraType(CameraType.valueOf(action.require("camera")));
            EntityPresentationProbe.beginObservation();
            manager = field(ClientModelService.class, "renderTargetManager").get(ClientModelService.instance());
            workerField = field(manager.getClass(), "workers");
            original = (ScheduledExecutorService)workerField.get(manager);
            var gated = Proxy.newProxyInstance(original.getClass().getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("execute")) {
                        synchronized (queued) {
                            if (!released) {
                                queued.add((Runnable)arguments[0]);
                                return null;
                            }
                        }
                    }
                    try {
                        return method.invoke(original, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            workerField.set(manager, gated);
            owner.reset();
            java.nio.file.Files.writeString(java.nio.file.Path.of(action.require("admissionFile")), "held");
            result.put("input",
                Map.of("tick", player.tickCount, "path", action.require("path"), "requested",
                    ClientModelService.instance().resolvePath(action.require("path")).orElseThrow().toString(),
                    "requestedTexture", owner.getRequestedTextureName(), "realQueuedTasks", queued.size()));
            step = 1;
            return null;
        }
        if (step == 1) {
            if (++ticks > 600)
                throw new IllegalStateException("No current Pending acquisition was admitted");
            if (!ClientModelService.instance().resolvePath(action.require("path")).orElseThrow()
                .equals(owner.getModelHash()))
                return null;
            owner.checkModelUpdate();
            var binding = field(AnimatableEntity.class, "modelBinding").get(owner);
            var demand = field(binding.getClass(), "acquisition").get(binding);
            var attempt = field(demand.getClass(), "attempt").get(demand);
            var leaseField = field(demand.getClass(), "lease");
            var lease = (com.elfmcys.ysm.model.resource.client.ResourceLease)leaseField.get(demand);
            var current =
                attempt == null ? null : (CompletableFuture<?>)field(attempt.getClass(), "future").get(attempt);
            var pendingLease =
                lease != null && lease.poll() instanceof com.elfmcys.ysm.model.resource.client.AcquireResult.Pending;
            if (!pendingLease && (current == null || current.isDone()))
                return null;
            var marker = pendingLease ? lease : current;
            if (marker != pendingMarker) {
                pendingMarker = marker;
                pending = current;
                ticks = 0;
                return null;
            }
            if (ticks < 3)
                return null;
            if (pendingLease)
                leaseField.set(demand, new com.elfmcys.ysm.model.resource.client.ResourceLease() {
                    @Override
                    public com.elfmcys.ysm.model.resource.client.ModelResourcePurpose purpose() {
                        return lease.purpose();
                    }

                    @Override
                    public com.elfmcys.ysm.model.resource.client.AcquireResult poll() {
                        return lease.poll();
                    }

                    @Override
                    public boolean isCurrent(com.elfmcys.ysm.model.resource.client.ResourceRequest request) {
                        return lease.isCurrent(request);
                    }

                    @Override
                    public void cancelPending() {
                        lease.cancelPending();
                    }

                    @Override
                    public void close() {
                        pendingLeaseCloses.incrementAndGet();
                        lease.close();
                    }
                });
            result.put("pendingHost", EntityPresentationProbe.observations());
            result.put("beforeRelease",
                Map.of("unfinished", owner.hasUnfinishedWork(), "pendingKind", pendingLease ? "lease" : "future",
                    "futureDone", pending == null || pending.isDone(), "realQueuedTasks", queued.size(), "installed",
                    String.valueOf(owner.getInstalledModelId()), "requestedTexture", owner.getRequestedTextureName()));
            if (Boolean.parseBoolean(action.arguments().getOrDefault("observeOnly", "false"))) {
                releaseWorker();
                step = 3;
                ticks = 0;
                return null;
            }
            if (Boolean.parseBoolean(action.arguments().getOrDefault("retire", "false")))
                owner.retire();
            else
                owner.setDisabled(true);
            owner.playExtraAnimation("trace");
            result.put("released",
                Map.of("futureCancelled", pending != null && pending.isCancelled(), "pendingLeaseCloses",
                    pendingLeaseCloses.get(), "unfinished", owner.hasUnfinishedWork(), "installed",
                    owner.hasInstalledModel(), "playback", owner.isPlayingExtraAnimation(), "assigned",
                    owner.isAssigned()));
            releaseWorker();
            ticks = 0;
            step = 2;
            return null;
        }
        if (step == 2) {
            if (++ticks < 5)
                return null;
            result.put("afterLateWork",
                Map.of("futureCancelled", pending != null && pending.isCancelled(), "pendingLeaseCloses",
                    pendingLeaseCloses.get(), "unfinished", owner.hasUnfinishedWork(), "installed",
                    owner.hasInstalledModel(), "expressionInstalled", owner.getExpressionRuntime().module().isPresent(),
                    "playback", owner.isPlayingExtraAnimation()));
            if (!owner.isAssigned())
                return result;
            owner.setDisabled(false);
            step = 3;
            ticks = 0;
            return null;
        }
        if (++ticks > 600)
            throw new IllegalStateException("Released model did not become Ready");
        if (!action.require("path").equals(owner.getInstalledModelId()))
            return null;
        result.put("restored",
            Map.of("installed", owner.getInstalledModelId(), "requestedTexture", owner.getRequestedTextureName(),
                "playback", owner.isPlayingExtraAnimation(), "relation", owner.getInstallationRelation().name()));
        return result;
    }

    private void releaseWorker() throws IllegalAccessException {
        workerField.set(manager, original);
        synchronized (queued) {
            released = true;
            queued.forEach(original::execute);
            queued.clear();
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
}
