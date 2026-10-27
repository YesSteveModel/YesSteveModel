package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.api.client.audio.AudioBackend;

public interface ICanStopSound {
    void setStopped();

    default void setStopped(AudioBackend.CancelReason reason) {
        setStopped();
    }

    // A distinct name keeps the playback contract outside Minecraft's method mappings.
    boolean isPlaybackStopped();
}
