package com.elfmcys.ysm.capability;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelSelectionServiceTest {
    private static ManagedContainer content;
    private static CatalogRecord required;
    private static ServerCatalog complete;

    @BeforeAll
    static void prepare() throws Exception {
        ServerConfig.init().setConfig(CommentedConfig.inMemory());
        var source =
            Path.of(ModelSelectionServiceTest.class.getResource("/assets/ysm/builtin/default/ysm.json").toURI())
                .getParent();
        try (var directory = new Directory(source)) {
            content = ManagedContainer.openResidentDefault(ModelParser.parseBuiltinDefaultMemory(directory).bytes(),
                CatalogLocation.BUILTIN_DEFAULT);
        }
        required =
            new CatalogRecord(CatalogLocation.BUILTIN_DEFAULT, new CatalogContentBinding(content.modelId(), content));
        complete = new ServerCatalog(
            new CatalogSnapshot(Map.of(content.modelId(), required), required, List.of(), ModelScanReport.empty()));
    }

    @BeforeEach
    void defaults() {
        ServerConfig.DEFAULT_MODEL_PATH.set("builtin:default");
        ServerConfig.DEFAULT_MODEL_TEXTURE.set("default");
    }

    @AfterAll
    static void close() {
        content.representation().close();
    }

    @Test
    void initialAndInvalidConfigurationSelectOrdinaryDefault() {
        for (var path : List.of("builtin:default", "missing", "invalid:address")) {
            ServerConfig.DEFAULT_MODEL_PATH.set(path);
            var capability = new ModelInfoCapability();
            assertTrue(ModelSelectionService.resolve(capability, complete).isPresent());
            assertEquals(PublishedModelId.model(content.modelId()), capability.getModelId());
        }
    }

    @Test
    void explicitIntrinsicAndSavedOrdinaryKeepTheirRoles() {
        ServerConfig.DEFAULT_MODEL_PATH.set("default");
        var intrinsic = new ModelInfoCapability();
        assertTrue(ModelSelectionService.resolve(intrinsic, complete).isPresent());
        assertEquals(PublishedModelId.INTRINSIC_DEFAULT, intrinsic.getModelId());
        var ordinary = new ModelInfoCapability();
        ordinary.setModelAndTexture(content.modelId(), "default");
        assertTrue(ModelSelectionService.resolve(ordinary, complete).isPresent());
        assertEquals(PublishedModelId.model(content.modelId()), ordinary.getModelId());
    }

    @Test
    void delayedOrdinaryPublicationPreservesTheInitialIntent() {
        var pending = new ServerCatalog(new CatalogSnapshot(Map.of(), required, List.of(), ModelScanReport.empty()));
        var capability = new ModelInfoCapability();
        assertTrue(ModelSelectionService.resolve(capability, pending).isEmpty());
        assertEquals(PublishedModelId.model(content.modelId()), capability.getModelId());
        assertTrue(ModelSelectionService.resolve(capability, complete).isPresent());
        assertEquals(PublishedModelId.model(content.modelId()), capability.getModelId());
    }
}
