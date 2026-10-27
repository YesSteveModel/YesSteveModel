package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogLocationTest {
    @Test
    void preservesHandwrittenAddressesAndScalarSequences() {
        for (var address : new String[] {"global:custom/pack/model.mxc", "global:auth/model.zip",
            "local:custom/空 格/$literal%2e%2e.ysm", "local:auth/a..b", "default", "builtin:other",
            "builtin:pack/model.mxc", "local:custom/e\u0301/😀.mxc"})
            assertEquals(address, CatalogLocation.parse(address).value());
        assertEquals("global:custom", CatalogLocation.directory("global:custom").value());
        assertThrows(IllegalArgumentException.class, () -> CatalogLocation.parse("global:custom"));
        assertEquals(" padded ", new ModelPath(" padded ").value());
    }

    @Test
    void rootsAndParentsUseTheSameCanonicalAddressGrammar() {
        for (var address : new String[] {"global:custom", "global:auth", "local:custom", "local:auth", "builtin:"}) {
            var root = CatalogLocation.directory(address);
            assertEquals(address, root.value());
            assertTrue(root.entry().isEmpty());
            assertEquals(address, root.parent());
            assertThrows(IllegalArgumentException.class, () -> CatalogLocation.parse(address));
            var childAddress = address + (address.endsWith(":") ? "" : "/") + "pack/model.mxc";
            var child = CatalogLocation.parse(childAddress);
            assertEquals(childAddress, child.value());
            var parent = CatalogLocation.directory(child.parent());
            assertEquals("pack", parent.entry());
            assertEquals(address, parent.parent());
        }
        assertEquals(new CatalogLocation(CatalogRoot.BUILTIN, "default"), CatalogLocation.DEFAULT);
        assertEquals("default", CatalogLocation.DEFAULT.value());
        assertEquals("builtin:", CatalogLocation.DEFAULT.parent());
    }

    @Test
    void rejectsInvalidInputsWithoutNormalizingThem() {
        for (var entry : new String[] {"", "/a", "a/", "a//b", "a/./b", "a/../b", "a\\b", "C:/a", "file:/a", "a\u0001b",
            "a\u007fb", "a\u009fb", "a\ud800", "a\udc00"})
            assertThrows(IllegalArgumentException.class, () -> new ModelPath(entry), entry);
        for (var address : new String[] {"global:other/a", "$ysm_local/custom/a", "$YSM_GAME/a", "builtin:",
            "$YSM_GLOBAL/custom/a", "$YSM_LOCAL/auth/a", "$YSM_BUILTIN/default", "builtin:default", "builtin:/default",
            "builtin:default/", "global:/custom/a", "local:custom/a:b", "Global:custom/a", "/tmp/model.mxc",
            "C:/model.mxc", "\\\\host\\model.mxc", "file:///a"})
            assertThrows(IllegalArgumentException.class, () -> CatalogLocation.parse(address), address);
    }

    @Test
    void defaultIsReservedForTheIntrinsicModelEvenWhenUserModelsHaveTheSameName() {
        var builtin = new Hash256(new byte[32]);
        var bytes = new byte[32];
        bytes[0] = 1;
        var custom = new Hash256(bytes);
        var positions = Map.of(CatalogLocation.DEFAULT, builtin, CatalogLocation.parse("local:custom/default"), custom);
        assertEquals(builtin, CatalogAddressResolver.resolve("default", positions).modelId());
        assertEquals(custom, CatalogAddressResolver.resolve("local:custom/default", positions).modelId());
        assertEquals(CatalogAddressResolver.Status.NOT_FOUND, CatalogAddressResolver
            .resolve("default", Map.of(CatalogLocation.parse("local:custom/default"), custom)).status());
        for (var address : new String[] {"builtin:default", "$YSM_BUILTIN/default", "$YSM_LOCAL/custom/default",
            "unknown:default"})
            assertEquals(CatalogAddressResolver.Status.INVALID,
                CatalogAddressResolver.resolve(address, positions).status(), address);
    }

    @Test
    void resolvesPublishedPositionsBeforePermissionsAndNeverAddsSuffixes() {
        var first = new Hash256(new byte[32]);
        var bytes = new byte[32];
        bytes[0] = 1;
        var second = new Hash256(bytes);
        var positions = Map.of(CatalogLocation.parse("global:custom/pack/model.mxc"), first,
            CatalogLocation.parse("local:auth/pack/model.mxc"), second);
        assertEquals(CatalogAddressResolver.Status.AMBIGUOUS,
            CatalogAddressResolver.resolve("pack/model.mxc", positions).status());
        assertEquals(first, CatalogAddressResolver.resolve("global:custom/pack/model.mxc", positions).modelId());
        assertEquals(CatalogAddressResolver.Status.NOT_FOUND,
            CatalogAddressResolver.resolve("pack/model", positions).status());
        assertEquals(CatalogAddressResolver.Status.NOT_FOUND,
            CatalogAddressResolver.resolve("model.mxc", positions).status());
        assertEquals(CatalogAddressResolver.Status.INVALID,
            CatalogAddressResolver.resolve("$YSM_OTHER/model", positions).status());
    }
}
