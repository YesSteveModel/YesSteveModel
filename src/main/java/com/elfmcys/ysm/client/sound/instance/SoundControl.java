package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.api.client.audio.AudioBackend;
import com.elfmcys.ysm.config.ClientConfig;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

/** Author-owned control values and the entity used to produce immutable runtime snapshots. */
public final class SoundControl implements PlaybackControl {
    private final Entity entity;
    private float configuredVolume = 1.0f;
    private float pitch = 1.0f;
    private boolean looping;
    private boolean relative;

    public SoundControl(Entity entity) {
        this.entity = Objects.requireNonNull(entity, "entity");
    }

    public void setConfiguredVolume(float volume) {
        configuredVolume = volume;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
    }

    public void setAsUI() {
        relative = true;
    }

    @Override
    public boolean isLooping() {
        return looping;
    }

    @Override
    public boolean isRetired() {
        return entity.isRemoved();
    }

    Entity entity() {
        return entity;
    }

    void apply(MinecraftSoundInstance instance) {
        instance.setConfiguredVolume(configuredVolume);
        instance.setPitch(pitch);
        instance.setLooping(looping);
        if (relative) {
            instance.setAsUI();
        }
    }

    @Override
    public AudioBackend.Control snapshot(boolean paused) {
        return new AudioBackend.Control(configuredVolume, ClientConfig.SOUND_VOLUME.get().floatValue() / 100.0f, pitch,
            entity.getX(), entity.getY(), entity.getZ(), relative, looping, paused);
    }
}
