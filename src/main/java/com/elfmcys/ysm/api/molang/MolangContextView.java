package com.elfmcys.ysm.api.molang;

import net.minecraft.world.entity.Entity;

/** Borrowed only for the synchronous provider call. */
public interface MolangContextView<E extends Entity> {
    E subject();

    MolangInputs inputs();

    SubjectSampleView subjectSample();

    /** Copies finite arguments for a handler at the end of the current animation context. */
    void defer(float delay, float... values);
}
