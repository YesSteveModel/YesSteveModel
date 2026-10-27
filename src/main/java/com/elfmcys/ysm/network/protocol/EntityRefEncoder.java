package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.EntityRefProto;

/** Encodes current-level entity routing without advertising stable player identity. */
public final class EntityRefEncoder {
    private EntityRefEncoder() {}

    public static EntityRefProto encode(int entityId) {
        return EntityRefProto.newBuilder().setEntityId(entityId).build();
    }
}
