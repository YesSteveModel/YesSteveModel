package com.elfmcys.ysm.capability;

import org.jetbrains.annotations.Nullable;

public class ClientLazyCapability {
    private final VehicleAnimatableCapabilityProvider vehicleAnimatableCapabilityProvider;
    @Nullable
    private final ProjectileAnimatableCapabilityProvider projectileAnimatableCapabilityProvider;

    public ClientLazyCapability(VehicleAnimatableCapabilityProvider vehicleAnimatableCapabilityProvider,
        @Nullable ProjectileAnimatableCapabilityProvider projectileAnimatableCapabilityProvider) {
        this.vehicleAnimatableCapabilityProvider = vehicleAnimatableCapabilityProvider;
        this.projectileAnimatableCapabilityProvider = projectileAnimatableCapabilityProvider;
    }

    public VehicleAnimatableCapabilityProvider getVehicleAnimatableCapabilityProvider() {
        return vehicleAnimatableCapabilityProvider;
    }

    public @Nullable ProjectileAnimatableCapabilityProvider getProjectileAnimatableCapabilityProvider() {
        return projectileAnimatableCapabilityProvider;
    }
}
