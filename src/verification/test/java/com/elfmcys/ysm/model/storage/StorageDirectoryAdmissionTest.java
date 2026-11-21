package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StorageDirectoryAdmissionTest {
    @TempDir
    Path temp;

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void warmRawReloadDoesNotOpenLockedOrdinarySourcePayload() throws Exception {
        var source = Path.of(java.util.Objects
            .requireNonNull(getClass().getResource("/assets/ysm/builtin/misc/1_alex/ysm.json")).toURI()).getParent();
        var data = Files.createDirectories(temp.resolve("data"));
        var raw = data.resolve("alex");
        try (var paths = Files.walk(source)) {
            for (var file : paths.toList()) {
                var target = raw.resolve(source.relativize(file));
                if (Files.isDirectory(file))
                    Files.createDirectories(target);
                else
                    Files.copy(file, target);
            }
        }
        var root = new com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity(CatalogRootKind.MODELS, data);
        var key = new com.elfmcys.ysm.model.catalog.source.ModelSourceKey(root, new ModelPath("alex"),
            com.elfmcys.ysm.model.catalog.source.ModelSourceKind.CURRENT_RAW_DIRECTORY);
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var scope = ConvertedScope.of(data, "v1");
            var index = new ConvertedSourceIndexStore(scope, cache);
            var resolver = new com.elfmcys.ysm.model.catalog.source.ModelSourceResolver(
                new com.elfmcys.ysm.model.catalog.RawModelImporter(
                    com.elfmcys.ysm.format.parser.DefaultAnimationFilter.keepAll()),
                index, new ConvertedObjectStore(cache));
            var observation = com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery.observe(key, raw);
            var cold = resolver.resolve(observation).convertedIndexEntry().orElseThrow();
            final Path payload;
            try (var members = Files.walk(raw)) {
                payload = members.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("ysm.json")).findFirst().orElseThrow();
            }
            try (var channel = java.nio.channels.FileChannel.open(payload, java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE); var locked = channel.lock()) {
                var warm = resolver.resolve(com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery.observe(key, raw))
                    .convertedIndexEntry().orElseThrow();
                assertEquals(cold.instance(), warm.instance());
            }
            Files.writeString(raw.resolve("new-member"), "");
            var refreshed =
                resolver.resolve(com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery.observe(key, raw))
                    .convertedIndexEntry().orElseThrow();
            assertNotEquals(cold.instance(), refreshed.instance());
        }
    }

    @Test
    void persistedDirectProofDefersUnreadDamageToFullVerification() throws Exception {
        var source = Files.write(temp.resolve("source.mxc"), StorageContainerFixture.bytes());
        var location = new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("source.mxc"));
        var plain = ManagedContainer.openDirect(source, location);
        final Path exported;
        try (var ignored = plain.representation()) {
            exported = ModelExporter.export(plain, TestPreviews.blank(), temp.resolve("direct.mxc"), null);
        }
        int manifestOffset;
        try (var first = new CacheRegion(temp.resolve("cache"))) {
            var admitted = first.directories().open(exported, location, null);
            try (var ignored = admitted.representation()) {
                manifestOffset = admitted.modelFile().getFileView().getAssetView()
                    .getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME).offset();
            }
        }
        var bytes = Files.readAllBytes(exported);
        bytes[manifestOffset] ^= 1;
        bytes[bytes.length - 1] ^= 1;
        Files.write(exported, bytes);
        try (var reopened = new CacheRegion(temp.resolve("cache"))) {
            var hit = reopened.directories().open(exported, location, null);
            try (var ignored = hit.representation()) {
                assertTrue(hit.representation().hasMetadataPrefix());
            }
        }
        assertThrows(java.io.IOException.class, () -> ManagedContainer.verifyFile(exported));
        Files.write(exported, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        try (var reopened = new CacheRegion(temp.resolve("cache"))) {
            var hit = reopened.directories().open(exported, location, null);
            try (var ignored = hit.representation()) {
                assertTrue(hit.representation().hasMetadataPrefix());
            }
        }
        assertThrows(java.io.IOException.class, () -> ManagedContainer.verifyFile(exported));
    }

    @Test
    void runtimeContentFeedbackRevokesOnlyItsConvertedInstanceAndNeverDeletesBytes() throws Exception {
        try (var work = new DatabaseWork(); var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            cache.databaseWork(work);
            var scope = ConvertedScope.of(temp.resolve("data"), "v1");
            var index = new ConvertedSourceIndexStore(scope, cache);
            var old = StorageContainerFixture.produce(scope, cache, "custom/model");
            index.publish(old);
            try (var consumers = new CacheRootCoordinator(cache.root()); var region = new CacheRegion(cache.root())) {
                consumers.register();
                ContentStorageBinding.builtin(region).withConverted(old, null).reportCorruption(failure -> {
                    throw new AssertionError(failure);
                });
                assertTrue(index.find("custom/model").isPresent());
                var binding = ContentStorageBinding.builtin(region).withConverted(old, index);
                var entry = new com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry(old.identity(),
                    new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("model")), old.file(), binding, old);
                var content = ManagedContainer.openIndexed(entry);
                try (var ignored = content.representation()) {
                    var payload = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
                        .filter(chunk -> chunk.offset() >= content.representation().metadataPrefixSize()).findFirst()
                        .orElseThrow();
                    var bytes = Files.readAllBytes(old.file());
                    bytes[payload.offset()] ^= 1;
                    Files.write(old.file(), bytes);
                    var runtime = new RuntimeContentStore(failure -> {
                    });
                    var exact = runtime.exact(content);
                    assertThrows(AssetLoadException.class,
                        () -> exact.chunks().readStoredVerified(payload, BufferType.ARRAY));
                    runtime.tick();
                    cache.catalogIndex().submit((db, cancelled) -> db.pageSources("barrier", "", cancelled))
                        .toCompletableFuture().join();
                    assertTrue(index.find("custom/model").isEmpty());
                    index.replaceScopes(Set.of("custom"), List.of(old));
                    assertTrue(index.find("custom/model").isEmpty());
                    assertTrue(Files.isRegularFile(old.file()));
                }
            }
        }
    }
}
