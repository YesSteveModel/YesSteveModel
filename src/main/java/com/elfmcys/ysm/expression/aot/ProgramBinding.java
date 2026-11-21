package com.elfmcys.ysm.expression.aot;

import dev.semaweave.ProgramShape;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.ResourceGenerationToken;
import java.util.Objects;

/** A source reference cannot retain an interpreted program or its environment. */
public sealed interface ProgramBinding permits ProgramBinding.Aot, ProgramBinding.Interpreted {
    ProgramShape shape();

    Object executionGeneration();

    int programId();

    record Aot(ProgramRef reference, ResourceGenerationToken generation) implements ProgramBinding {
        public Aot {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(generation, "generation");
        }

        @Override
        public ProgramShape shape() {
            return reference.shape();
        }

        @Override
        public Object executionGeneration() {
            return generation;
        }

        @Override
        public int programId() {
            return reference.sourceId();
        }
    }

    record Interpreted(Object generation, int sourceId, ProgramShape shape) implements ProgramBinding {
        public Interpreted {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(shape, "shape");
            if (sourceId < 0)
                throw new IllegalArgumentException("sourceId must be nonnegative");
        }

        @Override
        public Object executionGeneration() {
            return generation;
        }

        @Override
        public int programId() {
            return sourceId;
        }
    }

    default ProgramRef aotReference() {
        if (this instanceof Aot aot)
            return aot.reference();
        throw new IllegalStateException("interpreted programs have no AOT reference");
    }

    default ResourceGenerationToken aotGeneration() {
        if (this instanceof Aot aot)
            return aot.generation();
        throw new IllegalStateException("interpreted programs have no AOT generation");
    }
}
