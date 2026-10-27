package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogIndexSnapshotTest {
    @TempDir
    Path temp;

    @Test
    void candidateLookupIsStableWithExactIdentityFirst() {
        var modelId = hash(1);
        var currentContainer = container(2);
        var previousContainer = container(3);
        var previous = entry(modelId, previousContainer, "previous.mxc");
        var first = entry(modelId, currentContainer, "first.mxc");
        var second = entry(modelId, currentContainer, "second.mxc");
        var index = new CatalogIndexSnapshot(List.of(previous, first, second), List.of(), ModelScanReport.empty());

        assertEquals(List.of(first, second, previous),
            index.findCandidates(new ModelFileIdentity(modelId, currentContainer)));
        assertTrue(index.findCandidates(new ModelFileIdentity(hash(4), currentContainer)).isEmpty());
    }

    private CatalogIndexEntry entry(Hash256 modelId, Hash256 containerId, String path) {
        return new CatalogIndexEntry(new ModelFileIdentity(modelId, containerId),
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath(path)), temp.resolve(path));
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }

    private static Hash256 container(int seed) {
        return hash(seed);
    }
}
