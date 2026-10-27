package com.elfmcys.ysm.capability;

import net.minecraft.core.Direction;
import net.minecraft.nbt.ListTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AuthModelsCapabilityProvider implements ICapabilitySerializable<ListTag> {
    public static Capability<AuthModelsCapability> AUTH_MODELS_CAP = CapabilityManager.get(new CapabilityToken<>() {});
    private AuthModelsCapability instance = null;

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return AUTH_MODELS_CAP.orEmpty(cap, LazyOptional.of(this::createCapability));
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap) {
        return getCapability(cap, null);
    }

    @NotNull
    private AuthModelsCapability createCapability() {
        if (instance == null) {
            this.instance = new AuthModelsCapability();
        }
        return instance;
    }

    @Override
    public void deserializeNBT(ListTag nbt) {
        createCapability().deserializeNBT(nbt);
    }

    @Override
    public ListTag serializeNBT() {
        return createCapability().serializeNBT();
    }
}
