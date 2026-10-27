package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.util.DirectoryAccess;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Fixed cache root and replacement observations for checked path operations. */
public final class CacheDirectoryGuard {
    private final Path root;
    private final DirectoryAccess.Snapshot rootObservation;

    private CacheDirectoryGuard(Path root, DirectoryAccess.Snapshot rootObservation) {
        this.root = root;
        this.rootObservation = rootObservation;
    }

    public static CacheDirectoryGuard open(Path configuredRoot) throws IOException {
        var root = DirectoryAccess.ensureDirectory(configuredRoot);
        try (var directory = DirectoryAccess.open(root)) {
            return new CacheDirectoryGuard(root, directory.snapshot());
        }
    }

    public Path root() {
        return root;
    }

    public void verify() throws IOException {
        try (var ignored = rootObservation.open()) {
        }
    }

    public Observation observe(Path target, boolean createParents) throws IOException {
        var absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root))
            throw new IOException("Cache target escapes fixed root: " + target);
        try (var directory = rootObservation.open();
            var parent = directory.directory(root.relativize(absolute.getParent()), createParents)) {
            return new Observation(absolute, parent.snapshot());
        }
    }

    public final class Observation {
        private final Path target;
        private final DirectoryAccess.Snapshot parent;

        private Observation(Path target, DirectoryAccess.Snapshot parent) {
            this.target = target;
            this.parent = parent;
        }

        public Path target() {
            return target;
        }

        public DirectoryAccess acquire() throws IOException {
            return parent.open();
        }

        public void verify() throws IOException {
            try (var ignored = acquire()) {
            }
        }

        public Object fileIdentity() throws IOException {
            try (var parent = acquire()) {
                return parent.fileIdentity(target.getFileName().toString());
            }
        }

        public FileChannel open(Set<? extends OpenOption> options) throws IOException {
            try (var parent = acquire()) {
                var result = parent.file(target.getFileName().toString(), options.contains(StandardOpenOption.WRITE),
                    options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW),
                    options.contains(StandardOpenOption.CREATE_NEW));
                try {
                    if (options.contains(StandardOpenOption.TRUNCATE_EXISTING))
                        result.truncate(0);
                    return result;
                } catch (IOException | RuntimeException failure) {
                    result.close();
                    throw failure;
                }
            }
        }

        public boolean deleteIfExists(Object expectedIdentity) throws IOException {
            try (var parent = acquire()) {
                var name = target.getFileName().toString();
                parent.delete(name, expectedIdentity, false);
                return true;
            } catch (NoSuchFileException absent) {
                return false;
            }
        }
    }
}
