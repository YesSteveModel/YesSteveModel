package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.ProjectileAnimatableCapability;
import com.elfmcys.ysm.capability.VehicleAnimatableCapability;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.vehicle.Boat;

/** Exercises renderer admission on capabilities attached to real replicated world entities. */
final class ScopedOwnerProbe {
    private final Minecraft minecraft;
    private final HostAction action;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private ProjectileAnimatableCapability projectile;
    private VehicleAnimatableCapability vehicle;
    private int step;
    private int ticks;

    ScopedOwnerProbe(Minecraft minecraft, HostAction action) {
        this.minecraft = minecraft;
        this.action = action;
    }

    Map<String, ?> poll() throws Exception {
        if (++ticks > 600)
            throw new IllegalStateException("Scoped world owners did not complete");
        var service = ClientModelService.instance();
        if (step == 0) {
            var entities = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
            minecraft.level.entitiesForRendering().forEach(entities::add);
            entities.sort(java.util.Comparator.comparingInt(net.minecraft.world.entity.Entity::getId));
            for (var entity : entities) {
                if (entity instanceof Arrow)
                    projectile = entity.getCapability(com.elfmcys.ysm.capability.ClientLazyCapabilityProvider.CAP)
                        .map(value -> value.getProjectileAnimatableCapabilityProvider().initialize()).orElse(null);
                if (entity instanceof Boat)
                    vehicle = entity.getCapability(com.elfmcys.ysm.capability.ClientLazyCapabilityProvider.CAP)
                        .map(value -> value.getVehicleAnimatableCapabilityProvider().initialize()).orElse(null);
            }
            if (projectile == null || vehicle == null)
                return null;
            var hash = service.resolvePath("wine_fox/16_tactics").orElseThrow();
            projectile.init(hash);
            vehicle.init(hash);
            result.put("worldInputs", Map.of("projectile", projectile.getEntity().getType().toString(), "vehicle",
                vehicle.getEntity().getType().toString(), "model", hash.toString()));
            step = 1;
        }
        projectile.checkModelUpdate();
        vehicle.checkModelUpdate();
        if (step == 1) {
            if (!projectile.hasInstalledModel() || !vehicle.hasInstalledModel())
                return null;
            result.put("matching", draw());
            if (action.arguments().getOrDefault("mode", "ordinary").equals("failure")) {
                java.nio.file.Files.writeString(java.nio.file.Path.of(action.require("admissionFile")), "matching");
                step = 3;
                ticks = 0;
                return null;
            }
            var noTarget = service.resolvePath("entity/parts-a").orElseThrow();
            projectile.init(noTarget);
            vehicle.init(noTarget);
            step = 2;
            ticks = 0;
            return null;
        }
        if (step == 3) {
            var state = com.elfmcys.ysm.network.forge.ClientSessionRuntime.state();
            if (state.isEmpty() || !state.get().name().equals("INTRINSIC_DEFAULT_ONLY"))
                return null;
            result.put("failedSession", Map.of("state", state.get().name(), "minecraftConnected",
                minecraft.getConnection() != null, "owners", draw()));
            return result;
        }
        if (ticks < 3)
            return null;
        result.put("noTarget", draw());
        projectile.retire();
        vehicle.retire();
        result.put("retired", draw());
        return result;
    }

    private Map<String, Object> draw() {
        var buffer = minecraft.renderBuffers().bufferSource();
        boolean projectileConsumed = RegisterEntityRenderersEvent.getProjectRenderer().renderReplacement(projectile, 0,
            minecraft.getFrameTime(), new PoseStack(), buffer, 15728880);
        boolean vehicleConsumed = RegisterEntityRenderersEvent.getVehicleRenderer().renderReplacement(vehicle, 0,
            minecraft.getFrameTime(), new PoseStack(), buffer, 15728880);
        buffer.endBatch();
        return Map.of("projectile", facts(projectile, projectileConsumed), "vehicle", facts(vehicle, vehicleConsumed));
    }

    private Map<String, Object> facts(com.elfmcys.ysm.client.entity.AnimatableEntity<?> owner, boolean consumed) {
        return Map.of("installed", String.valueOf(owner.getInstalledModelId()), "relation",
            owner.getInstallationRelation().name(), "consumed", consumed, "requested",
            String.valueOf(owner.getModelHash()), "assigned", owner.isAssigned());
    }
}
