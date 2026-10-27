package com.elfmcys.ysm.expression.frontend;

import dev.semaweave.program.EncodedProgram;
import dev.semaweave.program.PortableProgramCodec;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.PortablePrograms;
import dev.semaweave.program.ProgramNode;
import dev.semaweave.program.ProgramStructuralException;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure bounded Molang source to Semaweave Portable Program frontend. */
public final class MolangFrontend {
    public static final Limits DEFAULT_LIMITS =
        new Limits(64 * 1024, 65_535, 65_535, 256, 4_096, 255, 16_384, 1024 * 1024);

    private final PortableProgramCodec codec;
    private final Limits limits;
    private final CompilationObserver observer;

    public MolangFrontend() {
        this(PortableProgramCodecs.v1(), DEFAULT_LIMITS, CompilationObserver.none());
    }

    public MolangFrontend(PortableProgramCodec codec, Limits limits) {
        this(codec, limits, CompilationObserver.none());
    }

    public MolangFrontend(CompilationObserver observer) {
        this(PortableProgramCodecs.v1(), DEFAULT_LIMITS, observer);
    }

    public MolangFrontend(PortableProgramCodec codec, Limits limits, CompilationObserver observer) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public Result compile(String source, SourceMode mode, SourceLocation location) throws MolangFrontendException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(location, "location");
        if (source.length() > limits.maxSourceBytes()) {
            throw new MolangFrontendException(MolangFrontendException.Kind.SOURCE_LIMIT, location,
                limits.maxSourceBytes(), "Source exceeds UTF-8 byte budget");
        }
        var sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        if (sourceBytes.length > limits.maxSourceBytes()) {
            throw new MolangFrontendException(MolangFrontendException.Kind.SOURCE_LIMIT, location,
                limits.maxSourceBytes(), "UTF-8 source exceeds " + limits.maxSourceBytes() + " bytes");
        }

        if (isSectionMarker(source) || mode == SourceMode.INSTRUCTION && isCommentStatement(source)) {
            return observed(source, mode, location, encodeNoOp(sourceBytes.length, location));
        }
        var parsed = new MolangSourceParser(source, location, limits).parse(mode);
        final EncodedProgram encoded;
        try {
            encoded = codec.encode(PortablePrograms.ofRoots(parsed.roots()));
        } catch (ProgramStructuralException | IllegalArgumentException failure) {
            throw new MolangFrontendException(MolangFrontendException.Kind.LOWERING, location, -1,
                "Portable Program rejected lowered object graph", failure);
        }
        if (encoded.payload().size() > limits.maxPayloadBytes()) {
            throw new MolangFrontendException(MolangFrontendException.Kind.LOWERING, location, -1,
                "Portable Program exceeds " + limits.maxPayloadBytes() + " bytes");
        }
        return observed(source, mode, location,
            new Result(encoded, parsed.references(), new Statistics(sourceBytes.length, parsed.tokenCount(),
                parsed.nodeCount(), parsed.maximumDepth(), parsed.localCount(), encoded.payload().size())));
    }

    private Result observed(String source, SourceMode mode, SourceLocation location, Result result) {
        observer.compiled(source, mode, location, result);
        return result;
    }

    @FunctionalInterface
    public interface CompilationObserver {
        void compiled(String source, SourceMode mode, SourceLocation location, Result result);

        static CompilationObserver none() {
            return (source, mode, location, result) -> {
            };
        }
    }

    public enum SourceMode {
        EXPRESSION, INSTRUCTION, USER_FUNCTION, CONFIG_WRITE
    }

    private Result encodeNoOp(int sourceBytes, SourceLocation location) throws MolangFrontendException {
        try {
            var encoded = codec.encode(PortablePrograms.ofRoots(List.of(new ProgramNode.Unset())));
            return new Result(encoded, List.of(), new Statistics(sourceBytes, 1, 1, 1, 0, encoded.payload().size()));
        } catch (ProgramStructuralException failure) {
            throw new MolangFrontendException(MolangFrontendException.Kind.LOWERING, location, -1,
                "Portable Program rejected no-op instruction", failure);
        }
    }

    private static boolean isCommentStatement(String source) {
        var value = source.strip();
        return value.length() >= 2 && value.charAt(0) == '\''
            && (value.charAt(value.length() - 1) == '\'' || value.length() >= 3 && value.endsWith("';"));
    }

    private static boolean isSectionMarker(String source) {
        var value = source.strip();
        if (value.equals("[;") || value.equals("];")) {
            return true;
        }
        if (!value.startsWith("['") || !value.endsWith("';")) {
            return false;
        }
        return value.substring(2, value.length() - 2).indexOf('\'') < 0;
    }

    public record SourceLocation(String value) {
        public SourceLocation {
            Objects.requireNonNull(value, "value");
            var length = value.getBytes(StandardCharsets.UTF_8).length;
            if (value.isBlank() || length > 1024) {
                throw new IllegalArgumentException("source location must contain 1..1024 UTF-8 bytes");
            }
        }
    }

    public record ReferencedSymbol(ProgramNode.SymbolDomain domain, String name, ProgramNode.AccessForm accessForm) {

        static final Comparator<ReferencedSymbol> ORDER =
            Comparator.comparing((ReferencedSymbol value) -> value.domain().numericId())
                .thenComparing(ReferencedSymbol::name).thenComparing(value -> value.accessForm().numericId());

        public ReferencedSymbol {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(accessForm, "accessForm");
        }
    }

    public record Statistics(int sourceBytes, int tokens, int nodes, int maximumDepth, int locals, int payloadBytes) {
    }

    public record Result(EncodedProgram program, List<ReferencedSymbol> referencedSymbols, Statistics statistics) {
        public Result {
            Objects.requireNonNull(program, "program");
            referencedSymbols = List.copyOf(referencedSymbols);
            Objects.requireNonNull(statistics, "statistics");
        }
    }

    public record Limits(int maxSourceBytes, int maxTokens, int maxNodes, int maxDepth, int maxStatements,
        int maxArguments, int maxLocals, int maxPayloadBytes) {
        public Limits {
            if (maxSourceBytes <= 0 || maxTokens <= 0 || maxNodes <= 0 || maxDepth <= 0 || maxStatements <= 0
                || maxArguments <= 0 || maxLocals <= 0 || maxPayloadBytes <= 0) {
                throw new IllegalArgumentException("frontend limits must be positive");
            }
            if (maxDepth > 256 || maxStatements > 4096 || maxArguments > 255 || maxPayloadBytes > 1024 * 1024) {
                throw new IllegalArgumentException("frontend limits exceed Portable Program V1");
            }
        }
    }
}
