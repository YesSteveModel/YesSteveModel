package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.client.entity.CustomProjectileEntity;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class ProjectileAnimatableCapability extends CustomProjectileEntity {
    private @Nullable Int2FloatOpenHashMap roamingValues;

    public ProjectileAnimatableCapability(Projectile projectile) {
        super(projectile);
    }

    public void init(Hash256 ownerModelHash) {
        updateModelHash(ownerModelHash);
    }

    public void initRoamingVars(Int2FloatOpenHashMap vars) {
        if (vars != null) {
            // 无条件同步服务端数据
            roamingValues = new Int2FloatOpenHashMap(vars);
        } else {
            roamingValues = null;
        }
        if (getExpressionRuntime().module().isPresent()) {
            getExpressionRuntime().applyRoamingFull(
                RoamingPayloads.fromNetwork(roamingValues == null ? new Int2FloatOpenHashMap() : roamingValues));
        }
    }

    @Override
    protected @Nullable Runnable prepareRoleInstallation(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.PreparedInstallation prepared) {
        prepared.initializeRoaming(
            RoamingPayloads.fromNetwork(roamingValues == null ? new Int2FloatOpenHashMap() : roamingValues));
        return null;
    }
}
