package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import org.jetbrains.annotations.Nullable;

/** Minecraft-facing prepared-stream adapter used only by the default backend. */
public interface AudioHost {
    void execute(Runnable task);

    boolean paused();

    @Nullable
    HostPlayback submit(CustomAudioStream stream, PlaybackControl control);

    interface HostPlayback {
        boolean active();

        void stopPlayback();
    }
}
