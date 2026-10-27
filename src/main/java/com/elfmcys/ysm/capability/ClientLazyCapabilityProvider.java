package com.elfmcys.ysm.capability;

import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ClientLazyCapabilityProvider implements ICapabilityProvider {
    public static Capability<ClientLazyCapability> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    private final ClientLazyCapability instance;

    public ClientLazyCapabilityProvider(VehicleAnimatableCapabilityProvider vehicleAnimatableCapabilityProvider,
        @Nullable ProjectileAnimatableCapabilityProvider projectileAnimatableCapabilityProvider) {
        this.instance =
            new ClientLazyCapability(vehicleAnimatableCapabilityProvider, projectileAnimatableCapabilityProvider);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return CAP.orEmpty(cap, LazyOptional.of(() -> instance));
    }
}
