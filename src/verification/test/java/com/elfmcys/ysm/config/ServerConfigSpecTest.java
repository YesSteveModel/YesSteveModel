package com.elfmcys.ysm.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import com.elfmcys.ysm.model.domain.ModelId;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigSpecTest {
    @TempDir
    Path temp;

    @Test
    void rejectedInPlaceMutationsCannotChangeUnreadAcceptedValues() {
        var spec = ServerConfig.init();
        var source = CommentedConfig.inMemory();
        var rules = new ArrayList<>(List.of("path:global:*"));
        source.set("RestrictedModelList", rules);
        source.set("server_scheduler.RestrictedAuth", false);
        spec.acceptConfig(source);
        rules.add("broken");
        source.set("server_scheduler.RestrictedAuth", true);
        spec.afterReload();
        assertFalse(ServerConfig.RESTRICTED_AUTH.get());
        assertEquals(List.of("path:global:*"), ServerConfig.RESTRICTED_MODEL_LIST.get());
    }

    @Test
    void acceptedFilesPersistDefaultsAndListValuesWhileRejectedFilesRemainUntouched() throws Exception {
        var path = temp.resolve("server.toml");
        Files.writeString(path, "RestrictedModelList = ['path:global:*']\n");
        var spec = ServerConfig.init();
        try (var config = CommentedFileConfig.of(path)) {
            config.load();
            spec.acceptConfig(config);
            assertEquals(List.of("path:global:*"), ServerConfig.RESTRICTED_MODEL_LIST.get());
            assertTrue(Files.readString(path).contains("DefaultModelPath"));
            var rejected = "RestrictedModelList = ['id:*', 'broken']\nDefaultModelTexture = 'bad'\n";
            Files.writeString(path, rejected);
            config.load();
            if (!spec.isCorrect(config)) {
                spec.correct(config);
                config.save();
            }
            spec.afterReload();
            assertEquals(rejected, Files.readString(path));
            assertTrue(spec.policy().restricted("global:a", ModelId.parse("a/m")));
            assertEquals("default", ServerConfig.DEFAULT_MODEL_TEXTURE.get());
        }
    }

    @Test
    void rawInvalidCandidatesAreNeverCorrectedIntoAnEmptyPolicy() {
        var spec = ServerConfig.init();
        assertNull(spec.policy());
        var source = CommentedConfig.inMemory();
        source.set("RestrictedModelList", List.of("path:global:*"));
        source.set("DefaultModelTexture", "original");
        spec.acceptConfig(source);
        var accepted = spec.policy();
        assertNotNull(accepted);
        assertTrue(accepted.restricted("global:a", ModelId.parse("a/m")));
        for (var invalid : List.of(1, "path:*", List.of("id:*", "path:"), List.of("id:*", 9), List.of("path:\udc00"))) {
            source.set("RestrictedModelList", invalid);
            source.set("DefaultModelTexture", "rejected");
            assertTrue(spec.isCorrect(source));
            assertEquals(0, spec.correct(source));
            spec.afterReload();
            assertSame(accepted, spec.policy());
            assertEquals(invalid, source.getRaw("RestrictedModelList"));
            assertEquals("original", ServerConfig.DEFAULT_MODEL_TEXTURE.get());
        }
        source.remove("RestrictedModelList");
        spec.afterReload();
        assertFalse(spec.policy().restricted("global:a", ModelId.parse("a/m")));
        assertEquals("rejected", ServerConfig.DEFAULT_MODEL_TEXTURE.get());
    }

    @Test
    void firstInvalidConfigKeepsPublicationSealedUntilRecovery() {
        var spec = ServerConfig.init();
        var source = CommentedConfig.inMemory();
        source.set("RestrictedModelList", List.of("broken"));
        spec.acceptConfig(source);
        assertNull(spec.policy());
        source.set("RestrictedModelList", List.of());
        spec.afterReload();
        assertNotNull(spec.policy());
        spec.acceptConfig(null);
        assertNull(spec.policy());
    }
}
