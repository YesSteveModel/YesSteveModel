package com.elfmcys.ysm.expression.host;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MathIntrinsicBindingTest {
    @Test
    void mathUsesOneLanguageDeclarationWithoutJavaProviders() {
        var host = ExpressionHost.create(HostAvailability.all());
        assertEquals(14, MathDeclarations.ALL.size());
        for (var declaration : MathDeclarations.ALL) {
            var descriptor = host.registry().function(declaration.key()).orElseThrow().methods().get(0);
            assertEquals(declaration.operation(), descriptor.intrinsic().orElseThrow());
            assertTrue(descriptor.effects().isEmpty());
            assertFalse(descriptor.nullableReturn());
            assertFalse(descriptor.injectContext());
            assertTrue(host.expressionBinding().bindingPlan().guard(descriptor.method()).isEmpty());
            assertEquals(declaration.operation().arity(),
                BuiltinDeclarations.group(declaration.key().canonical()).minimumArguments());
            assertTrue(host.declarations().stream().flatMap(d -> d.members().stream())
                .noneMatch(m -> m.schema().symbol().equals(declaration.key().canonical())));
        }
    }

    @Test
    void numericEdgesKeepDegreeConversionSignedZeroAndFloatEvaluationOrder() {
        assertBits(-0.0f, dev.semaweave.internal.IntrinsicMath.min(+0.0f, -0.0f));
        assertBits(+0.0f, dev.semaweave.internal.IntrinsicMath.max(-0.0f, +0.0f));
        assertBits(+0.0f, dev.semaweave.internal.IntrinsicMath.abs(-0.0f));
        assertBits(-0.0f, dev.semaweave.internal.IntrinsicMath.sin(-0.0f));
        assertBits(1.0f, dev.semaweave.internal.IntrinsicMath.sin(90.0f));
        assertBits(-1.0f, dev.semaweave.internal.IntrinsicMath.cos(180.0f));
        assertBits(1.0f, dev.semaweave.internal.IntrinsicMath.exp(0.0f));
        assertBits(2.0f, dev.semaweave.internal.IntrinsicMath.sqrt(4.0f));
        assertBits(-0.0f, dev.semaweave.internal.IntrinsicMath.floor(-0.0f));
        assertBits(-2.0f, dev.semaweave.internal.IntrinsicMath.floor(-1.5f));
        assertBits(8.0f, dev.semaweave.internal.IntrinsicMath.pow(2.0f, 3.0f));
        assertBits(-1.0f, dev.semaweave.internal.IntrinsicMath.mod(-5.0f, 2.0f));
        assertBits(-0.0f, dev.semaweave.internal.IntrinsicMath.mod(-0.0f, 3.0f));
        assertBits(+0.0f, dev.semaweave.internal.IntrinsicMath.round(-0.5f));
        assertBits(+0.0f, dev.semaweave.internal.IntrinsicMath.round(-0.0f));
        assertBits(2147483648.0f, dev.semaweave.internal.IntrinsicMath.round(Float.MAX_VALUE));
        assertBits(-2147483648.0f, dev.semaweave.internal.IntrinsicMath.round(-Float.MAX_VALUE));
        assertBits(-0.0f, dev.semaweave.internal.IntrinsicMath.minAngle(-0.0f));
        assertBits(-180.0f, dev.semaweave.internal.IntrinsicMath.minAngle(540.0f));
        assertBits(-180.0f, dev.semaweave.internal.IntrinsicMath.minAngle(-180.0f));
        assertBits(-1.0f, dev.semaweave.internal.IntrinsicMath.minAngle(359.0f));
        assertBits(3.0f, dev.semaweave.internal.IntrinsicMath.clamp(4.0f, 5.0f, 3.0f));
        assertBits(12.0f, dev.semaweave.internal.IntrinsicMath.lerp(10.0f, 20.0f, 0.2f));
        for (org.junit.jupiter.api.function.Executable operation : List.<org.junit.jupiter.api.function.Executable>of(
            () -> dev.semaweave.internal.IntrinsicMath.exp(Float.MAX_VALUE),
            () -> dev.semaweave.internal.IntrinsicMath.sqrt(-1.0f),
            () -> dev.semaweave.internal.IntrinsicMath.mod(1.0f, 0.0f),
            () -> dev.semaweave.internal.IntrinsicMath.pow(-1.0f, 0.5f),
            () -> dev.semaweave.internal.IntrinsicMath.lerp(Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f))) {
            assertDoesNotThrow(operation);
        }
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
