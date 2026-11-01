package com.elfmcys.ysm.api.molang;

import net.minecraft.world.entity.Entity;

/** Borrowed only for the synchronous provider call. */
public interface MolangContextView<E extends Entity> {
    E subject();

    MolangInputs inputs();

    void submit(EffectRequest request);
}
