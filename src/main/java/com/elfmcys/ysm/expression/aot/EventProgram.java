package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.ResourceGenerationToken;

/** Closed exact-generation binding for one admitted event entry. */
public sealed interface EventProgram permits FloatExpression.Program, VoidProgram {
    ModelProgramInventory.SemanticLocation location();

    ProgramBinding binding();

    default ProgramRef reference() {
        return binding().aotReference();
    }

    default ResourceGenerationToken generation() {
        return binding().aotGeneration();
    }
}
