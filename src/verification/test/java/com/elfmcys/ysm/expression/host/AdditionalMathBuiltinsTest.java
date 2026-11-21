package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.expression.host.builtin.AdditionalMathBuiltins;
import dev.semaweave.SymbolKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AdditionalMathBuiltinsTest {
    @Test
    void registersLegacyMathAndPreservesItsNumericSemantics() {
        var host = ExpressionHost.create(HostAvailability.all());
        for (var name : new String[] {"ceil", "trunc", "ln", "acos", "asin", "atan", "atan2", "lerprotate",
            "hermite_blend", "hermite", "randomi"}) {
            assertTrue(host.registry().function(new SymbolKey("math:" + name)).isPresent(), name);
        }
        assertEquals(-1f, AdditionalMathBuiltins.ceil(-1.25f));
        assertEquals(-1f, AdditionalMathBuiltins.trunc(-1.25f));
        assertEquals(0f, AdditionalMathBuiltins.ln(-1));
        assertEquals((float)(Math.PI / 2), AdditionalMathBuiltins.acos(0));
        assertEquals((float)Math.atan(1), AdditionalMathBuiltins.atan(1));
        assertEquals(180f, AdditionalMathBuiltins.lerpRotate(170, -170, 0.5f));
        assertEquals(1f, AdditionalMathBuiltins.hermiteBlend(0.5f));
        assertEquals(AdditionalMathBuiltins.hermiteBlend(0.5f), AdditionalMathBuiltins.hermite(0.5f));
    }
}
