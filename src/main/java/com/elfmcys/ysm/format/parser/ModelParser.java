package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.schema.model.ModelOriginVersion;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.BuiltinDeclarations;
import com.elfmcys.ysm.format.vfs.VirtualFileSystem;
import com.elfmcys.ysm.model.domain.Hash256;

import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.util.Objects;

/** Coordinates raw source access and model assembly. The caller retains ownership of the VFS. */
public final class ModelParser {
    public static final String CANONICALIZER_PROFILE_VERSION = "2";
    public static final String PARSER_PROFILE_VERSION = "1";
    public static final String IMAGE_POLICY_PROFILE_VERSION = "2";

    private ModelParser() {}

    public static CapturedModel capture(VirtualFileSystem source) {
        var data = new CapturedSourceData(Objects.requireNonNull(source, "source"));
        try {
            var scan = parse(data, null, true, DefaultAnimationFilter.keepAll(), false);
            data.freeze();
            return new CapturedModel(scan.modelHash(), data, scan.diagnostics(), scan.originVer());
        } catch (RuntimeException | Error failure) {
            try {
                data.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public static Path parse(VirtualFileSystem vfs, Path outputDirectory, DefaultAnimationFilter defaultAnimations) {
        return compile(vfs, outputDirectory, defaultAnimations).stagedContainer();
    }

    /** Verification-only overload that observes the exact sources admitted by raw import. */
    public static Path parse(VirtualFileSystem vfs, Path outputDirectory, DefaultAnimationFilter defaultAnimations,
        MolangFrontend.CompilationObserver observer) {
        return Objects.requireNonNull(parse(vfs, Objects.requireNonNull(outputDirectory, "outputDirectory"), false,
            defaultAnimations, true, new MolangFrontend(observer)).output(), "output");
    }

    public static RawCompileResult compile(VirtualFileSystem vfs, Path outputDirectory,
        DefaultAnimationFilter defaultAnimations) {
        return compile(vfs, outputDirectory, defaultAnimations, ModelOriginVersion.CURRENT_RELEASE);
    }

    public static RawCompileResult compile(VirtualFileSystem vfs, Path outputDirectory,
        DefaultAnimationFilter defaultAnimations, String originVer) {
        var source = new RawModelSource(Objects.requireNonNull(vfs, "vfs"));
        final RawModelAssembler.Result result;
        try (var assembler = new RawModelAssembler(source, Objects.requireNonNull(outputDirectory, "outputDirectory"),
            false, defaultAnimations, true)) {
            assembler.originVer(originVer);
            result = assembler.parse();
        }
        return new RawCompileResult(result.modelHash(), Objects.requireNonNull(result.output(), "output"),
            result.diagnostics());
    }

    /** Uses the caller-owned channel so cache staging cannot reopen a mutable path. */
    public static RawCompileResult compile(VirtualFileSystem vfs, java.nio.channels.WritableByteChannel output,
        Path label, DefaultAnimationFilter defaultAnimations, String originVer) {
        var source = new RawModelSource(Objects.requireNonNull(vfs));
        try (var assembler = new RawModelAssembler(source, output, defaultAnimations, true, new MolangFrontend())) {
            assembler.originVer(originVer);
            var result = assembler.parse();
            return new RawCompileResult(result.modelHash(), label, result.diagnostics());
        }
    }

    /** Generates the builtin default without applying the default-animation filter to itself. */
    public static Path parseBuiltinDefault(VirtualFileSystem vfs, Path outputDirectory) {
        return Objects.requireNonNull(parse(vfs, Objects.requireNonNull(outputDirectory, "outputDirectory"), false,
            DefaultAnimationFilter.keepAll(), false, defaultFrontend(MolangFrontend.CompilationObserver.none()))
            .output(), "output");
    }

    /** Builds the required default as one complete in-memory container. */
    public static MemoryContainer parseBuiltinDefaultMemory(VirtualFileSystem vfs) {
        var bytes = new ByteArrayOutputStream();
        var source = new RawModelSource(Objects.requireNonNull(vfs, "vfs"));
        try (var output = Channels.newChannel(bytes); var assembler = new RawModelAssembler(source, output,
            DefaultAnimationFilter.keepAll(), false, defaultFrontend(MolangFrontend.CompilationObserver.none()))) {
            var result = assembler.parse();
            return new MemoryContainer(result.modelHash(), ArrayBuffer.move(bytes.toByteArray()));
        } catch (java.io.IOException failure) {
            throw new UncheckedIOException("Failed to build the builtin default in memory", failure);
        }
    }

    /** Verification-only default-model overload paired with the ordinary import path. */
    public static Path parseBuiltinDefault(VirtualFileSystem vfs, Path outputDirectory,
        MolangFrontend.CompilationObserver observer) {
        return Objects.requireNonNull(parse(vfs, Objects.requireNonNull(outputDirectory, "outputDirectory"), false,
            DefaultAnimationFilter.keepAll(), false, defaultFrontend(observer)).output(), "output");
    }

    private static MolangFrontend defaultFrontend(MolangFrontend.CompilationObserver observer) {
        Objects.requireNonNull(observer, "observer");
        return new MolangFrontend((source, mode, location, result) -> {
            BuiltinDeclarations.validateDefaultSource(location, result);
            observer.compiled(source, mode, location, result);
        });
    }

    /** Scans the parse resource set without decoding assets or writing a model container. */
    public static Hash256 scanModelHash(VirtualFileSystem vfs) {
        return parse(vfs, null, true, DefaultAnimationFilter.keepAll(), false).modelHash();
    }

    private static RawModelAssembler.Result parse(VirtualFileSystem vfs, Path outputDirectory, boolean dryRun,
        DefaultAnimationFilter defaultAnimations, boolean recompressImages) {
        return parse(vfs, outputDirectory, dryRun, defaultAnimations, recompressImages, new MolangFrontend());
    }

    private static RawModelAssembler.Result parse(VirtualFileSystem vfs, Path outputDirectory, boolean dryRun,
        DefaultAnimationFilter defaultAnimations, boolean recompressImages, MolangFrontend frontend) {
        var source = new RawModelSource(Objects.requireNonNull(vfs, "vfs"));
        try (var assembler = new RawModelAssembler(source, outputDirectory, dryRun,
            Objects.requireNonNull(defaultAnimations, "defaultAnimations"), recompressImages, frontend)) {
            return assembler.parse();
        }
    }

    public record MemoryContainer(Hash256 modelHash, ArrayBuffer bytes) implements AutoCloseable {
        public MemoryContainer {
            Objects.requireNonNull(modelHash, "modelHash");
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public ArrayBuffer bytes() {
            return bytes.acquire();
        }

        @Override
        public void close() {
            bytes.close();
        }
    }
}
