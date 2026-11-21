package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import dev.semaweave.ProgramShape;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.ResourceGenerationToken;

import java.util.Objects;

/** Catalog identity; interpreted calls resolve the body only when the function is consumed. */
public record UserFunctionProgram(ModelProgramInventory.SemanticLocation location, ProgramBinding binding) {
    public UserFunctionProgram(ModelProgramInventory.SemanticLocation location, ProgramRef reference,
        ResourceGenerationToken generation) {
        this(location, new ProgramBinding.Aot(reference, generation));
    }

    public ProgramRef reference() {
        return binding.aotReference();
    }

    public ResourceGenerationToken generation() {
        return binding.aotGeneration();
    }

    public UserFunctionProgram {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(binding, "binding");
        if (binding.shape() != ProgramShape.USER_FUNCTION || location.role().shape() != ProgramShape.USER_FUNCTION) {
            throw new IllegalArgumentException("user-function shape mismatch at " + location);
        }
    }
}
