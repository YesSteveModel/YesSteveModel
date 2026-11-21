package com.elfmcys.ysm.format.schema.baked.asset;

import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.model.domain.Hash256;
import dev.semaweave.ProgramKey;
import java.util.Objects;

/** Immutable source identity and file-local locator for one unbound program envelope. */
public record ProgramInputDescriptor(int id, ModelProgramInventory.SemanticLocation location, ProgramKey key,
    Hash256 contentHash, int byteLength) {
    public ProgramInputDescriptor {
        if (id < 0 || byteLength < 0)
            throw new IllegalArgumentException("Invalid program input locator or length");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contentHash, "contentHash");
    }
}
