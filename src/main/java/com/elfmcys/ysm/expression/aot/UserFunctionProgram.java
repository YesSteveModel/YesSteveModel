package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import dev.semaweave.ProgramShape;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.ResourceGenerationToken;

import java.util.Objects;

/** Catalog identity for a user function resolved inside the complete compiled unit. */
public record UserFunctionProgram(ModelProgramInventory.SemanticLocation location, ProgramRef reference,
    ResourceGenerationToken generation) {
    public UserFunctionProgram {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(generation, "generation");
        if (reference.shape() != ProgramShape.USER_FUNCTION || location.role().shape() != ProgramShape.USER_FUNCTION) {
            throw new IllegalArgumentException("user-function shape mismatch at " + location);
        }
    }
}
