package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import dev.semaweave.ProgramShape;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.ResourceGenerationToken;

import java.util.Objects;

/** Immutable exact-generation binding for an effect-capable void program. */
public record VoidProgram(ModelProgramInventory.SemanticLocation location,
    ProgramBinding binding) implements EventProgram {
    public VoidProgram(ModelProgramInventory.SemanticLocation location, ProgramRef reference,
        ResourceGenerationToken generation) {
        this(location, new ProgramBinding.Aot(reference, generation));
    }

    public VoidProgram {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(binding, "binding");
        if (binding.shape() != ProgramShape.VOID || location.role().shape() != ProgramShape.VOID) {
            throw new IllegalArgumentException("void program shape mismatch at " + location);
        }
    }
}
