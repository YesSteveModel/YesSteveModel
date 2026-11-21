package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.network.protocol.SelectionCodec;
import com.elfmcys.ysm.proto.network.EntityTrackProto;
import com.elfmcys.ysm.proto.network.PlayerFlagsStateProto;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

public final class ModelInfoSyncAssembler {
    private ModelInfoSyncAssembler() {}

    public static Optional<EntityTrackProto> build(ServerPlayer player, ModelInfoCapability capability,
        ServerCatalog catalog) {
        return ModelSelectionService.resolve(capability, catalog)
            .map(ignored -> EntityTrackProto.newBuilder()
                .setSelection(SelectionCodec.write(player.getId(), capability.selection()))
                .setState(capability.getPropertiesTracker().snapshot(player))
                .setFlags(PlayerFlagsStateProto.newBuilder().setDisabled(capability.isDisabled()).build()).build());
    }
}
