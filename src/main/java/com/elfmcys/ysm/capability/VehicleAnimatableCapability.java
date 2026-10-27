package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class VehicleAnimatableCapability extends CustomVehicleEntity {
    private @Nullable Int2FloatOpenHashMap roamingValues;
    private @Nullable Int2FloatOpenHashMap pendingRoamingValues;

    public VehicleAnimatableCapability(Entity entity) {
        super(entity);
    }

    public void init(Hash256 ownerModelHash) {
        updateModelHash(ownerModelHash);
    }

    public void initRoamingVars(@NotNull Int2FloatOpenHashMap vars) {
        com.elfmcys.ysm.network.NetworkHandler.traceNetwork("vehicle-roaming-full-apply",
            "vehicleId=" + getEntity().getId(), "variables=" + vars.size());
        // 无条件同步服务端数据
        var payload = RoamingPayloads.fromNetwork(vars);
        if (getModelRenderTarget() == null) {
            pendingRoamingValues = new Int2FloatOpenHashMap(vars);
            return;
        }
        getModelRenderTarget().expressions().validateRoamingFull(payload);
        roamingValues = new Int2FloatOpenHashMap(vars);
        pendingRoamingValues = null;
        if (getExpressionRuntime().module().isPresent()) {
            getExpressionRuntime().applyRoamingFull(payload);
        }
    }

    public void updateRoamingVars(@NotNull Int2FloatMap vars) {
        com.elfmcys.ysm.network.NetworkHandler.traceNetwork("vehicle-roaming-delta-apply",
            "vehicleId=" + getEntity().getId(), "variables=" + vars.size());
        var payload = RoamingPayloads.fromNetwork(vars);
        if (getModelRenderTarget() == null) {
            if (pendingRoamingValues == null) {
                pendingRoamingValues =
                    roamingValues == null ? new Int2FloatOpenHashMap() : new Int2FloatOpenHashMap(roamingValues);
            }
            pendingRoamingValues.putAll(vars);
            return;
        }
        getModelRenderTarget().expressions().validateRoamingPatch(payload);
        if (roamingValues == null) {
            roamingValues = new Int2FloatOpenHashMap();
        }
        roamingValues.putAll(vars);
        if (getExpressionRuntime().module().isPresent()) {
            getExpressionRuntime().applyRoamingPatch(payload);
        }
    }

    @Override
    protected Runnable prepareRoleInstallation(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.PreparedInstallation prepared) {
        var values = pendingRoamingValues != null ? pendingRoamingValues
            : roamingValues == null ? new Int2FloatOpenHashMap() : roamingValues;
        prepared.initializeRoaming(RoamingPayloads.fromNetwork(values));
        var accepted = new Int2FloatOpenHashMap(values);
        return () -> {
            roamingValues = accepted;
            pendingRoamingValues = null;
        };
    }
}
