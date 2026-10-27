package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import com.elfmcys.ysm.init.ModSounds;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/** Public Minecraft interfaces only; it owns no decoder, cursor, or playback registry. */
public final class MinecraftAudioHost implements AudioHost {
    @Override
    public void execute(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    @Override
    public boolean paused() {
        return Minecraft.getInstance().isPaused();
    }

    @Override
    public @Nullable HostPlayback submit(CustomAudioStream stream, PlaybackControl control) {
        if (!(control instanceof SoundControl minecraftControl)) {
            throw new IllegalArgumentException("Minecraft audio requires a Minecraft sound control");
        }
        var instance = new CustomSoundInstance(ModSounds.CUSTOM, stream, minecraftControl.entity());
        minecraftControl.apply(instance);
        var sounds = Minecraft.getInstance().getSoundManager();
        sounds.play(instance);
        return sounds.isActive(instance) ? instance : null;
    }
}
