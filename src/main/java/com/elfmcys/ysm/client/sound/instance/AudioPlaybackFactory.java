package com.elfmcys.ysm.client.sound.instance;

import org.jetbrains.annotations.Nullable;

/** Creates one accepted model-audio playback from a frozen author control object. */
@FunctionalInterface
public interface AudioPlaybackFactory {
    @Nullable
    ICanStopSound play(PlaybackControl control);
}
