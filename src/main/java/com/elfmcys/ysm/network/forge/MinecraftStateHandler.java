package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.ClientLazyCapabilityProvider;
import com.elfmcys.ysm.capability.ProjectileModelInfoCapability;
import com.elfmcys.ysm.capability.VehicleModelInfoCapability;
import com.elfmcys.ysm.client.event.EntityLoadEvent;
import com.elfmcys.ysm.util.StringPool;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.network.protocol.ModelReferenceCodec;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.MolangVariableProto;
import com.elfmcys.ysm.proto.network.ProjectileModelStateProto;
import com.elfmcys.ysm.proto.network.VehicleModelStateProto;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

public final class MinecraftStateHandler {
    private MinecraftStateHandler() {}

    public static ProjectileModelStateProto projectile(int entityId, ProjectileModelInfoCapability capability) {
        var message =
            ProjectileModelStateProto.newBuilder().setEntity(EntityRefProto.newBuilder().setEntityId(entityId).build())
                .setModel(ModelReferenceCodec.create(capability.getOwnerModelHash(), null));
        addVariables(message::addMolangVariables, capability.getMolangVarsServerBound());
        return message.build();
    }

    public static VehicleModelStateProto vehicle(int entityId, VehicleModelInfoCapability capability) {
        var message =
            VehicleModelStateProto.newBuilder().setEntity(EntityRefProto.newBuilder().setEntityId(entityId).build())
                .setModel(ModelReferenceCodec.create(capability.getOwnerModelHash(), null));
        addVariables(message::addMolangVariables, capability.getMolangVarsServerBound());
        return message.build();
    }

    public static void handleProjectile(ProjectileModelStateProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (!ModelReferenceCodec.valid(message.model())) {
            context.setPacketHandled(true);
            return;
        }
        var modelHash = ModelReferenceCodec.read(message.model());
        var variables = clientVariables(message.molangVariables());
        var connection = context.getNetworkManager();
        context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> EntityLoadEvent
            .executeOnEntity(message.entity().entityId(), entity -> applyProjectile(entity, modelHash, variables))));
        context.setPacketHandled(true);
    }

    public static void handleVehicle(VehicleModelStateProto message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (!ModelReferenceCodec.valid(message.model())) {
            context.setPacketHandled(true);
            return;
        }
        var modelHash = ModelReferenceCodec.read(message.model());
        var variables = clientVariables(message.molangVariables());
        var connection = context.getNetworkManager();
        context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> EntityLoadEvent
            .executeOnEntity(message.entity().entityId(), entity -> applyVehicle(entity, modelHash, variables))));
        context.setPacketHandled(true);
    }

    private static void addVariables(Consumer<MolangVariableProto> variableConsumer,
        Object2FloatOpenHashMap<String> variables) {
        variables.object2FloatEntrySet().fastForEach(entry -> variableConsumer
            .accept(MolangVariableProto.newBuilder().setName(entry.getKey()).setValue(entry.getFloatValue()).build()));
    }

    private static Int2FloatOpenHashMap clientVariables(Iterable<MolangVariableProto> variables) {
        var result = new Int2FloatOpenHashMap();
        for (var variable : variables) {
            result.put(StringPool.computeIfAbsent(variable.name()), variable.value_());
        }
        return result;
    }

    @OnlyIn(Dist.CLIENT)
    @SuppressWarnings("DataFlowIssue")
    private static void applyProjectile(Entity entity, Hash256 modelHash, Int2FloatOpenHashMap variables) {
        entity.getCapability(ClientLazyCapabilityProvider.CAP).ifPresent(capability -> {
            var animatable = capability.getProjectileAnimatableCapabilityProvider().initialize();
            animatable.init(modelHash);
            animatable.initRoamingVars(variables);
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyVehicle(Entity entity, Hash256 modelHash, Int2FloatOpenHashMap variables) {
        com.elfmcys.ysm.network.NetworkHandler.traceNetwork("vehicle-model-apply", "vehicleId=" + entity.getId(),
            "modelKey=" + modelHash.roamingHash(), "variables=" + variables.size());
        entity.getCapability(ClientLazyCapabilityProvider.CAP).ifPresent(capability -> {
            var animatable = capability.getVehicleAnimatableCapabilityProvider().initialize();
            animatable.init(modelHash);
            animatable.initRoamingVars(variables);
        });
    }
}
