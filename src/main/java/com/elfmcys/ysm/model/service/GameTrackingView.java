package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.mixin.ChunkMapAccessor;
import com.elfmcys.ysm.mixin.TrackedEntityAccessor;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** Borrows the current host tracking relation without retaining a parallel index. */
public interface GameTrackingView {
    List<ServerPlayer> observers(ServerPlayer subject);

    boolean isTracking(ServerPlayer observer, ServerPlayer subject);

    static GameTrackingView live() {
        return new GameTrackingView() {
            @Override
            public List<ServerPlayer> observers(ServerPlayer subject) {
                var tracked = tracked(subject);
                if (tracked == null)
                    return List.of();
                return tracked.ysm$getSeenBy().stream().map(connection -> connection.getPlayer()).toList();
            }

            @Override
            public boolean isTracking(ServerPlayer observer, ServerPlayer subject) {
                var tracked = tracked(subject);
                return tracked != null && tracked.ysm$getSeenBy().contains(observer.connection);
            }

            private TrackedEntityAccessor tracked(ServerPlayer subject) {
                return (TrackedEntityAccessor)((ChunkMapAccessor)subject.serverLevel().getChunkSource().chunkMap)
                    .ysm$getEntityMap().get(subject.getId());
            }
        };
    }
}
