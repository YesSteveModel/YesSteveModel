package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.AssetPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContentStorageBindingTest {
    @TempDir
    Path temp;

    @Test
    void groupedFallbackKeepsLocalAndRemoteCapabilitiesDistinctWithoutEagerFiles() throws Exception {
        var paths = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of("YSM_HOME", ""), AssetDirectoryPlatform.Kind.WINDOWS, null);
        assertFalse(paths.globalEnabled());
        try (var storage = ModelStorageInfra.open(paths)) {
            assertSame(storage.local(), storage.remote());
            assertFalse(Files.exists(paths.gameModelsRoot().resolve("preferences-c2.sqlite")));
            assertFalse(Files.exists(storage.local().root().resolve("catalog-c3.sqlite")));
            assertFalse(Files.exists(storage.local().root().resolve("remote-c2.sqlite")));
            var local = ContentStorageBinding.builtin(storage.local());
            var remote = ContentStorageBinding.remote(storage.remote());
            assertSame(local.artifacts(), remote.artifacts());
            var kind = com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind.PREVIEW;
            var localFile = TestCacheFiles.path(storage.local().root(), kind, "same", false);
            var remoteFile = TestCacheFiles.path(storage.remote().root(), kind, "same", true);
            assertNotEquals(localFile, remoteFile);
            Files.write(localFile, new byte[] {1});
            Files.write(remoteFile, new byte[] {2});
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(localFile));
            assertArrayEquals(new byte[] {2}, Files.readAllBytes(remoteFile));
            PlayerPreferencesTest.committed(storage.preferences(),
                storage.preferences().writeSelection(PlayerPreferencesTest.U, new PlayerPreferences.LocalSelection(
                    com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, "")));
            assertTrue(Files.isRegularFile(paths.gameModelsRoot().resolve("preferences-c2.sqlite")));
        }
    }

}
