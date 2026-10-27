package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import dev.semaweave.ProgramShape;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.ResourceGenerationToken;

import java.util.Objects;

/** Immutable numeric animation input: either a scalar literal or an exact-generation program. */
public sealed interface FloatExpression {
    record Literal(float value) implements FloatExpression {
    }

    record Program(ModelProgramInventory.SemanticLocation location, ProgramRef reference,
        ResourceGenerationToken generation) implements FloatExpression, EventProgram {
        public Program {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(generation, "generation");
            if (reference.shape() != ProgramShape.F32 || location.role().shape() != ProgramShape.F32) {
                throw new IllegalArgumentException("float program shape mismatch at " + location);
            }
        }
    }

    record Scaled(FloatExpression source, float factor) implements FloatExpression {
        public Scaled {
            Objects.requireNonNull(source, "source");
        }
    }

    static FloatExpression scaled(FloatExpression source, float factor) {
        Objects.requireNonNull(source, "source");
        if (source instanceof Literal literal) {
            return new Literal(literal.value() * factor);
        }
        return factor == 1.0f ? source : new Scaled(source, factor);
    }
}
