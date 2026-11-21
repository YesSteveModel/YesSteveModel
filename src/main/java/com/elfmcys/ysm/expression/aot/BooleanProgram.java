package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import dev.semaweave.ProgramShape;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.ResourceGenerationToken;

import java.util.Objects;

/** Immutable exact-generation binding for a boolean program. */
public record BooleanProgram(ModelProgramInventory.SemanticLocation location, ProgramBinding binding) {
    public BooleanProgram(ModelProgramInventory.SemanticLocation location, ProgramRef reference,
        ResourceGenerationToken generation) {
        this(location, new ProgramBinding.Aot(reference, generation));
    }

    public ProgramRef reference() {
        return binding.aotReference();
    }

    public ResourceGenerationToken generation() {
        return binding.aotGeneration();
    }

    public BooleanProgram {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(binding, "binding");
        if (binding.shape() != ProgramShape.BOOLEAN || location.role().shape() != ProgramShape.BOOLEAN) {
            throw new IllegalArgumentException("boolean program shape mismatch at " + location);
        }
    }
}
