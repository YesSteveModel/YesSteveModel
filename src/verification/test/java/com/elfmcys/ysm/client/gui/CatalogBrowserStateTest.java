package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.client.ModelPackInfo;
import com.elfmcys.ysm.model.catalog.client.entry.ClientFailedCatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogBrowserStateTest {
    @Test
    void sourcePrefixesStayOutOfNavigationAndSameNamedDirectoriesRemainIndependent() {
        var browser = new CatalogBrowserState();
        var entries = List.of(failed(1, "global:custom/top.mxc"), failed(2, "global:custom/team/one.mxc"),
            failed(3, "local:auth/team/two.mxc"));
        browser.rebuild(new ClientCatalogSnapshot(CatalogSnapshot.empty(), entries), CatalogBrowserStateTest::pack);
        browser.enterPack("");
        filter(browser, Set.of());
        assertEquals(List.of("global:custom/top.mxc"),
            browser.failed().stream().map(ClientFailedCatalogEntry::displayPath).toList());
        assertEquals(List.of("global:custom/team", "local:auth/team"),
            browser.packs().stream().map(ModelPackInfo::hierarchy).toList());
        assertEquals(List.of("team", "team"), browser.packs().stream().map(ModelPackInfo::name).toList());
        browser.enterPack("local:auth/team");
        filter(browser, Set.of());
        assertEquals(List.of("local:auth/team/two.mxc"),
            browser.failed().stream().map(ClientFailedCatalogEntry::displayPath).toList());
        browser.backToParent();
        assertEquals("", browser.currentPack());
        filter(browser, Set.of("global:custom/top.mxc"));
        assertTrue(browser.failed().isEmpty());
    }

    @Test
    void anExplicitRootManifestRemainsARealPackWithItsOwnMembers() {
        var browser = new CatalogBrowserState();
        var descriptor =
            new ModelPackDescriptor(CatalogRootKind.CUSTOM, "global:custom", "Root pack", "", Map.of(), null, "", 0);
        var snapshot = new CatalogSnapshot(Map.of(), List.of(descriptor), ModelScanReport.empty());
        browser.rebuild(
            new ClientCatalogSnapshot(snapshot,
                List.of(failed(1, "global:custom/top.mxc"), failed(2, "global:custom/team/one.mxc"))),
            CatalogBrowserStateTest::pack);
        browser.enterPack("");
        filter(browser, Set.of());
        assertEquals(List.of("global:custom"), browser.packs().stream().map(ModelPackInfo::hierarchy).toList());
        assertTrue(browser.failed().isEmpty());
        browser.enterPack("global:custom");
        filter(browser, Set.of());
        assertEquals(1, browser.failed().size());
        assertEquals(List.of("global:custom/team"), browser.packs().stream().map(ModelPackInfo::hierarchy).toList());
        browser.backToParent();
        assertEquals("", browser.currentPack());
    }

    private static void filter(CatalogBrowserState browser, Set<String> hidden) {
        browser.filter("", "en_us", hidden, ignored -> false, ignored -> false);
    }

    private static ModelPackInfo pack(ModelPackDescriptor descriptor) {
        return new ModelPackInfo(descriptor.hierarchy(), descriptor.name(), descriptor.description(), null, Map.of());
    }

    private static ClientFailedCatalogEntry failed(int marker, String address) {
        var bytes = new byte[32];
        bytes[0] = (byte)marker;
        return new ClientFailedCatalogEntry(new Hash256(bytes), new HierarchyPath(address), CatalogAccess.PUBLIC,
            "Content validation failed");
    }
}
