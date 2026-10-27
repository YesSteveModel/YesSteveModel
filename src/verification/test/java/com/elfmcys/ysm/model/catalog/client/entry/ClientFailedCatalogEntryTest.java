package com.elfmcys.ysm.model.catalog.client.entry;

import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientFailedCatalogEntryTest {
    @Test
    void failedPresentationIsPathOnlyAndNeverBecomesSelectableContent() {
        var failed = new ClientFailedCatalogEntry(hash(1), new HierarchyPath("local:custom/pack/missing"),
            CatalogAccess.PUBLIC, "offline");
        var snapshot = new ClientCatalogSnapshot(CatalogSnapshot.empty(), List.of(failed));

        assertTrue(snapshot.models().isEmpty());
        assertEquals(List.of(failed), snapshot.failed());
        assertEquals("local:custom/pack/missing", failed.displayPath());
        assertFalse(Arrays.stream(ClientFailedCatalogEntry.class.getRecordComponents())
            .anyMatch(component -> component.getName().equals("containerId")));
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }
}
