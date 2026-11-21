package com.elfmcys.ysm.expression.frontend;

import dev.semaweave.compilation.TaskContext;
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

/** Pure Molang source to Semaweave Portable Program frontend. */
public final class MolangFrontend {
    private final PortableProgramCodec codec;
    private final CompilationObserver observer;

    public MolangFrontend() {
        this(PortableProgramCodecs.v1(), CompilationObserver.none());
    }

    public MolangFrontend(PortableProgramCodec codec) {
        this(codec, CompilationObserver.none());
    }

    public MolangFrontend(CompilationObserver observer) {
        this(PortableProgramCodecs.v1(), observer);
    }

    public MolangFrontend(PortableProgramCodec codec, CompilationObserver observer) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public Result compile(String source, SourceMode mode, SourceLocation location) throws MolangFrontendException {
        return compile(source, mode, location, TaskContext.NONE);
    }

    public Result compile(String source, SourceMode mode, SourceLocation location, TaskContext task)
        throws MolangFrontendException {
        Objects.requireNonNull(task, "task").checkCancelled();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(location, "location");
        var sourceBytes = source.getBytes(StandardCharsets.UTF_8);

        if (isSectionMarker(source) || mode == SourceMode.INSTRUCTION && isCommentStatement(source)) {
            return observed(source, mode, location, encodeNoOp(sourceBytes.length, location));
        }
        var parsed = new MolangSourceParser(source, location, task).parse(mode);
        final EncodedProgram encoded;
        try {
            encoded = codec.encode(PortablePrograms.ofRoots(parsed.roots()));
        } catch (ProgramStructuralException | IllegalArgumentException failure) {
            throw new MolangFrontendException(MolangFrontendException.Kind.LOWERING, location, -1,
                "Portable Program rejected lowered object graph", failure);
        }
        task.checkCancelled();
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
            if (value.isBlank()) {
                throw new IllegalArgumentException("source location must not be blank");
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

}
