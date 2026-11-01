package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.CapturedModel;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.natives.NativeArchive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Converts one frozen raw source into a staged current container.
 *
 * <p>Execution: capture and conversion perform synchronous I/O. A capture must remain open and be
 * exclusively available to its compilation; the caller owns the output directory and staging lifetime.
 *
 * <p>Seam: implements [frozen conversion](docs/architecture/asset-pipeline/overview.md#冻结输入与单向转换); storage revalidation and publication remain with the receiving owner.
 */
public final class RawModelImporter {
    private final DefaultAnimationFilter defaultAnimations;

    public RawModelImporter(DefaultAnimationFilter defaultAnimations) {
        this.defaultAnimations = Objects.requireNonNull(defaultAnimations, "defaultAnimations");
    }

    /** Returns an independently owned capture after closing this call's directory or archive handle. */
    public CapturedModel capture(Path source) {
        if (Files.isDirectory(source)) {
            try (var vfs = new Directory(source)) {
                return ModelParser.capture(vfs);
            }
        }
        try (var vfs = NativeArchive.open(source.toString())) {
            return ModelParser.capture(vfs);
        }
    }

    public RawCompileResult convert(CapturedModel captured, Path outputDirectory) {
        try (var directory = com.elfmcys.ysm.util.DirectoryAccess
            .open(com.elfmcys.ysm.util.DirectoryAccess.ensureDirectory(outputDirectory))) {
            return convert(captured, directory);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /**
     * Borrows captured and directory to write a fresh staging entry, checking scan/compile identity and diagnostics.
     *
     * <p>Ensures: normal return transfers the completed staging path to the caller, not a published or
     * revalidated storage object. The capture and directory remain caller-owned.
     *
     * <p>Failure: attempts to remove only this call's identified staging entry. I/O is reported as
     * unchecked I/O failure; cleanup failure is retained. Existing source and committed objects are not replaced.
     */
    public RawCompileResult convert(CapturedModel captured, com.elfmcys.ysm.util.DirectoryAccess directory) {
        Objects.requireNonNull(captured, "captured");
        var name = captured.modelId() + "-" + java.util.UUID.randomUUID() + ".mxc";
        Object identity = null;
        try (var output = directory.file(name, true, true, true)) {
            identity = directory.fileIdentity(name);
            var compiled = ModelParser.compile(captured.data().asVirtualFileSystem(), output,
                directory.label().resolve(name), defaultAnimations, captured.originVer());
            if (!captured.modelId().equals(compiled.modelHash())) {
                throw new IllegalStateException("Captured and compiled model identities do not match");
            }
            if (!captured.diagnostics().equals(compiled.diagnostics())) {
                throw new IllegalStateException("Captured and compiled model diagnostics do not match");
            }
            return compiled;
        } catch (java.io.IOException failure) {
            cleanup(directory, name, identity, failure);
            throw new java.io.UncheckedIOException(failure);
        } catch (RuntimeException | Error failure) {
            cleanup(directory, name, identity, failure);
            throw failure;
        }
    }

    private static void cleanup(com.elfmcys.ysm.util.DirectoryAccess directory, String name, Object identity,
        Throwable failure) {
        if (identity == null)
            return;
        try {
            directory.delete(name, identity, false);
        } catch (java.io.IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

}
