package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.*;
import com.elfmcys.ysm.model.catalog.source.*;
import com.elfmcys.ysm.model.domain.*;
import com.elfmcys.ysm.model.storage.CacheRegion;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCardActionsTest {
    @TempDir
    Path temp;

    @Test
    void shortcutsConsumeTheClickAndUseOnlyPublicationOrLogicalSourceProof() throws Exception {
        var physical = Files.createDirectory(temp.resolve("PHYSICAL_SECRET"));
        Files.writeString(physical.resolve("ysm.json"), "{}");
        var root = Files.createDirectory(temp.resolve("custom"));
        var logical = Files.createSymbolicLink(root.resolve("alias"), physical);
        var key = new ModelSourceKey(new CatalogRootIdentity(CatalogRoot.GLOBAL_AUTH, root), new ModelPath("alias"),
            ModelSourceKind.CURRENT_RAW_DIRECTORY);
        var binding = ContentStorageBinding.local(new CacheRegion(temp.resolve("cache")),
            ModelSourceDiscovery.observe(key, logical));
        var entry = entry(binding);
        var copied = new ArrayList<String>();
        var opened = new ArrayList<Path>();
        assertTrue(CatalogCardActions.click(true, 1, "global:auth/alias", entry, false, copied::add, opened::add));
        assertEquals(java.util.List.of("global:auth/alias"), copied);
        assertTrue(opened.isEmpty());
        assertTrue(CatalogCardActions.click(true, 0, entry.displayPath(), entry, true, copied::add, opened::add));
        assertEquals(java.util.List.of(logical), opened);
        assertTrue(CatalogCardActions.click(true, 0, entry.displayPath(), entry, false, copied::add, opened::add));
        assertEquals(1, opened.size());
        assertTrue(
            CatalogCardActions.click(true, 1, "local:custom/pending.mxc", null, false, copied::add, opened::add));
        assertEquals("local:custom/pending.mxc", copied.get(1));
        assertFalse(CatalogCardActions.click(false, 0, entry.displayPath(), entry, true, copied::add, opened::add));
        assertEquals(1, opened.size());
    }

    @Test
    void fileModelsOpenTheLogicalParentAndBuiltinHasNoHostCapability() throws Exception {
        var root = Files.createDirectory(temp.resolve("custom"));
        var file = Files.write(root.resolve("model.zip"), new byte[] {1});
        var key = new ModelSourceKey(new CatalogRootIdentity(CatalogRoot.GLOBAL_AUTH, root), new ModelPath("model.zip"),
            ModelSourceKind.CURRENT_RAW_ARCHIVE);
        var region = new CacheRegion(temp.resolve("cache"));
        var entry = entry(ContentStorageBinding.local(region, ModelSourceDiscovery.observe(key, file)));
        var opened = new ArrayList<Path>();
        CatalogCardActions.click(true, 0, entry.displayPath(), entry, true, ignored -> fail(), opened::add);
        assertEquals(java.util.List.of(root), opened);
        CatalogCardActions.click(true, 0, "default", entry(ContentStorageBinding.builtin(region)), true,
            ignored -> fail(), opened::add);
        assertEquals(1, opened.size());
    }

    private static ClientCatalogEntry entry(ContentStorageBinding binding) {
        var id = new Hash256(new byte[32]);
        var content = new ModelContent() {
            @Override
            public Hash256 modelId() {
                return id;
            }

            @Override
            public ModelRepresentation representation() {
                return null;
            }

            @Override
            public com.elfmcys.ysm.format.schema.file.ChunkDataSource chunks() {
                return null;
            }

            @Override
            public ContentStorageBinding storageBinding() {
                return binding;
            }
        };
        return new ClientCatalogEntry(new CatalogEntry(id, new HierarchyPath("global:auth/alias"),
            CatalogAccess.AUTHORIZED, CatalogPresentation.empty("alias")), content, ClientCatalogEntry.Origin.AUTH);
    }
}
