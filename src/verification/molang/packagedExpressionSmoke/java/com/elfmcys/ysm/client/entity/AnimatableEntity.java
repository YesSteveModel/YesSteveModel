package com.elfmcys.ysm.client.entity;

/** Packaged linkage fixture; actual owner scheduling is verified in the owner integration tests. */
public final class AnimatableEntity<E extends net.minecraft.world.entity.Entity> {
    private final E entity;
    public AnimatableEntity(E entity) { this.entity = entity; }
    public E getEntity() { return entity; }
}
