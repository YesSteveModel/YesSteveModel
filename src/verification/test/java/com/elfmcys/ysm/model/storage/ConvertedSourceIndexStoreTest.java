package com.elfmcys.ysm.model.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConvertedSourceIndexStoreTest {
    @TempDir
    Path temp;

    @Test
    void switchingToDirectKeepsTheSourceIdentityButDropsItsOldConvertedRelation() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var store = new ConvertedSourceIndexStore(ConvertedScope.of(temp, "v1"), cache);
            var previous = store.publish(StorageContainerFixture.produce(store.scope(), cache, "models/model"));
            var resolver = new com.elfmcys.ysm.model.catalog.source.ModelSourceResolver(
                new com.elfmcys.ysm.model.catalog.RawModelImporter(
                    com.elfmcys.ysm.format.parser.DefaultAnimationFilter.keepAll()),
                store, new ConvertedObjectStore(cache));
            var location = new com.elfmcys.ysm.model.catalog.source.CatalogLocation(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS,
                new com.elfmcys.ysm.model.domain.ModelPath("model"));
            resolver.replaceIndex(Set.of("models"), List.of(),
                List.of(new com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry(previous.identity(), location,
                    temp.resolve("direct.mxc"))));
            assertTrue(store.find("models/model").isEmpty());
            var source = StorageIndex
                .require(cache.catalog().read((db, token) -> db.pageSources(store.key(), "", token))).rows().get(0);
            assertEquals(previous.identity(), source.identity());
            assertTrue(source.converted().isEmpty());
        }
    }

    @Test
    void metadataOnlyDirectAndBuiltinReferencesSurviveOtherScopeScansAndConvertedRevocation() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var store = new ConvertedSourceIndexStore(ConvertedScope.of(temp.resolve("data"), "v1"), cache);
            var other = new ConvertedSourceIndexStore(ConvertedScope.of(temp.resolve("other"), "v1"), cache);
            var converted = store.publish(StorageContainerFixture.produce(store.scope(), cache, "models/raw"));
            var identity = converted.identity();
            store.replaceScopes(Set.of("models", "builtin"), List.of(converted),
                java.util.Map.of("models/raw", identity, "models/direct", identity, "builtin/default", identity));
            other.replaceScopes(Set.of("models"), List.of(), java.util.Map.of("models/other", identity));
            store.revoke(converted.location());
            store.replaceScopes(Set.of("models"), List.of(), java.util.Map.of());
            var rows = StorageIndex.require(cache.catalog().read((db, token) -> db.pageSources(store.key(), "", token)))
                .rows();
            assertEquals(List.of("builtin/default"),
                rows.stream().map(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Source::source).toList());
            var otherRows = StorageIndex
                .require(cache.catalog().read((db, token) -> db.pageSources(other.key(), "", token))).rows();
            assertEquals(List.of("models/other"),
                otherRows.stream().map(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Source::source).toList());
            var references =
                StorageIndex.require(cache.catalog().read((db, token) -> db.references(identity.containerId(), token)))
                    .rows().get(0);
            assertEquals(com.elfmcys.ysm.natives.storage.CacheStorageTypes.ReferenceState.REFERENCED,
                references.state());
            assertTrue(references.incompleteDomains() > 0, "a complete scope scan cannot prove root-wide coverage");
        }
    }

    @Test
    void lateOldInstanceFeedbackCannotRevokeReplacement() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var scope = ConvertedScope.of(temp.resolve("data"), "v1");
            var store = new ConvertedSourceIndexStore(scope, cache);
            var first = store.publish(StorageContainerFixture.produce(scope, cache, "custom/model"));
            var fresh = store.publish(StorageContainerFixture.produce(scope, cache, "custom/model"));
            store.revoke(first.location());
            assertEquals(fresh, store.find("custom/model").orElseThrow());
            assertTrue(Files.exists(first.file()));
        }
    }

    @Test
    void exactInstancesReopenAndScanNeverRepublishesAnOldSnapshot() throws Exception {
        try (var work = new DatabaseWork()) {
            var cache =
                com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new AtomicSharedCache(temp.resolve("cache")));
            cache.databaseWork(work);
            var scope = ConvertedScope.of(temp.resolve("data"), "v1");
            var store = new ConvertedSourceIndexStore(scope, cache);
            var old = StorageContainerFixture.produce(scope, cache, "custom/model");
            store.publish(old);
            var reopened = new ConvertedSourceIndexStore(scope, cache).find("custom/model").orElseThrow();
            assertEquals(old.instance(), reopened.instance());
            assertEquals(old.file(), reopened.file());
            try (var verified = ManagedContainer.readConvertedMetadata(reopened, cache)) {
                assertEquals(old.identity(), verified.identity());
            }
            store.revoke(old.location());
            store.replaceScopes(Set.of("custom"), List.of(old));
            assertTrue(store.find("custom/model").isEmpty());
            assertTrue(Files.exists(old.file()));
            var replacement = StorageContainerFixture.produce(scope, cache, "custom/model");
            assertEquals(old.identity(), replacement.identity());
            assertNotEquals(old.instance(), replacement.instance());
            store.publish(replacement);
            store.revoke(old.location());
            assertEquals(replacement.instance(), store.find("custom/model").orElseThrow().instance());
            assertEquals(cache.root(), store.root());
            assertFalse(Files.exists(cache.root().resolve("index.bin")));
        }
    }

    @Test
    void completeNamespacesRetireOnlyTheirObsoleteRelationsAndProfilesStayIsolated() throws Exception {
        try (var work = new DatabaseWork()) {
            var cache =
                com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new AtomicSharedCache(temp.resolve("cache")));
            cache.databaseWork(work);
            var scope = ConvertedScope.of(temp.resolve("data"), "v1");
            var store = new ConvertedSourceIndexStore(scope, cache);
            var custom = StorageContainerFixture.produce(scope, cache, "custom/model");
            var auth = StorageContainerFixture.produce(scope, cache, "auth/model");
            store.publish(custom);
            store.publish(auth);
            var other = new ConvertedSourceIndexStore(ConvertedScope.of(temp.resolve("other"), "v1"), cache);
            assertTrue(other.read().isEmpty());
            assertTrue(
                new ConvertedSourceIndexStore(ConvertedScope.of(temp.resolve("data"), "v2"), cache).read().isEmpty());
            store.replaceScopes(Set.of("custom"), List.of());
            assertTrue(store.find("custom/model").isEmpty());
            assertEquals(auth.instance(), store.find("auth/model").orElseThrow().instance());
            assertTrue(Files.exists(custom.file()));
        }
    }

    @Test
    void keysetPagesObserveEveryRelationBeyondTheRowAndByteBound() throws Exception {
        try (var work = new DatabaseWork()) {
            var cache =
                com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new AtomicSharedCache(temp.resolve("cache")));
            cache.databaseWork(work);
            var scope = ConvertedScope.of(temp.resolve("data"), "v1");
            var store = new ConvertedSourceIndexStore(scope, cache);
            var fixture = StorageContainerFixture.produce(scope, cache, "custom/fixture");
            for (var n = 0; n < 70; n++) {
                // Different relations may reference the same already verified immutable instance.
                var entry = new ConvertedSourceIndex(fixture.identity(), "custom/" + String.format("%03d", n), "v1",
                    fixture.location(), fixture.file(), fixture.observation(), fixture.metadata());
                store.publish(entry);
            }
            assertEquals(70, store.read().size());
            store.replaceScopes(Set.of("custom"), List.of());
            assertTrue(store.read().isEmpty());
        }
    }

    @Test
    void directSourceScanCrossesTheBatchBoundary() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var store = new ConvertedSourceIndexStore(ConvertedScope.of(temp, "v1"), cache);
            var identity = StorageContainerFixture.produce(store.scope(), cache, "models/fixture").identity();
            var references = new LinkedHashMap<String, com.elfmcys.ysm.model.domain.ModelFileIdentity>();
            for (int i = 0; i <= 64; i++)
                references.put("models/" + String.format("%02d", i), identity);

            store.replaceScopes(Set.of("models"), List.of(), references);
            var first = StorageIndex
                .require(cache.catalog().read((db, token) -> db.pageSources(store.key(), "", token))).rows();
            assertEquals(64, first.size());
            var last = StorageIndex
                .require(cache.catalog().read((db, token) -> db.pageSources(store.key(), "models/63", token))).rows();
            assertEquals(List.of("models/64"),
                last.stream().map(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Source::source).toList());
            assertEquals(identity, last.get(0).identity());
        }
    }

    @Test
    void incompatibleDatabaseIsPreservedAndNeverReportedAsMissing() throws Exception {
        var root = Files.createDirectories(temp.resolve("cache"));
        var file =
            Files.write(Files.createDirectories(root.resolve("f")).resolve("catalog.sqlite"), new byte[] {1, 2, 3});
        try (var work = new DatabaseWork()) {
            var cache = com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new AtomicSharedCache(root));
            cache.databaseWork(work);
            var store = new ConvertedSourceIndexStore(ConvertedScope.of(temp.resolve("data"), "v1"), cache);
            assertThrows(java.io.IOException.class, store::read);
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(file));
        }
    }
}
