package com.elfmcys.ysm.expression.host;

import static com.elfmcys.ysm.expression.TestRoamingPayloads.named;
import static org.junit.jupiter.api.Assertions.*;

import dev.semaweave.ContextTypes;
import dev.semaweave.ProgramKey;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import dev.semaweave.SymbolKey;
import dev.semaweave.compilation.BindingPlan;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.interpreter.InterpreterEnvironment;
import dev.semaweave.interpreter.InterpreterFunctions;
import dev.semaweave.registry.ExpressionRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class InterpretedRoamingStateTest {
    private final InterpreterEnvironment<Object> environment = environment();

    private static InterpreterEnvironment<Object> environment() {
        var hash = ExpressionIdentityHash.SHA_256;
        var registry =
            ExpressionRegistry.builder(ContextTypes.create("test:roaming", 1, Object.class, hash), hash).freeze();
        return new InterpreterEnvironment<>(registry, BindingPlan.identity(), CompilerOptions.DEFAULT,
            new InterpreterFunctions() {
                public Reference bind(ProgramKey caller, SymbolKey name, int argumentCount) {
                    return new Missing();
                }

                public Result acquire(Known reference) {
                    throw new AssertionError("no functions");
                }
            });
    }

    @AfterEach
    void closeEnvironment() {
        environment.close();
    }

    @Test
    void lazilyConsumedNamesKeepTheirNumbersAndDirtyOutputSurvivesObservation() {
        var values = environment.newRoamingState(RoamingRole.PRODUCER);
        var state = new RoamingState.Interpreted(values);
        assertTrue(state.fieldNames().isEmpty());
        values.declare(List.of("later"));
        assertEquals(0F, state.read((short)0));
        values.write("later", 7F);
        assertEquals(Map.of("later", 7F), named(state.snapshot(), state.fieldNames()));
        assertTrue(state.hasChanges());

        values.declare(List.of("earlier"));
        assertEquals(List.of("later", "earlier"), state.fieldNames());
        state.write((short)1, -0F);
        var delta = state.popChanges();
        assertEquals(Map.of("later", 7F, "earlier", -0F), named(delta, state.fieldNames()));
        assertFalse(state.hasChanges());
        values.write("later", 7F);
        assertEquals(Map.of("later", 7F), named(state.popChanges(), state.fieldNames()));
        assertThrows(IllegalStateException.class,
            () -> state.applyPatch(new RoamingPayload(new short[] {0}, new float[] {9})));
    }

    @Test
    void consumerFullClearsLocalWritesAndPatchPreservesUnmentionedValues() {
        var values = environment.newRoamingState(RoamingRole.CONSUMER);
        values.declare(List.of("a", "b"));
        var state = new RoamingState.Interpreted(values);
        state.write((short)1, 8F);
        state.applyPatch(new RoamingPayload(new short[] {0}, new float[] {-0F}));
        assertEquals(8F, state.read((short)1));
        assertEquals(Float.floatToRawIntBits(-0F), Float.floatToRawIntBits(state.read((short)0)));
        state.applyFull(new RoamingPayload(new short[] {0}, new float[] {3F}));
        assertEquals(3F, state.read((short)0));
        assertEquals(0F, state.read((short)1));
        assertThrows(IllegalStateException.class, state::hasChanges);
        assertThrows(IllegalStateException.class, state::snapshot);
        assertThrows(IllegalStateException.class, state::full);
        assertThrows(IllegalStateException.class, state::popChanges);
    }
}
