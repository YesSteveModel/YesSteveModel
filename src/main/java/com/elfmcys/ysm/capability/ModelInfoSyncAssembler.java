package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.network.forge.PlayerStateHandler;
import com.elfmcys.ysm.network.protocol.ModelReferenceCodec;
import com.elfmcys.ysm.proto.network.ModelSelectionStateProto;
import com.elfmcys.ysm.proto.network.MolangVariableProto;
import com.elfmcys.ysm.proto.network.PlayerStateUpdateProto;
import com.elfmcys.ysm.proto.network.RoamingStateProto;

import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

public final class ModelInfoSyncAssembler {
    private ModelInfoSyncAssembler() {}

    public static Optional<PlayerStateUpdateProto> build(ServerPlayer player, ModelInfoCapability capability,
        ServerCatalog snapshot) {
        return ModelSelectionService.resolve(capability, snapshot).map(model -> {
            var hash = model.representation().modelId();
            var builtinDefault = model.location().rootKind() == CatalogRootKind.BUILTIN
                && model.location().path().value().equals("default") ? hash : null;
            var reference = ModelReferenceCodec.create(hash, builtinDefault);
            var update =
                PlayerStateHandler.newFull(player).setModel(ModelSelectionStateProto.newBuilder().setModel(reference)
                    .setTextureId(capability.getSelectTexture()).setDisabled(capability.isDisabled()).build());
            capability.getPropertiesTracker().populateFull(update, player);
            var variables = capability.roamingVariables().variables(hash);
            var roaming = RoamingStateProto.newBuilder().setModelKey(hash.roamingHash());
            variables.object2FloatEntrySet().fastForEach(entry -> roaming.addVariables(
                MolangVariableProto.newBuilder().setName(entry.getKey()).setValue(entry.getFloatValue()).build()));
            update.setRoaming(roaming.build());
            return update.build();
        });
    }
}
