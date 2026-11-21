package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConvertedObjectStoreTest {
    @TempDir
    static Path fixtureTemp;

    @TempDir
    Path temp;

    private static Path fixture;
    private static ModelFileIdentity identity;

    @BeforeAll
    static void createFixture() throws Exception {
        var manifest = ConvertedObjectStoreTest.class.getResource("/assets/ysm/builtin/default/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var vfs = new Directory(source)) {
            fixture = ModelParser.parseBuiltinDefault(vfs, Files.createDirectories(fixtureTemp.resolve("model")));
        }
        try (var channel = FileChannel.open(fixture, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
    }

    @Test
    void convertedStoreUsesIndependentInstances() throws Exception {
        var root = temp.resolve("cache");
        var store = store(root);
        var first = commit(store, new RawCompileResult(identity.modelId(), stagedFixture(root)),
            new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("fixture")));
        var second = commit(store, new RawCompileResult(identity.modelId(), stagedFixture(root)),
            new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("fixture")));
        assertEquals(identity, first.identity());
        assertEquals(identity, second.identity());
        assertNotEquals(first.instance(), second.instance());
        assertNotEquals(first.file(), second.file());
        assertTrue(first.file().startsWith(root.resolve("f/c")));
    }

    @Test
    void cacheProbeReadsOnlyExactIdentityPreamble() throws Exception {
        var cacheRoot = temp.resolve("cache");
        var target = container(cacheRoot, identity.modelId(), identity.containerId());
        Files.createDirectories(target.getParent());
        var bytes = Files.readAllBytes(fixture);
        bytes[bytes.length - 1] ^= 1;
        Files.write(target, bytes);
        var entry = entry(identity, target);

        assertTrue(store(cacheRoot).findIdentity(entry).isPresent());
        assertTrue(Files.exists(target));
    }

    @Test
    void tupleMismatchIsAMissWithoutDeletingTheExactObject() throws Exception {
        var cacheRoot = temp.resolve("cache");
        var expectedModel = ModelId.parse("different/model");
        var expectedContainer = different(identity.containerId());
        var target = container(cacheRoot, expectedModel, expectedContainer);
        var sibling = target.resolveSibling("keep.mxc");
        Files.createDirectories(target.getParent());
        Files.copy(fixture, target);
        Files.writeString(sibling, "keep");
        var entry = entry(new ModelFileIdentity(expectedModel, expectedContainer), target);
        var original = Files.readAllBytes(target);

        assertTrue(store(cacheRoot).findIdentity(entry).isEmpty());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertTrue(Files.exists(sibling));
    }

    @Test
    void rawReuseRequiresTheCapturedOriginVersion() throws Exception {
        var cacheRoot = temp.resolve("cache");
        var target = container(cacheRoot, identity.modelId(), identity.containerId());
        Files.createDirectories(target.getParent());
        Files.copy(fixture, target);
        var entry = entry(identity, target);
        var store = store(cacheRoot);
        assertTrue(store.findIdentity(entry, "3.0.0").isPresent());
        assertTrue(store.findIdentity(entry, "0.0.0").isEmpty());
        assertTrue(Files.exists(target));
    }

    @Test
    void atomicCommitFailureKeepsThePreviousObjectAndCleansItsTemporary() throws Exception {
        var cacheRoot = temp.resolve("cache");
        var target = container(cacheRoot, identity.modelId(), identity.containerId());
        Files.createDirectories(target.getParent());
        var previous = new byte[] {9, 8, 7};
        Files.write(target, previous);
        var commitReached = new java.util.concurrent.atomic.AtomicBoolean();
        var cache = new AtomicSharedCache(cacheRoot, replacement -> {
            commitReached.set(true);
            throw new IOException("injected atomic move failure");
        });
        var store = new ConvertedObjectStore(com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(cache));

        assertThrows(CompletionException.class,
            () -> commit(store, new RawCompileResult(identity.modelId(), stagedFixture(cacheRoot)),
                new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("fixture"))));

        assertTrue(commitReached.get(), "The failure must reach the atomic replacement seam");
        assertArrayEquals(previous, Files.readAllBytes(target));
        try (var files = Files.list(target.getParent())) {
            assertTrue(
                files.noneMatch(path -> path.getFileName().toString().startsWith(target.getFileName() + ".tmp-")));
        }
    }

    @Test
    void concurrentQualifiedWritersLeaveACompletelyVerifiedObject() throws Exception {
        var cacheRoot = temp.resolve("cache");
        var compiled = new RawCompileResult(identity.modelId(), stagedFixture(cacheRoot));
        var location = new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("fixture"));
        var firstStore = store(cacheRoot);
        var secondStore = store(cacheRoot);
        var first = CompletableFuture.supplyAsync(() -> commit(firstStore, compiled, location));
        var second = CompletableFuture.supplyAsync(() -> commit(secondStore, compiled, location));
        CompletableFuture.allOf(first, second).join();
        assertNotEquals(first.join().instance(), second.join().instance());
        for (var result : java.util.List.of(first.join(), second.join()))
            try (var verified = ManagedContainer.verifyFile(result.file())) {
                assertEquals(identity, verified.identity());
            }
    }

    @Test
    void missingStagedBackingDoesNotPublishAConvertedObject() {
        var cacheRoot = temp.resolve("cache");
        var target = container(cacheRoot, identity.modelId(), identity.containerId());

        assertThrows(CompletionException.class,
            () -> commit(store(cacheRoot),
                new RawCompileResult(identity.modelId(), cacheRoot.resolve("converted/.tmp/missing.ysm")),
                new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("missing"))));
        assertTrue(Files.notExists(target));
    }

    private static ConvertedObjectStore store(Path root) {
        return new ConvertedObjectStore(
            com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new AtomicSharedCache(root)));
    }

    private static Path stagedFixture(Path root) {
        return fixture;
    }

    private static Path container(Path cacheRoot, ModelId modelId, Hash256 containerId) {
        return cacheRoot.resolve("f/c/0000000000000001");
    }

    private static ConvertedSourceIndex entry(ModelFileIdentity expected, Path file) throws IOException {
        try (var metadata = ManagedContainer.verifyFile(fixture)) {
            return new ConvertedSourceIndex(expected, "custom/raw", "test-version",
                new com.elfmcys.ysm.natives.storage.CacheStorageTypes.Location(1, java.util.UUID.randomUUID()), file,
                com.elfmcys.ysm.buffer.ArrayBuffer.move(new byte[] {1}).shareArray(),
                metadata.metadataPrefix().orElseThrow().shareArray());
        }
    }

    private static Hash256 different(Hash256 value) {
        var bytes = value.bytes();
        bytes[0] ^= 1;
        return new Hash256(bytes);
    }

    private static ConvertedObjectStore.Stored commit(ConvertedObjectStore store, RawCompileResult compiled,
        CatalogLocation location) {
        try (var staging = store.stage()) {
            staging.file().writeBytes(Files.readAllBytes(compiled.stagedContainer()));
            return store.commit(new RawCompileResult(compiled.modelHash(), staging.path()), location, staging);
        } catch (IOException failure) {
            throw new CompletionException(failure);
        }
    }
}
