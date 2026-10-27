package com.elfmcys.ysm.model.resource.client.data;

/** Kind-specific input used to build exactly one model render target. */
public sealed interface RenderTargetData permits PlayerModelData, ProjectileModelData, VehicleModelData {}
