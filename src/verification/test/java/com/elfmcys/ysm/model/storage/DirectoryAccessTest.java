package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.util.DirectoryAccess;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryAccessTest {
    @TempDir
    Path temp;

    @Test
    void snapshotsReopenAfterCloseAndRejectChangesInAnyAncestor() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        var source = Files.createDirectories(temp.resolve("source/child/leaf"));
        Files.writeString(source.resolve("original"), "source-bytes");
        var branch = Files.createDirectories(root.resolve("branch/child/leaf"));
        DirectoryAccess.Snapshot snapshot;
        try (var directory = DirectoryAccess.open(root);
            var parent = directory.directory(Path.of("branch/child/leaf"), false)) {
            snapshot = parent.snapshot();
        }
        try (var first = snapshot.open(); var second = snapshot.open()) {
            first.close();
            second.verify();
            assertThrows(java.nio.channels.ClosedChannelException.class, first::verify);
            assertEquals(branch, second.label());
        }
        Files.move(root.resolve("branch"), root.resolve("retired"));
        Files.createSymbolicLink(root.resolve("branch"), source.getParent().getParent());
        try {
            assertThrows(IOException.class, snapshot::open);
            assertEquals("source-bytes", Files.readString(source.resolve("original")));
        } finally { Files.delete(root.resolve("branch")); }
    }

    @Test
    void bulkDescentRejectsLinksBeforeCreatingMissingTails() throws Exception {
        for (var junction : System.getProperty("os.name").startsWith("Windows") ? List.of(false, true)
            : List.of(false)) {
            var fixture = Files.createDirectory(temp.resolve("fixture-" + junction));
            var root = Files.createDirectory(fixture.resolve("cache"));
            var source = Files.createDirectories(fixture.resolve("source/existing"));
            link(root.resolve("linked"), source.getParent(), junction);
            try (var directory = DirectoryAccess.open(root)) {
                assertThrows(IOException.class,
                    () -> directory.directory(Path.of("linked/existing/missing/tail"), true));
                assertFalse(Files.exists(source.resolve("missing")));
            } finally { Files.delete(root.resolve("linked")); }
        }
    }

    @Test
    void bulkDescentValidatesAllSegmentsBeforeCreatingDirectories() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        try (var directory = DirectoryAccess.open(root)) {
            assertThrows(IOException.class, () -> directory.directory(Path.of("created/../outside"), true));
            assertThrows(IOException.class, () -> directory.directory(temp.resolve("outside"), true));
            assertFalse(Files.exists(root.resolve("created")));
            try (var created = directory.directory(Path.of("a/b/c"), true);
                var reopened = directory.directory(Path.of("a/b/c"), false)) {
                assertEquals(created.identity(), reopened.identity());
                assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> directory.createDirectory("a"));
            }
        }
    }

    @Test
    void snapshotRejectsAReplacedAncestorEvenWhenTheLeafIdentitySurvives() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.readAttributes(root,
            java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null);
        var branch = Files.createDirectories(root.resolve("branch/leaf"));
        DirectoryAccess.Snapshot snapshot;
        try (var directory = DirectoryAccess.open(root);
            var leaf = directory.directory(Path.of("branch/leaf"), false)) {
            snapshot = leaf.snapshot();
        }
        Files.move(branch.getParent(), root.resolve("retired"));
        Files.createDirectory(branch.getParent());
        Files.move(root.resolve("retired/leaf"), branch);
        assertThrows(IOException.class, snapshot::open);
    }

    @Test
    void directoryAndLeafLinksAreRejected() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        var source = Files.createDirectory(temp.resolve("source"));
        var original = Files.writeString(source.resolve("original"), "source-bytes");
        Files.writeString(root.resolve("regular"), "regular-bytes");
        Files.createSymbolicLink(root.resolve("directory-link"), source);
        Files.createSymbolicLink(root.resolve("file-link"), original);
        assertThrows(IOException.class, () -> DirectoryAccess.open(root.resolve("regular")));
        assertThrows(IOException.class, () -> DirectoryAccess.open(root.resolve("directory-link")));
        try (var directory = DirectoryAccess.open(root)) {
            assertThrows(IOException.class, () -> directory.directory("regular", false));
            assertThrows(IOException.class, () -> directory.directory("directory-link", false));
            assertThrows(IOException.class, () -> directory.file("file-link", true, false, false));
        }
        assertEquals("source-bytes", Files.readString(original));
        try (var files = Files.list(source)) {
            assertEquals(List.of("original"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void returnedChannelsUseJdkLockOwnershipAndReleaseOnClose() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        try (var directory = DirectoryAccess.open(root);
            var first = directory.file("lock", true, true, true);
            var second = directory.file("lock", true, false, false)) {
            var owned = first.lock();
            assertThrows(java.nio.channels.OverlappingFileLockException.class, second::tryLock);
            first.close();
            assertFalse(owned.isValid());
            try (var next = second.tryLock()) { assertNotNull(next); }
        }
    }

    @Test
    void observedDirectoryReplacementRejectsCreateMoveAndCleanup() throws Exception {
        for (var junction : System.getProperty("os.name").startsWith("Windows") ? List.of(false, true)
            : List.of(false)) {
            var fixture = Files.createDirectory(temp.resolve("fixture-" + junction));
            var root = Files.createDirectory(fixture.resolve("cache"));
            var source = Files.createDirectory(fixture.resolve("source"));
            Files.writeString(source.resolve("original"), "source-bytes");
            try (var held = DirectoryAccess.open(root)) {
                Object staged;
                try (var file = held.file("staging", true, true, true)) {
                    staged = held.fileIdentity("staging");
                    file.write(ByteBuffer.wrap("new-valid".getBytes()));
                }
                Files.move(root, fixture.resolve("retired"));
                link(root, source, junction);
                try {
                    assertThrows(IOException.class, held::identity);
                    assertThrows(IOException.class, () -> held.file("new", true, true, true));
                    assertThrows(IOException.class, () -> held.move("staging", staged, held, "target"));
                    assertThrows(IOException.class, () -> held.delete("staging", staged, false));
                    assertEquals("source-bytes", Files.readString(source.resolve("original")));
                    try (var names = Files.list(source)) {
                        assertEquals(List.of("original"), names.map(p -> p.getFileName().toString()).toList());
                    }
                } finally { Files.delete(root); }
            }
        }
    }

    @Test
    void borrowedFileCloseCannotEndTheOwnersValidationAndCommit() throws Exception {
        var root = temp.resolve("cache");
        var target = root.resolve("object");
        new AtomicSharedCache(root).replaceValidated("test", "key", target,
            file -> new String(file.readAllBytes()).equals("complete"), file -> {
                try (file; var stream = file.outputStream()) {
                    stream.write("complete".getBytes());
                }
            }, AtomicSharedCache.AtomicReplacement::run);
        assertEquals("complete", Files.readString(target));
    }

    @Test
    void cleanupCannotDeleteAReplacementWithTheSameName() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.readAttributes(root,
            java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null,
            "Same-type replacement is observable only when the provider supplies a file key");
        try (var directory = DirectoryAccess.open(root)) {
            Object identity;
            try (var owned = directory.file("owned", true, true, true)) {
                identity = directory.fileIdentity("owned");
            }
            Files.move(root.resolve("owned"), root.resolve("retired"));
            Files.writeString(root.resolve("owned"), "replacement");
            assertThrows(IOException.class, () -> directory.delete("owned", identity, false));
            assertEquals("replacement", Files.readString(root.resolve("owned")));
        }
    }

    @Test
    void cleanupRejectsAReplacementLinkWithoutTouchingItsTarget() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        var source = Files.writeString(temp.resolve("source"), "original");
        try (var directory = DirectoryAccess.open(root)) {
            Object identity;
            try (var file = directory.file("owned", true, true, true)) {
                identity = directory.fileIdentity("owned");
            }
            Files.delete(root.resolve("owned"));
            Files.createSymbolicLink(root.resolve("owned"), source);
            assertThrows(IOException.class, () -> directory.delete("owned", identity, false));
            assertEquals("original", Files.readString(source));
        }
    }

    private static void link(Path link, Path target, boolean junction) throws Exception {
        if (!junction) {
            Files.createSymbolicLink(link, target);
            return;
        }
        var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }
}
