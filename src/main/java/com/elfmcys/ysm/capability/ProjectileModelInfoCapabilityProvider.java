package com.elfmcys.ysm.capability;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ProjectileModelInfoCapabilityProvider implements ICapabilitySerializable<CompoundTag> {
    public static Capability<ProjectileModelInfoCapability> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    private ProjectileModelInfoCapability instance = null;

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return CAP.orEmpty(cap, LazyOptional.of(this::createCapability));
    }

    @NotNull
    private ProjectileModelInfoCapability createCapability() {
        if (instance == null) {
            this.instance = new ProjectileModelInfoCapability();
        }
        return instance;
    }

    @Override
    public CompoundTag serializeNBT() {
        return createCapability().serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        createCapability().deserializeNBT(nbt);
    }
}
