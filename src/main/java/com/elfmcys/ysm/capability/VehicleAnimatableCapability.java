package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import dev.semaweave.RoamingPayload;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.util.List;
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

    @Override
    protected boolean worldSubjectSamplePublisher(Entity subject) {
        return !(subject instanceof net.minecraft.world.entity.player.Player
            || subject instanceof net.minecraft.world.entity.projectile.Projectile);
    }

    public void init(ModelId ownerModelHash) {
        updateModelHash(ownerModelHash);
    }

    public void initRoamingVars(@NotNull Int2FloatOpenHashMap vars) {
        waitForAsyncUpdate();
        if (com.elfmcys.ysm.network.NetworkDiagnostics.isEnabled())
            com.elfmcys.ysm.network.NetworkDiagnostics.trace("vehicle-roaming-full-apply",
                "vehicleId=" + getEntity().getId(), "variables=" + vars.size());
        // 无条件同步服务端数据
        RoamingPayloads.validateNetwork(vars);
        if (getModelRenderTarget() == null) {
            pendingRoamingValues = new Int2FloatOpenHashMap(vars);
            return;
        }
        var payload =
            RoamingPayloads.fromNetwork(vars, getExpressionRuntime().metadata().orElseThrow().roamingFields());
        roamingValues = new Int2FloatOpenHashMap(vars);
        pendingRoamingValues = null;
        if (getExpressionRuntime().metadata().isPresent()) {
            getExpressionRuntime().applyRoamingFull(payload);
        }
    }

    public void updateRoamingVars(@NotNull Int2FloatMap vars) {
        RoamingPayloads.validateNetwork(vars);
        applyRoamingChanges(vars);
    }

    /** Borrows a finite module export and its exact source layout; wire validation belongs to the map ingress. */
    public void adoptRoamingChanges(RoamingPayload changes, List<String> sourceFields) {
        applyRoamingChanges(RoamingPayloads.toNetwork(changes, sourceFields));
    }

    private void applyRoamingChanges(Int2FloatMap vars) {
        waitForAsyncUpdate();
        if (com.elfmcys.ysm.network.NetworkDiagnostics.isEnabled())
            com.elfmcys.ysm.network.NetworkDiagnostics.trace("vehicle-roaming-delta-apply",
                "vehicleId=" + getEntity().getId(), "variables=" + vars.size());
        if (getModelRenderTarget() == null) {
            if (pendingRoamingValues == null) {
                pendingRoamingValues =
                    roamingValues == null ? new Int2FloatOpenHashMap() : new Int2FloatOpenHashMap(roamingValues);
            }
            pendingRoamingValues.putAll(vars);
            return;
        }
        var payload =
            RoamingPayloads.fromNetwork(vars, getExpressionRuntime().metadata().orElseThrow().roamingFields());
        if (roamingValues == null) {
            roamingValues = new Int2FloatOpenHashMap();
        }
        roamingValues.putAll(vars);
        if (getExpressionRuntime().metadata().isPresent()) {
            getExpressionRuntime().applyRoamingPatch(payload);
        }
    }

    @Override
    protected Runnable prepareRoleInstallation(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.PreparedInstallation prepared) {
        var values = pendingRoamingValues != null ? pendingRoamingValues
            : roamingValues == null ? new Int2FloatOpenHashMap() : roamingValues;
        prepared
            .initializeRoaming(RoamingPayloads.fromNetwork(values, animationStore(target).metadata().roamingFields()));
        var accepted = new Int2FloatOpenHashMap(values);
        return () -> {
            roamingValues = accepted;
            pendingRoamingValues = null;
        };
    }
}
