package com.elfmcys.ysm.capability;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class VehicleAnimatableCapabilityProvider implements ICapabilityProvider {
    public static Capability<VehicleAnimatableCapability> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    private VehicleAnimatableCapability instance;
    private Entity entity;

    public VehicleAnimatableCapabilityProvider(Entity entity) {
        this.entity = entity;
    }

    public VehicleAnimatableCapability initialize() {
        if (this.instance == null) {
            this.instance = new VehicleAnimatableCapability(entity);
            this.entity = null;
        }
        return instance;
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return CAP.orEmpty(cap, instance == null ? LazyOptional.empty() : LazyOptional.of(() -> instance));
    }
}
