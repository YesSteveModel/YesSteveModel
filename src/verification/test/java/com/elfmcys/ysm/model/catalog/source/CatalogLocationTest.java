package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogLocationTest {
    @Test
    void explicitIdentityRequiresAnExactCurrentModelExceptForHistoricalClearing() {
        var id = ModelId.parse("Author/model@1.0.0+pc");
        var newer = ModelId.parse("Author/model@2.0.0");
        var positions = Map.of(CatalogLocation.parse("local:pack/model.mxc"), id);
        assertEquals(PublishedModelId.model(id), ModelSelector.resolve("id:" + id, positions).modelId());
        assertEquals(ModelSelector.Status.NOT_FOUND, ModelSelector.resolve("id:" + newer, positions).status());
        assertEquals(PublishedModelId.model(newer),
            ModelSelector.resolveHistorical("id:" + newer, positions).modelId());
        for (var input : new String[] {"id:", "id:default", "id:Author/model@01.0.0", "id:Author/model@1"})
            assertEquals(ModelSelector.Status.INVALID, ModelSelector.resolveHistorical(input, positions).status());
        assertEquals(ModelSelector.Status.NOT_FOUND, ModelSelector.resolveHistorical("missing", positions).status());
        assertTrue(ModelSelector.suggestions(positions).contains("id:" + id));
        assertEquals("local:custom/x", CatalogLocation.parse("local:custom/x").value());
    }

    @Test
    void preservesHandwrittenAddressesAndScalarSequences() {
        for (var address : new String[] {"global:pack/model.mxc", "global:model.zip", "local:空 格/$literal%2e%2e.ysm",
            "local:a..b", "builtin:default", "builtin:other", "builtin:pack/model.mxc", "local:e\u0301/😀.mxc"})
            assertEquals(address, CatalogLocation.parse(address).value());
        assertEquals("global:", CatalogLocation.directory("global:").value());
        assertThrows(IllegalArgumentException.class, () -> CatalogLocation.parse("global:"));
        assertEquals(" padded ", new ModelPath(" padded ").value());
    }

    @Test
    void rootsAndParentsUseTheSameCanonicalAddressGrammar() {
        for (var address : new String[] {"global:", "local:", "builtin:"}) {
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
        assertEquals(new CatalogLocation(CatalogRoot.BUILTIN, "default"), CatalogLocation.BUILTIN_DEFAULT);
        assertEquals("builtin:default", CatalogLocation.BUILTIN_DEFAULT.value());
        assertEquals("builtin:", CatalogLocation.BUILTIN_DEFAULT.parent());
    }

    @Test
    void rejectsInvalidInputsWithoutNormalizingThem() {
        for (var entry : new String[] {"", "/a", "a/", "a//b", "a/./b", "a/../b", "a\\b", "C:/a", "file:/a", "a\u0001b",
            "a\u007fb", "a\u009fb", "a\ud800", "a\udc00"})
            assertThrows(IllegalArgumentException.class, () -> new ModelPath(entry), entry);
        for (var address : new String[] {"global:/a", "$ysm_local/custom/a", "$YSM_GAME/a", "builtin:",
            "$YSM_GLOBAL/custom/a", "$YSM_LOCAL/auth/a", "$YSM_BUILTIN/default", "default", "builtin:/default",
            "builtin:default/", "global:/custom/a", "local:a:b", "Global:custom/a", "/tmp/model.mxc", "C:/model.mxc",
            "\\\\host\\model.mxc", "file:///a"})
            assertThrows(IllegalArgumentException.class, () -> CatalogLocation.parse(address), address);
    }

    @Test
    void defaultIsReservedForTheIntrinsicModelEvenWhenUserModelsHaveTheSameName() {
        var builtin = ModelId.fromHash(new Hash256(new byte[32]));
        var bytes = new byte[32];
        bytes[0] = 1;
        var custom = ModelId.fromHash(new Hash256(bytes));
        var positions =
            Map.of(CatalogLocation.BUILTIN_DEFAULT, builtin, CatalogLocation.parse("local:default"), custom);
        assertEquals(PublishedModelId.INTRINSIC_DEFAULT, ModelSelector.resolve("default", positions).modelId());
        assertEquals(PublishedModelId.model(builtin), ModelSelector.resolve("builtin:default", positions).modelId());
        assertEquals(PublishedModelId.model(custom), ModelSelector.resolve("local:default", positions).modelId());
        assertEquals(ModelSelector.Status.FOUND,
            ModelSelector.resolve("default", Map.of(CatalogLocation.parse("local:default"), custom)).status());
        for (var address : new String[] {"$YSM_BUILTIN/default", "$YSM_LOCAL/custom/default", "unknown:default"})
            assertEquals(ModelSelector.Status.INVALID, ModelSelector.resolve(address, positions).status(), address);
    }

    @Test
    void resolvesPublishedPositionsBeforePermissionsAndNeverAddsSuffixes() {
        var first = ModelId.fromHash(new Hash256(new byte[32]));
        var bytes = new byte[32];
        bytes[0] = 1;
        var second = ModelId.fromHash(new Hash256(bytes));
        var positions = Map.of(CatalogLocation.parse("global:pack/model.mxc"), first,
            CatalogLocation.parse("local:pack/model.mxc"), second);
        assertEquals(ModelSelector.Status.AMBIGUOUS, ModelSelector.resolve("pack/model.mxc", positions).status());
        assertEquals(PublishedModelId.model(first),
            ModelSelector.resolve("global:pack/model.mxc", positions).modelId());
        assertEquals(ModelSelector.Status.NOT_FOUND, ModelSelector.resolve("pack/model", positions).status());
        assertEquals(ModelSelector.Status.NOT_FOUND, ModelSelector.resolve("model.mxc", positions).status());
        assertEquals(ModelSelector.Status.INVALID, ModelSelector.resolve("$YSM_OTHER/model", positions).status());
    }
}
