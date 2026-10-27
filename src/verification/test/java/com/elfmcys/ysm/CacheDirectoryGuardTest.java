package com.elfmcys.ysm;

import com.elfmcys.ysm.model.storage.CacheDirectoryGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CacheDirectoryGuardTest {
    @TempDir Path temp;

    private AssetPaths discover(Path root) {
        return AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of("YSM_HOME", root.toString()), AssetDirectoryPlatform.Kind.WINDOWS, null);
    }

    @Test void observationRejectsDirectoryTypeChangeAndProtectsOldFile() throws Exception {
        var guard = CacheDirectoryGuard.open(temp.resolve("cache"));
        var observed = guard.observe(guard.root().resolve("objects/item"), true);
        var old = Files.writeString(observed.target(), "old-valid");
        var moved = guard.root().resolve("retired");
        Files.move(old.getParent(), moved);
        Files.writeString(old.getParent(), "replacement-file");
        assertThrows(IOException.class, observed::verify);
        assertThrows(IOException.class, () -> observed.open(Set.of(StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)));
        assertEquals("old-valid", Files.readString(moved.resolve("item")));
        assertFalse(Files.exists(old));
    }

    @Test void observationRejectsReplacedRootAndEscapingTargets() throws Exception {
        var guard = CacheDirectoryGuard.open(temp.resolve("cache"));
        assertThrows(IOException.class, () -> guard.observe(temp.resolve("outside/file"), true));
        Files.move(guard.root(), temp.resolve("old-cache"));
        Files.writeString(guard.root(), "replacement-file");
        assertThrows(IOException.class, guard::verify);
    }

    @Test void legalRootJunctionPinsPhysicalTargetAndInternalJunctionCannotReachOriginal() throws Exception {
        requireWindows();
        var target = Files.createDirectories(temp.resolve("source-target"));
        var original = Files.writeString(target.resolve("model.zip"), "unique-original");
        var alias = temp.resolve("global-link");
        junction(alias, target);
        try {
            var hash = hash(original);
            var paths = discover(alias);
            assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
            assertEquals(alias, paths.global().orElseThrow().dataRoot());
            assertEquals(target.resolve("cache").toRealPath(), paths.global().orElseThrow().cacheRoot());
            assertEquals(hash, hash(original));
            assertEquals(target.toRealPath(), alias.toRealPath());
            var guard = paths.global().orElseThrow().cacheGuard();
            var internal = guard.root().resolve("objects");
            junction(internal, target);
            try {
                assertThrows(IOException.class, () -> guard.observe(internal.resolve("model.zip"), false));
                assertEquals(hash, hash(original));
            } finally { Files.delete(internal); }
            var inventory = inventory(target);
            assertEquals(java.util.List.of("auth/", "cache/", "custom/", "model.zip"), inventory);
            Files.delete(alias);
            var other = Files.createDirectories(temp.resolve("other-target"));
            junction(alias, other);
            assertEquals(target.resolve("cache").toRealPath(), guard.root());
            guard.verify();
            assertEquals(hash, hash(original));
        } finally { Files.deleteIfExists(alias); }
    }

    @Test void junctionAncestorIntoGameAndDanglingJunctionNeverCreateTargets() throws Exception {
        requireWindows();
        var game = Files.createDirectories(temp.resolve("game"));
        var original = Files.writeString(game.resolve("original.zip"), "game-original");
        var hash = hash(original);
        var ancestor = temp.resolve("outside-ancestor");
        junction(ancestor, game);
        try {
            assertFalse(discover(ancestor.resolve("absent/global")).globalEnabled());
            assertFalse(Files.exists(game.resolve("absent")));
            assertEquals(hash, hash(original));
            assertEquals(game.toRealPath(), ancestor.toRealPath());
        } finally { Files.delete(ancestor); }
        var target = Files.createDirectory(temp.resolve("missing-target"));
        var broken = temp.resolve("dangling");
        junction(broken, target);
        Files.delete(target);
        try {
            assertFalse(discover(broken).globalEnabled());
            assertFalse(Files.exists(target));
            assertTrue(Files.exists(broken, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        } finally { Files.delete(broken); }
        try (var contents = Files.list(game)) {
            assertEquals(java.util.List.of("original.zip"), contents.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test void symbolicLinksPreserveSourceAndRejectInternalCacheLinks() throws Exception {
        var source = Files.createDirectory(temp.resolve("source"));
        var original = Files.writeString(source.resolve("model.zip"), "symlink-original");
        var alias = temp.resolve("symlink");
        try {
            Files.createSymbolicLink(alias, source);
        } catch (IOException | UnsupportedOperationException denied) {
            assumeTrue(false, "Cannot create real symlinks: " + denied);
        }
        try {
            var before = hash(original);
            var paths = discover(alias);
            assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
            assertEquals(source, Files.readSymbolicLink(alias));
            var guard = paths.global().orElseThrow().cacheGuard();
            var internal = guard.root().resolve("objects");
            Files.createSymbolicLink(internal, source);
            try {
                assertThrows(IOException.class, () -> guard.observe(internal.resolve("model.zip"), false));
                assertEquals(before, hash(original));
            } finally { Files.delete(internal); }
            var inventory = inventory(source);
            assertEquals(java.util.List.of("auth/", "cache/", "custom/", "model.zip"), inventory);
            var missingTarget = temp.resolve("missing-link-target");
            var broken = temp.resolve("broken-link");
            Files.createSymbolicLink(broken, missingTarget);
            try {
                assertFalse(discover(broken).globalEnabled());
                assertFalse(Files.exists(missingTarget));
                assertEquals(missingTarget, Files.readSymbolicLink(broken));
            } finally { Files.delete(broken); }
            var game = Files.createDirectories(temp.resolve("game"));
            var gameLink = temp.resolve("game-symlink");
            Files.createSymbolicLink(gameLink, game);
            try {
                assertFalse(discover(gameLink.resolve("absent/global")).globalEnabled());
                assertFalse(Files.exists(game.resolve("absent")));
            } finally { Files.delete(gameLink); }
        } finally { Files.deleteIfExists(alias); }
    }

    @Test void realWindowsAclDenialDisablesGlobalWithoutChangingSource() throws Exception {
        requireWindows();
        var root = Files.createDirectory(temp.resolve("denied"));
        var original = Files.writeString(root.resolve("original.zip"), "acl-original");
        var hash = hash(original);
        var account = command("whoami.exe").strip();
        command("icacls.exe", root.toString(), "/deny", account + ":(OI)(CI)(WD,AD)");
        try {
            assertThrows(IOException.class, () -> Files.writeString(root.resolve("must-not-create"), "x"));
            var paths = discover(root);
            assertFalse(paths.globalEnabled());
            assertFalse(paths.diagnostics().isEmpty());
            assertEquals(hash, hash(original));
            assertFalse(Files.exists(root.resolve("custom")));
            assertEquals(java.util.List.of("original.zip"), inventory(root));
        } finally { command("icacls.exe", root.toString(), "/remove:d", account, "/T"); }
    }

    private static void requireWindows() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    }

    private static void junction(Path alias, Path target) throws Exception {
        command("cmd.exe", "/c", "mklink", "/J", alias.toString(), target.toString());
        assertEquals(target.toRealPath(), alias.toRealPath());
    }

    private static String command(String... args) throws Exception {
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static String hash(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path)));
    }

    private static java.util.List<String> inventory(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(p -> !p.equals(root)).map(p -> root.relativize(p).toString().replace('\\', '/')
                + (Files.isDirectory(p) ? "/" : "")).sorted().toList();
        }
    }
}
