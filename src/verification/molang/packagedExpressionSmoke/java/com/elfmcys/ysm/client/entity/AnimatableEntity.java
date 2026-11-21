package com.elfmcys.ysm.client.entity;

/** Packaged linkage fixture; actual owner scheduling is verified in the owner integration tests. */
public final class AnimatableEntity<E extends net.minecraft.world.entity.Entity> {
    private final E entity;
    private final com.elfmcys.ysm.expression.host.SubjectSample.Draft sample =
        new com.elfmcys.ysm.expression.host.SubjectSample.Draft();

    public AnimatableEntity(E entity) {
        this.entity = entity;
    }

    public E getEntity() {
        return entity;
    }

    public com.elfmcys.ysm.expression.host.SubjectSample.Draft subjectSampleDraft() {
        return sample;
    }
}
