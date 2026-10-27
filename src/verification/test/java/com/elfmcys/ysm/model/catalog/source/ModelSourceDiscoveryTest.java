package com.elfmcys.ysm.model.catalog.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ModelSourceDiscoveryTest {
    @TempDir
    Path temp;

    @Test
    void discoversModernLegacyAndArchiveSourcesWithoutDescendingIntoModels() throws Exception {
        var modern = Files.createDirectories(temp.resolve("category/modern"));
        Files.writeString(modern.resolve("ysm.json"), "{}");
        Files.createDirectories(modern.resolve("models"));
        Files.writeString(modern.resolve("models/main.json"), "not another model");

        var legacy = Files.createDirectories(temp.resolve("legacy"));
        Files.writeString(legacy.resolve("main.json"), "{}");
        Files.writeString(temp.resolve("packed.ysm"), "archive");

        var result = ModelSourceDiscovery.discover(temp);

        assertEquals(List.of("category/modern", "legacy", "packed.ysm"),
            result.models().stream().map(this::relative).toList());
        assertFalse(result.packDirectories().stream().map(this::relative)
            .anyMatch(path -> path.startsWith("category/modern/")));
        assertEquals(List.of(), result.failures());
    }

    @Test
    void classifiesMxcAndHistoricalYsmHeadersWithoutGuessing() throws Exception {
        Files.writeString(temp.resolve("current.mxc"), "validated when opened");
        Files.write(temp.resolve("v1.ysm"), rawHeader(1));
        Files.write(temp.resolve("v2.ysm"), rawHeader(2));
        Files.write(temp.resolve("v3.ysm"), v3Header());
        Files.writeString(temp.resolve("unknown.ysm"), "unknown header");

        var state = assertInstanceOf(RootInventoryState.Complete.class,
            ModelSourceDiscovery.inventory(new ModelCatalogSource(CatalogRootKind.CUSTOM, temp, false)));
        Map<String, ModelSourceKind> kinds = state.sources().values().stream()
            .collect(Collectors.toMap(value -> value.key().relativePath().value(), value -> value.key().sourceKind()));

        assertEquals(ModelSourceKind.DIRECT_CONTAINER, kinds.get("current.mxc"));
        assertEquals(ModelSourceKind.CURRENT_RAW_ARCHIVE, kinds.get("v1.ysm"));
        assertEquals(ModelSourceKind.CURRENT_RAW_ARCHIVE, kinds.get("v2.ysm"));
        assertEquals(ModelSourceKind.LEGACY_ARCHIVE, kinds.get("v3.ysm"));
        assertEquals(ModelSourceKind.UNSUPPORTED_YSM, kinds.get("unknown.ysm"));
    }

    private static byte[] rawHeader(int version) {
        return ByteBuffer.allocate(8).put(new byte[] {'Y', 'S', 'G', 'P'}).putInt(version).array();
    }

    @Test
    void logicalAliasesRemainCandidatesAndOnlyAncestorCyclesFail() throws Exception {
        var root = Files.createDirectory(temp.resolve("custom"));
        var target = Files.createDirectories(temp.resolve("physical/nested"));
        Files.writeString(target.resolve("hero.mxc"), "content validated by resolver");
        Files.createSymbolicLink(root.resolve("team"), target.getParent());
        Files.createSymbolicLink(root.resolve("other"), Path.of("team"));
        Files.createSymbolicLink(target.resolve("back"), target.getParent());

        var state = assertInstanceOf(RootInventoryState.Incomplete.class,
            ModelSourceDiscovery.inventory(new ModelCatalogSource(CatalogRootKind.CUSTOM, root, false)));

        assertEquals(List.of("other/nested/hero.mxc", "team/nested/hero.mxc"),
            state.sources().keySet().stream().map(key -> key.relativePath().value()).toList());
        assertEquals(2, state.errors().size());
        assertTrue(state.errors().stream().allMatch(error -> error.message().equals("Source is inaccessible")
            && error.source().startsWith("local:custom/") && error.detail().contains("cycle")));
        assertTrue(state.sources().values().stream()
            .allMatch(value -> value.absolutePath().equals(target.resolve("hero.mxc"))));
        assertEquals(root, state.root().configuredAbsoluteRoot());
    }

    @Test
    void linkedRootsUseBothCreationPoliciesAndNeverRepairBrokenRoots() throws Exception {
        var target = Files.createDirectory(temp.resolve("external"));
        Files.writeString(target.resolve("actual.zip"), "archive");
        for (var create : new boolean[] {false, true}) {
            var root = Files.createSymbolicLink(temp.resolve("linked-" + create), target);
            var state = assertInstanceOf(RootInventoryState.Complete.class,
                ModelSourceDiscovery.inventory(new ModelCatalogSource(CatalogRootKind.AUTH, root, create)));
            assertEquals("actual.zip", state.sources().keySet().iterator().next().relativePath().value());
            assertEquals(root, state.root().configuredAbsoluteRoot());
            var missing = temp.resolve("absent-target-" + create);
            var broken = Files.createSymbolicLink(temp.resolve("broken-" + create), missing);
            assertInstanceOf(RootInventoryState.Incomplete.class,
                ModelSourceDiscovery.inventory(new ModelCatalogSource(CatalogRootKind.AUTH, broken, create)));
            assertTrue(Files.isSymbolicLink(broken));
            assertFalse(Files.exists(missing));
        }
        var absent = temp.resolve("absent-auth");
        assertInstanceOf(RootInventoryState.Complete.class,
            ModelSourceDiscovery.inventory(new ModelCatalogSource(CatalogRootKind.AUTH, absent, false)));
        assertFalse(Files.exists(absent));
        assertInstanceOf(RootInventoryState.Complete.class,
            ModelSourceDiscovery.inventory(new ModelCatalogSource(CatalogRootKind.AUTH, absent, true)));
        assertTrue(Files.isDirectory(absent));
    }

    @Test
    void fileKindUsesTheLogicalNameAndRetargetingChangesOnlyTheObservation() throws Exception {
        var root = Files.createDirectory(temp.resolve("custom"));
        var first = Files.writeString(temp.resolve("first.data"), "first");
        var second = Files.writeString(temp.resolve("second.data"), "second");
        var link = Files.createSymbolicLink(root.resolve("alias.mxc"), first);
        var source = new ModelCatalogSource(CatalogRootKind.CUSTOM, root, false);
        var before = ModelSourceDiscovery.inventory(source).sources().values().iterator().next();
        assertEquals(ModelSourceKind.DIRECT_CONTAINER, before.key().sourceKind());
        assertEquals("alias.mxc", before.key().relativePath().value());
        Files.delete(link);
        Files.createSymbolicLink(link, second);
        var after = ModelSourceDiscovery.observe(before.key(), link);
        assertEquals(before.key(), after.key());
        assertNotEquals(before, after);
        assertEquals(second, after.absolutePath());
    }

    private static byte[] v3Header() {
        return ByteBuffer.allocate(7 + 1 + Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[] {(byte)0xEF, (byte)0xBB, (byte)0xBF, 'Y', 'S', 'G', 'P'}).put((byte)0).putInt(3).array();
    }

    private String relative(Path path) {
        return temp.relativize(path).toString().replace('\\', '/');
    }
}
