package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.network.StarredModelsSnapshotProto;
import com.elfmcys.ysm.util.ProtoBytes;

import java.util.Comparator;
import java.util.Set;

/** Builds the side-neutral payload used before any client-only control code is needed. */
public final class StarredModelSnapshots {
    public static final int MAX_MODEL_SET_SIZE = 16_384;

    private StarredModelSnapshots() {}

    public static StarredModelsSnapshotProto create(Set<Hash256> hashes) {
        var result = StarredModelsSnapshotProto.newBuilder();
        hashes.stream().sorted(Comparator.naturalOrder()).limit(MAX_MODEL_SET_SIZE)
            .forEach(hash -> result.addModelHashes(ProtoBytes.wrap(hash)));
        return result.build();
    }
}
