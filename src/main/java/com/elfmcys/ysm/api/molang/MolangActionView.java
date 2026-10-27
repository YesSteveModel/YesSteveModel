package com.elfmcys.ysm.api.molang;

import net.minecraft.world.entity.Entity;

/** Business actions still require the active expression's fixed permission. */
public interface MolangActionView<E extends Entity> extends MolangContextView<E> {
    void playSound(String id, String name, int mode, float volume, float pitch);

    void playSound(float id, String name, int mode, float volume, float pitch);

    void stopSound(String id, boolean global);

    void stopSound(float id, boolean global);

    void stopAllSounds(boolean global);

    void setAnimation(String name, float loop);

    void resetController();

    void indicateReload();

    void setBeginningTransitionLength(float length);

    void boneColor(String name, float red, float green, float blue);

    void boneGlow(String name, float glow);

    void boneTransparency(String name, float alpha);

    void sync(float... values);

    void particle(String name, float... values);

    void absoluteParticle(String name, float... values);

    void defer(float delay, float... values);
}
