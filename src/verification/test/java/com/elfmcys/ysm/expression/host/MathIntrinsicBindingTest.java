package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.builtin.MathBuiltins;
import dev.semaweave.registry.DeclarationEffect;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MathIntrinsicBindingTest {
    @Test
    void onlyFourteenExactBuiltinMathMembersBypassTheContextAdapter() {
        var host = ExpressionHost.create(HostAvailability.all());
        var plan = host.expressionBinding().bindingPlan();
        int trusted = 0;
        for (var declaration : host.declarations())
            for (var member : declaration.members()) {
                var call = plan.callTarget(member.binding());
                var guard = plan.guard(member.binding()).orElseThrow();
                assertEquals(member.binding(), guard.direct());
                assertEquals(member.subject(), guard.subject().orElseThrow());
                if (call.contextFree()) {
                    trusted++;
                    assertEquals(MathBuiltins.class, declaration.owner());
                    assertTrue(declaration.schema().builtin());
                    assertEquals(DeclarationEffect.CONTEXT, guard.effect());
                    assertEquals(MathBuiltins.class, call.member().owner());
                    assertEquals(member.schema().javaName(), call.member().methodName());
                    assertEquals(member.binding().methodType().dropParameterTypes(0, 1), call.member().methodType());
                    assertFalse(call.requiresContext());
                } else {
                    assertEquals(member.binding(), call.member());
                    assertTrue(call.requiresContext());
                }
            }
        assertEquals(14, trusted);
    }

    @Test
    void callableAndContextFreeOverloadsPreserveBitsAndOriginalFailures() throws Exception {
        for (String name : List.of("abs", "clamp", "cos", "exp", "floor", "lerp", "max", "min", "mod", "pow", "round",
            "sin", "sqrt", "minAngle")) {
            int arity = name.equals("clamp") || name.equals("lerp") ? 3
                : List.of("max", "min", "mod", "pow").contains(name) ? 2 : 1;
            Class<?>[] scalars = new Class<?>[arity];
            Arrays.fill(scalars, float.class);
            var direct = MathBuiltins.class.getMethod(name, scalars);
            Class<?>[] declaration = new Class<?>[arity + 1];
            declaration[0] = MolangContextView.class;
            System.arraycopy(scalars, 0, declaration, 1, arity);
            var callable = MathBuiltins.class.getMethod(name, declaration);
            for (float value : new float[] {0.0f, -0.0f, Float.MIN_VALUE, -0.5f, 1.0f, -1.0f, 180.0f,
                Float.MAX_VALUE}) {
                Object[] arguments = new Object[arity];
                Arrays.fill(arguments, value);
                Object[] borrowed = new Object[arity + 1];
                System.arraycopy(arguments, 0, borrowed, 1, arity);
                Float expectedValue = null;
                Throwable expectedFailure = null;
                try {
                    expectedValue = (float)callable.invoke(null, borrowed);
                } catch (InvocationTargetException failure) {
                    expectedFailure = failure.getCause();
                }
                if (expectedFailure == null) {
                    assertBits(expectedValue, (float)direct.invoke(null, arguments));
                } else {
                    var actual = assertThrows(InvocationTargetException.class, () -> direct.invoke(null, arguments));
                    assertEquals(expectedFailure.getClass(), actual.getCause().getClass());
                    assertEquals(expectedFailure.getMessage(), actual.getCause().getMessage());
                }
            }
        }
    }

    @Test
    void numericEdgesKeepDegreeConversionSignedZeroAndFloatEvaluationOrder() {
        assertBits(-0.0f, MathBuiltins.min(+0.0f, -0.0f));
        assertBits(+0.0f, MathBuiltins.max(-0.0f, +0.0f));
        assertBits(+0.0f, MathBuiltins.abs(-0.0f));
        assertBits(-0.0f, MathBuiltins.sin(-0.0f));
        assertBits(1.0f, MathBuiltins.sin(90.0f));
        assertBits(-1.0f, MathBuiltins.cos(180.0f));
        assertBits(1.0f, MathBuiltins.exp(0.0f));
        assertBits(2.0f, MathBuiltins.sqrt(4.0f));
        assertBits(-0.0f, MathBuiltins.floor(-0.0f));
        assertBits(-2.0f, MathBuiltins.floor(-1.5f));
        assertBits(8.0f, MathBuiltins.pow(2.0f, 3.0f));
        assertBits(-1.0f, MathBuiltins.mod(-5.0f, 2.0f));
        assertBits(-0.0f, MathBuiltins.mod(-0.0f, 3.0f));
        assertBits(+0.0f, MathBuiltins.round(-0.5f));
        assertBits(+0.0f, MathBuiltins.round(-0.0f));
        assertBits(2147483648.0f, MathBuiltins.round(Float.MAX_VALUE));
        assertBits(-2147483648.0f, MathBuiltins.round(-Float.MAX_VALUE));
        assertBits(-0.0f, MathBuiltins.minAngle(-0.0f));
        assertBits(-180.0f, MathBuiltins.minAngle(540.0f));
        assertBits(-180.0f, MathBuiltins.minAngle(-180.0f));
        assertBits(-1.0f, MathBuiltins.minAngle(359.0f));
        assertBits(3.0f, MathBuiltins.clamp(4.0f, 5.0f, 3.0f));
        assertBits(12.0f, MathBuiltins.lerp(10.0f, 20.0f, 0.2f));
        for (org.junit.jupiter.api.function.Executable operation : List.<org.junit.jupiter.api.function.Executable>of(
            () -> MathBuiltins.exp(Float.MAX_VALUE), () -> MathBuiltins.sqrt(-1.0f), () -> MathBuiltins.mod(1.0f, 0.0f),
            () -> MathBuiltins.pow(-1.0f, 0.5f), () -> MathBuiltins.lerp(Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f))) {
            assertEquals("non-finite math result", assertThrows(ArithmeticException.class, operation).getMessage());
        }
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
