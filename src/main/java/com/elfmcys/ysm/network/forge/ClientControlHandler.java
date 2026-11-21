package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.network.protocol.ExpressionProtocol;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.util.ProtoBytes;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import static com.elfmcys.ysm.network.forge.ControlHandler.*;

/** Keeps client capability types out of common handler verification on dedicated servers. */
@OnlyIn(Dist.CLIENT)
final class ClientControlHandler {
    private ClientControlHandler() {}

    static void handleConfigAction(ConfigActionEventProto message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (validWireAction(message.action())) {
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> {
                var level = Minecraft.getInstance().level;
                var entity = level == null ? null : level.getEntity(message.subject().entityId());
                if (entity instanceof Player player) {
                    player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                        .ifPresent(capability -> applyConfigAction(capability, message.identity(), message.action()));
                }
            }));
        }
        context.setPacketHandled(true);
    }

    private static void applyConfigAction(AnimatableEntity<?> capability, ExpressionTargetIdentityProto identity,
        ConfigActionProto action) {
        capability.waitForAsyncUpdate();
        var runtime = capability.getExpressionRuntime();
        var module = runtime.metadata().orElse(null);
        var entity = capability;
        if (module == null || entity.getModelRenderTarget() == null
            || !acceptsConfigAction(identity, action, entity.getModelRenderTarget().sharedSemantics()))
            return;
        var generation = runtime.generation();
        var route = new ConfigActionRoute(action.buttonId(), action.formIndex());
        if (action.hasBoolValue()) {
            capability.writeConfigBoolean(generation, route, action.boolValue());
        } else if (action.hasF32Value()) {
            capability.writeConfigFloat(generation, route, action.f32Value());
        } else if (action.hasLabelIndex()) {
            capability.executeConfigLabel(generation, route, action.labelName(), action.labelIndex());
        }
    }
}
