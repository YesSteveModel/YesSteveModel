package dev.semaweave.compilation;

import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import dev.semaweave.BuiltinType;
import dev.semaweave.EntryResult;
import dev.semaweave.ProgramKey;
import dev.semaweave.ProgramShape;
import dev.semaweave.TopLevelEntrySignature;
import dev.semaweave.UnionType;
import dev.semaweave.ValueType;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ReadOnlyBytes;
import dev.semaweave.registry.RegistrySnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Test-only bridge that gives the T01 oracle package access to whole-unit semantic analysis facts without copying them
 * into production code.
 *
 * <p>
 * It only observes outcomes of the engine's declared policy (whole-unit arity rejection, semantic fallback selection,
 * typed-zero lowering) for programs whose expressions were authored by the oracle before execution.
 */
public final class OracleSemanticProbe {
    public record Program(String name, String source, String entry) {
        public Program {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(entry, "entry");
        }
    }

    public record Outcome(List<String> localRecoveryPrograms, List<String> fallbackPrograms,
        Map<String, Boolean> typedZeroPrograms, Map<String, List<String>> programEffects, String nonFinitePolicy) {
        public Outcome {
            localRecoveryPrograms = List.copyOf(localRecoveryPrograms);
            fallbackPrograms = List.copyOf(fallbackPrograms);
            typedZeroPrograms = Map.copyOf(typedZeroPrograms);
            programEffects = Map.copyOf(programEffects);
        }
    }

    private OracleSemanticProbe() {}

    public static CompilationUnit unit(ExpressionHost host, List<Program> programs, boolean withFallback,
        String location) throws Exception {
        var codec = PortableProgramCodecs.v1();
        var frontend = new com.elfmcys.ysm.expression.frontend.MolangFrontend();
        var builder = CompilationUnit.builder(host.compilationPolicy(), codec, ExpressionIdentityHash.SHA_256);
        var index = 0;
        for (var program : programs) {
            var compiled = frontend.compile(program.source(),
                com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceMode.EXPRESSION,
                new com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation(location + "/" + program.name()))
                .program();
            var signature = signature(program.entry());
            var fallbackSource = switch (program.entry()) {
                case "BOOL" -> "false";
                case "F32" -> "0";
                case "STRING" -> "''";
                case "VECTOR" -> "ysm.bone_rot('b')";
                case "OPTIONAL_BOOL" -> "ctrl.armor('a','b')";
                case "OPTIONAL_F32" -> "query.position(0)";
                case "OPTIONAL_STRING" -> "ysm.mod_version('x')";
                case "VOID" -> "ctrl.reset()";
                default -> throw new IllegalArgumentException("unsupported oracle entry " + program.entry());
            };
            Optional<dev.semaweave.program.EncodedProgram> fallback = withFallback ? Optional.of(frontend
                .compile(fallbackSource, com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceMode.EXPRESSION,
                    new com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation(
                        location + "/" + program.name() + "/fallback"))
                .program()) : Optional.empty();
            builder.addProgram(new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)(index + 1)})),
                compiled, signature, fallback, new DiagnosticLocation(location + "/" + program.name())));
            index++;
        }
        return builder.build();
    }

    public static TopLevelEntrySignature signature(String entry) {
        var type = switch (entry) {
            case "VECTOR" -> (ValueType)ExpressionHost.VECTOR;
            case "BOOL" -> (ValueType)BuiltinType.BOOL;
            case "F32" -> (ValueType)BuiltinType.F32;
            case "STRING" -> (ValueType)BuiltinType.STRING;
            case "OPTIONAL_BOOL" -> optional(BuiltinType.BOOL);
            case "OPTIONAL_F32" -> optional(BuiltinType.F32);
            case "OPTIONAL_STRING" -> optional(BuiltinType.STRING);
            case "VOID" -> null;
            default -> throw new IllegalArgumentException("unsupported oracle entry " + entry);
        };
        if ("VOID".equals(entry)) {
            return new TopLevelEntrySignature(ProgramShape.VOID, new EntryResult.Void(), List.of(), List.of());
        }
        var shape = switch (entry) {
            case "F32" -> ProgramShape.F32;
            case "BOOL" -> ProgramShape.BOOLEAN;
            default -> ProgramShape.VALUE;
        };
        return new TopLevelEntrySignature(shape, new EntryResult.Value(type), List.of(), List.of());
    }

    private static UnionType optional(ValueType type) {
        return new UnionType(List.of(BuiltinType.UNSET, type));
    }

    public static Outcome compile(ExpressionHost host, List<Program> programs, boolean withFallback) throws Exception {
        var unit = unit(host, programs, withFallback, "t01-oracle");
        try {
            return analyze(unit, host, programs);
        } catch (SemanticCompilationException failure) {
            var program = programs.get(failure.programId().value());
            throw new SemanticCompilationException(failure.reason(), failure.programId(),
                program.name() + ':' + failure.nodePath(),
                failure.getMessage() + " [" + program.name() + " = " + program.source() + ']');
        }
    }

    private static Outcome analyze(CompilationUnit unit, ExpressionHost host, List<Program> programs) throws Exception {
        var semantic = WholeUnitSemanticCompiler.compile(unit, host.registry());
        var arityZero = new ArrayList<String>();
        for (var selection : semantic.localRecoveries()) {
            arityZero.add(programs.get(selection.programId().value()).name());
        }
        var fallbacks = new ArrayList<String>();
        for (var selection : semantic.fallbackSelections()) {
            fallbacks.add(programs.get(selection.programId().value()).name());
        }
        var typedZero = new LinkedHashMap<String, Boolean>();
        var effects = new LinkedHashMap<String, List<String>>();
        for (int index = 0; index < programs.size(); index++) {
            var typed = semantic.program(new dev.semaweave.ProgramId(index));
            var name = programs.get(typed.sourceId().value()).name();
            var roots = typed.roots();
            typedZero.put(name, roots.size() == 1 && "TYPED_ZERO".equals(roots.get(0).operation().name()));
            effects.put(name, typed.effects().asSortedSet().stream().map(Object::toString).sorted().toList());
        }
        return new Outcome(arityZero, fallbacks, typedZero, effects, semantic.nonFinitePolicy().name());
    }

    /** Materializes the artifact's baked constants exactly like the module loader does. */
    public static Object[] materialize(BakedConstantTable table, RegistrySnapshot<?> registry) {
        return DefaultModuleLoader.materializeConstants(table, registry);
    }
}
