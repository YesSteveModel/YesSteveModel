package dev.semaweave.compilation;

import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import dev.semaweave.BuiltinType;
import dev.semaweave.EntryResult;
import dev.semaweave.ProgramKey;
import dev.semaweave.ProgramShape;
import dev.semaweave.TopLevelEntrySignature;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ReadOnlyBytes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostSemanticContractTest {
    private static final MolangFrontend.SourceLocation LOCATION =
        new MolangFrontend.SourceLocation("test/host-contract");
    private static final TopLevelEntrySignature F32_ENTRY =
        new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(), List.of());
    private static final TopLevelEntrySignature BOOL_ENTRY =
        new TopLevelEntrySignature(ProgramShape.BOOLEAN, new EntryResult.Value(BuiltinType.BOOL), List.of(), List.of());
    private static final TopLevelEntrySignature VOID_ENTRY =
        new TopLevelEntrySignature(ProgramShape.VOID, new EntryResult.Void(), List.of(), List.of());

    @Test
    void realFrontendBindsBothFunctionFormsAndRejectsFunctionAssignment() throws Exception {
        var host = ExpressionHost.create(HostAvailability.all());
        var valid = unit(host, "math.clamp(q.anim_time,0,1)", Optional.empty());
        var result = WholeUnitSemanticCompiler.compile(valid, host.registry());

        assertTrue(result.fallbackSelections().isEmpty());
        assertEquals(List.of("ysm:context-state"),
            result.programs().get(0).effects().asSortedSet().stream().map(dev.semaweave.EffectId::canonical).toList());

        var invoked = WholeUnitSemanticCompiler.compile(unit(host, "q.anim_time()", Optional.empty()), host.registry());
        assertTrue(invoked.fallbackSelections().isEmpty());
        assertTrue(invoked.localRecoveries().isEmpty());
        assertEquals(result.programs().get(0).effects(), invoked.programs().get(0).effects());

        var wrongKind = unit(host, "q.anim_time=1", Optional.empty());
        var failure = assertThrows(SemanticCompilationException.class,
            () -> WholeUnitSemanticCompiler.compile(wrongKind, host.registry()));
        assertSame(SemanticFailureReason.INVALID_ACCESS, failure.reason());
    }

    @Test
    void missingInvokeDoesNotRetainItsEffectfulArguments() throws Exception {
        var host = ExpressionHost.create(HostAvailability.all());
        var result =
            WholeUnitSemanticCompiler.compile(unit(host, "missing(ysm.sync(1))", Optional.of("0")), host.registry());

        assertTrue(result.fallbackSelections().isEmpty());
        assertTrue(result.programs().stream().allMatch(program -> program.effects().isEmpty()));
    }

    @Test
    void realFrontendEnforcesOriginalLiteralSentinelsForYsmObjects() throws Exception {
        var host = ExpressionHost.create(HostAvailability.all());
        for (var source : List.of("ysm.texture_name == (0)", "(1) != ysm.texture_name",
            "ysm.texture_name == q.is_jumping", "q.is_jumping != ysm.texture_name")) {
            var result = WholeUnitSemanticCompiler.compile(unit(host, source, Optional.of("false"), BOOL_ENTRY),
                host.registry());
            assertTrue(result.fallbackSelections().isEmpty(), source);
        }
        for (var source : List.of("ysm.texture_name == +0", "ysm.texture_name == -0", "ysm.texture_name == (1-1)")) {
            var result = WholeUnitSemanticCompiler.compile(unit(host, source, Optional.of("false"), BOOL_ENTRY),
                host.registry());
            assertEquals(1, result.fallbackSelections().size(), source);
            assertTrue(result.fallbackSelections().stream()
                .allMatch(replacement -> replacement.primaryReason() == SemanticFailureReason.TYPE_MISMATCH), source);
        }
    }

    @Test
    void scalarArgumentsRejectStringButSoundKeepsItsStringOverload() throws Exception {
        var host = ExpressionHost.create(HostAvailability.all());
        var defer = WholeUnitSemanticCompiler
            .compile(unit(host, "ysm.defer(ysm.texture_name)", Optional.empty(), VOID_ENTRY), host.registry());

        assertTrue(defer.fallbackSelections().isEmpty());
        assertEquals(1, defer.localRecoveries().size());
        assertTrue(defer.programs().stream().allMatch(program -> program.effects().isEmpty()));
        var booleanDefer = WholeUnitSemanticCompiler
            .compile(unit(host, "ysm.defer(q.is_jumping)", Optional.empty(), VOID_ENTRY), host.registry());
        assertTrue(booleanDefer.localRecoveries().isEmpty());
        var actionDefer = booleanDefer.programs().stream().findFirst().orElseThrow();
        assertTrue(
            actionDefer.effects().asSortedSet().stream().anyMatch(effect -> effect.canonical().equals("ysm:action")));
        assertTrue(containsOperation(actionDefer, SemanticModel.NodeOperation.CONVERT));

        var sound = WholeUnitSemanticCompiler
            .compile(unit(host, "ysm.play_sound('0','ysm:test')", Optional.empty(), VOID_ENTRY), host.registry());

        assertTrue(sound.fallbackSelections().isEmpty());
        var actionSound = sound.programs().stream().findFirst().orElseThrow();
        assertTrue(
            actionSound.effects().asSortedSet().stream().anyMatch(effect -> effect.canonical().equals("ysm:action")));
        assertFalse(containsOperation(actionSound, SemanticModel.NodeOperation.OBJECT_PRESENCE));
    }

    private static CompilationUnit unit(ExpressionHost host, String primary, Optional<String> fallback)
        throws Exception {
        return unit(host, primary, fallback, F32_ENTRY);
    }

    private static CompilationUnit unit(ExpressionHost host, String primary, Optional<String> fallback,
        TopLevelEntrySignature signature) throws Exception {
        var frontend = new MolangFrontend();
        var primaryProgram = frontend.compile(primary, MolangFrontend.SourceMode.EXPRESSION, LOCATION).program();
        var fallbackProgram = fallback.map(source -> {
            try {
                return frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION, LOCATION).program();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        var source = new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {1})), primaryProgram, signature,
            fallbackProgram, new DiagnosticLocation(LOCATION.value()));
        return CompilationUnit
            .builder(host.compilationPolicy(), PortableProgramCodecs.v1(), ExpressionIdentityHash.SHA_256)
            .addProgram(source).build();
    }

    private static boolean containsOperation(SemanticModel.TypedProgram program,
        SemanticModel.NodeOperation operation) {
        return program.roots().stream().anyMatch(root -> containsOperation(root, operation));
    }

    private static boolean containsOperation(SemanticModel.TypedNode node, SemanticModel.NodeOperation operation) {
        return node.operation() == operation
            || node.children().stream().anyMatch(child -> containsOperation(child, operation));
    }
}
