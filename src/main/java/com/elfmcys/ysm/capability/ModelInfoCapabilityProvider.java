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

public class ModelInfoCapabilityProvider implements ICapabilitySerializable<CompoundTag> {
    public static Capability<ModelInfoCapability> MODEL_INFO_CAP = CapabilityManager.get(new CapabilityToken<>() {});
    private ModelInfoCapability instance = null;

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return MODEL_INFO_CAP.orEmpty(cap, LazyOptional.of(this::createCapability));
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap) {
        return getCapability(cap, null);
    }

    @NotNull
    private ModelInfoCapability createCapability() {
        if (instance == null) {
            this.instance = new ModelInfoCapability();
        }
        return instance;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        createCapability().deserializeNBT(nbt);
    }

    @Override
    public CompoundTag serializeNBT() {
        return createCapability().serializeNBT();
    }
}
