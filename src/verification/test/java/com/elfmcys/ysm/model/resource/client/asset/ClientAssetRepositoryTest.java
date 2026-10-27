package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAssetRepositoryTest {
    @Test
    void packCoverContentIgnoresEndpointLocalRootKindAndFormatCase() {
        var local = pack(CatalogRootKind.BUILTIN, "wine_fox", "PNG", 1024, 1);
        var remote = pack(CatalogRootKind.CUSTOM, "wine_fox", "png", 1024, 1);

        assertTrue(ClientAssetRepository.samePackCoverContent(local, remote));
    }

    @Test
    void packCoverContentRequiresTheCompletePublishedDescriptor() {
        var requested = pack(CatalogRootKind.CUSTOM, "wine_fox", "PNG", 1024, 1);

        assertFalse(ClientAssetRepository.samePackCoverContent(pack(CatalogRootKind.BUILTIN, "misc", "PNG", 1024, 1),
            requested));
        assertFalse(ClientAssetRepository
            .samePackCoverContent(pack(CatalogRootKind.BUILTIN, "wine_fox", "PNG", 1024, 2), requested));
        assertFalse(ClientAssetRepository
            .samePackCoverContent(pack(CatalogRootKind.BUILTIN, "wine_fox", "PNG", 1023, 1), requested));
        assertFalse(ClientAssetRepository
            .samePackCoverContent(pack(CatalogRootKind.BUILTIN, "wine_fox", "JPEG", 1024, 1), requested));
    }

    private static ModelPackDescriptor pack(CatalogRootKind rootKind, String hierarchy, String format, int size,
        int hashMarker) {
        var hash = new byte[Hash256.SIZE];
        hash[0] = (byte)hashMarker;
        return new ModelPackDescriptor(rootKind,
            "local:custom" + (hierarchy.isEmpty() ? ""
                : "/" + (hierarchy.endsWith("/") ? hierarchy.substring(0, hierarchy.length() - 1) : hierarchy)),
            "", "", Map.of(), new Hash256(hash), format, size);
    }
}
