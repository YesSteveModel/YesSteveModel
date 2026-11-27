package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.proto.mixel.asset.model.data.CollectionNameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsDefinitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;

import java.util.List;
import java.util.Objects;

/** The admitted declarations, in source order and units, available before a world produces a sample. */
public record PhysicsAssetMetadata(PhysicsDefinitionProto definition) {
    public PhysicsAssetMetadata {
        Objects.requireNonNull(definition, "definition");
    }

    public List<PhysicsNodeProto> nodes() {
        return definition.nodes();
    }

    public List<PhysicsConstraintProto> constraints() {
        return definition.constraints();
    }

    public List<CollectionNameProto> collections() {
        return definition.collections();
    }
}
