package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.api.client.audio.AudioBackend;

/** Runtime-facing author control source, independent of the Minecraft host adapter. */
public interface PlaybackControl {
    boolean isLooping();

    boolean isRetired();

    AudioBackend.Control snapshot(boolean paused);
}
