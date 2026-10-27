package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.ProjectileModelInfoCapability;
import com.elfmcys.ysm.capability.VehicleModelInfoCapability;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.util.ProtoBytes;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftStateHandlerTest {
    @Test
    void mapsProjectileAndVehicleStateWithoutNbt() {
        var hashBytes = new byte[Hash256.SIZE];
        hashBytes[0] = 42;
        var hash = new Hash256(hashBytes);
        var variables = new Object2FloatOpenHashMap<String>();
        variables.put("query.test", 1.5F);
        var projectile = new ProjectileModelInfoCapability();
        projectile.init(hash, variables);
        var vehicle = new VehicleModelInfoCapability();
        vehicle.update(hash, variables);

        var projectileMessage = MinecraftStateHandler.projectile(7, projectile);
        var vehicleMessage = MinecraftStateHandler.vehicle(9, vehicle);

        assertEquals(7, projectileMessage.entity().entityId());
        assertEquals(9, vehicleMessage.entity().entityId());
        assertTrue(!projectileMessage.entity().hasPlayerId());
        assertTrue(!vehicleMessage.entity().hasPlayerId());
        assertTrue(ProtoBytes.equals(hash, projectileMessage.model().modelHash()));
        assertTrue(ProtoBytes.equals(hash, vehicleMessage.model().modelHash()));
        assertEquals("query.test", projectileMessage.molangVariables().get(0).name());
        assertEquals(1.5F, vehicleMessage.molangVariables().get(0).value_());
    }
}
