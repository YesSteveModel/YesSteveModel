package com.elfmcys.ysm.expression.oracle;

import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import dev.semaweave.compilation.OracleSemanticProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Call-contract oracle: for every one of the 31 callables the frozen dataset fixes the supported arity range, and the
 * engine must accept it, reject the whole unit for an over/under-bound call, and keep a legal-arity type error a
 * program replacement rather than an arity failure (Frozen O-01, O-03; A-02).
 */
final class BuiltinCallContractOracleTest {
    private static final BuiltinCapabilityOracle ORACLE = BuiltinCapabilityOracle.load();
    private static final ExpressionHost HOST = ExpressionHost.create(HostAvailability.all());

    @Test
    void supportedAritiesRunAndOutOfRangeCallsRecoverLocally() throws Exception {
        var programs = new ArrayList<OracleSemanticProbe.Program>();
        var accepted = new TreeSet<String>();
        var rejected = new ArrayList<OracleSemanticProbe.Program>();
        for (var capability : ORACLE.invokeCapabilities()) {
            var entry = entry(capability);
            for (var count : supportedCounts(capability)) {
                var name = capability.sourceSpelling() + ".accepted_" + count;
                programs.add(new OracleSemanticProbe.Program(name, expression(capability, count), entry));
                accepted.add(name);
            }
            int invalidCount = capability.minimum() > 0 ? capability.minimum() - 1 : capability.maximum() + 1;
            rejected.add(new OracleSemanticProbe.Program(capability.sourceSpelling() + ".rejected_" + invalidCount,
                expression(capability, invalidCount), entry));
        }

        var outcome = OracleSemanticProbe.compile(HOST, programs, false);
        assertEquals(List.of(), outcome.fallbackPrograms(), "supported programs must not need a semantic fallback");
        assertEquals(List.of(), outcome.localRecoveryPrograms(), "Contract 3 does not publish arity-zero programs");
        assertEquals("FAIL_EXECUTION", outcome.nonFinitePolicy());
        for (var name : accepted) {
            assertFalse(outcome.typedZeroPrograms().get(name), name);
        }
        for (var program : rejected) {
            var recovered = OracleSemanticProbe.compile(HOST, List.of(program), true);
            assertTrue(recovered.localRecoveryPrograms().contains(program.name())
                || recovered.fallbackPrograms().contains(program.name()), program.name());
        }
    }

    @Test
    void legalArityTypeFailuresStaySemanticFailuresInsteadOfArityZeros() throws Exception {
        var programs = List.of(new OracleSemanticProbe.Program("bone-string", "ysm.bone_rot(1)", "VECTOR"),
            new OracleSemanticProbe.Program("animation-string", "ctrl.set_animation(1)", "VOID"),
            new OracleSemanticProbe.Program("version-string", "ysm.mod_version(1)", "OPTIONAL_STRING"));
        var names = programs.stream().map(OracleSemanticProbe.Program::name)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        var outcome = OracleSemanticProbe.compile(HOST, programs, true);
        assertEquals(new TreeSet<>(List.of("animation-string")), new TreeSet<>(outcome.localRecoveryPrograms()),
            "whole-program replacement discards the abandoned original tree's records");
        assertEquals(new TreeSet<>(List.of("bone-string", "version-string")), new TreeSet<>(outcome.fallbackPrograms()),
            "unsupported final object entry still uses its separate whole-program fallback");
    }

    @Test
    void everyCallableHasASupportedFixtureAndAnOutOfRangeFixture() {
        for (var capability : ORACLE.invokeCapabilities()) {
            assertFalse(supportedCounts(capability).isEmpty(), capability.label());
            assertTrue(capability.minimum() > 0 || capability.maximum() < 255,
                capability.label() + " must have a representable invalid arity");
        }
    }

    private static String entry(BuiltinCapabilityOracle.Capability capability) {
        var result = BuiltinStaticOracle.result(capability.sourceSpelling());
        return result == BuiltinStaticOracle.Type.UNSET ? "VOID" : result.name();
    }

    private static List<Integer> supportedCounts(BuiltinCapabilityOracle.Capability capability) {
        int upper = capability.maximum() == 255 ? Math.min(255, capability.minimum() + 1) : capability.maximum();
        return upper == capability.minimum() ? List.of(upper) : List.of(capability.minimum(), upper);
    }

    private static String expression(BuiltinCapabilityOracle.Capability capability, int count) {
        var signature = BuiltinStaticOracle.signature(capability.sourceSpelling());
        var arguments = new ArrayList<String>(count);
        for (var index = 0; index < count; index++) {
            var type = index < signature.fixed().size() ? signature.fixed().get(index)
                : signature.hasVariadic() ? signature.variadic() : BuiltinStaticOracle.Type.F32;
            arguments.add(argument(type));
        }
        return capability.sourceSpelling() + '(' + String.join(",", arguments) + ')';
    }

    private static String argument(BuiltinStaticOracle.Type type) {
        return switch (type) {
            case STRING, SOUND_ID -> "'oracle'";
            case BOOL -> "true";
            case F32 -> "1";
            default -> throw new IllegalArgumentException("invalid parameter type " + type);
        };
    }
}
