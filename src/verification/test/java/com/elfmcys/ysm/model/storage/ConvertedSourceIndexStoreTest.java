package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConvertedSourceIndexStoreTest {
    @TempDir
    Path temp;

    @Test
    void exactMappingRoundTripsAsOneAtomicIndex() throws Exception {
        var store = store("1.2.3");
        var custom = entry(1, 11, "custom/group/model", "1.2.3");
        var auth = entry(2, 12, "auth/model", "1.2.3");

        store.replace(List.of(auth, custom));

        assertEquals(custom, store.find("custom/group/model").orElseThrow());
        assertEquals(auth, store.find("auth/model").orElseThrow());
        assertEquals(Set.of("custom/group/model", "auth/model"), store.read().keySet());
        assertEquals(store.scope().root().resolve("index.bin"), store.path());
    }

    @Test
    void indexHeaderRejectsADifferentDataScopeWithoutMutatingIt() throws Exception {
        var first = store("1.2.3");
        first.replace(List.of(entry(1, 11, "custom/model", "1.2.3")));
        var bytes = Files.readAllBytes(first.path());
        assertArrayEquals("YSMCIDX2".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOf(bytes, 8));
        var foreignScope = ConvertedScope.of(temp.resolve("different-data"), temp.resolve("cache"), "1.2.3");
        var foreign = new ConvertedSourceIndexStore(foreignScope, new AtomicSharedCache(temp.resolve("cache")));
        Files.createDirectories(foreign.path().getParent());
        Files.write(foreign.path(), bytes);
        assertTrue(foreign.read().isEmpty());
        assertArrayEquals(bytes, Files.readAllBytes(foreign.path()));
        assertEquals(1, first.read().size());
    }

    @Test
    void scopedReplacementPreservesOtherManagementNamespaces() throws Exception {
        var store = store("1.2.3");
        var custom = entry(1, 11, "custom/old", "1.2.3");
        var auth = entry(2, 12, "auth/kept", "1.2.3");
        var replacement = entry(3, 13, "custom/new", "1.2.3");
        store.replace(List.of(custom, auth));

        store.replaceScopes(Set.of("custom"), List.of(replacement));

        assertTrue(store.find("custom/old").isEmpty());
        assertEquals(replacement, store.find("custom/new").orElseThrow());
        assertEquals(auth, store.find("auth/kept").orElseThrow());
    }

    @Test
    void rejectsDuplicatePathsAndForeignVersions() {
        var store = store("1.2.3");
        assertThrows(IllegalArgumentException.class,
            () -> store.replace(List.of(entry(1, 11, "custom/model", "1.2.3"), entry(2, 12, "custom/model", "1.2.3"))));
        assertThrows(IllegalArgumentException.class,
            () -> store.replace(List.of(entry(1, 11, "custom/model", "other"))));
    }

    @Test
    void malformedExactIndexIsPreservedAndVersionsHaveIndependentMappings() throws Exception {
        var first = store("1.2.3");
        first.replace(List.of(entry(1, 11, "custom/model", "1.2.3")));
        assertTrue(store("2.0.0").read().isEmpty());
        assertTrue(Files.exists(first.path()));

        Files.write(first.path(), new byte[] {1, 2, 3});

        assertTrue(first.read().isEmpty());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(first.path()));
    }

    @Test
    void foreignEntryVersionIsAMissAndTheIndexIsNotDeleted() throws Exception {
        var store = store("1.2.3");
        store.replace(List.of(entry(1, 11, "custom/model", "1.2.3")));
        var bytes = Files.readAllBytes(store.path());
        bytes[bytes.length - 1] = '4';
        Files.write(store.path(), bytes);
        assertTrue(store.read().isEmpty());
        assertArrayEquals(bytes, Files.readAllBytes(store.path()));
    }

    private ConvertedSourceIndexStore store(String version) {
        return new ConvertedSourceIndexStore(temp.resolve("cache"), version);
    }

    private static ConvertedSourceIndex entry(int modelSeed, int containerSeed, String path, String version) {
        return new ConvertedSourceIndex(new ModelFileIdentity(hash(modelSeed), hash(containerSeed)), path, version);
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }
}
