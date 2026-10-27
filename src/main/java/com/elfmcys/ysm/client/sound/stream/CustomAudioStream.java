package com.elfmcys.ysm.client.sound.stream;

import org.jetbrains.annotations.NotNull;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Codec stream independent of the Minecraft client sound engine. */
public interface CustomAudioStream extends Closeable {
    @NotNull
    AudioFormat getFormat();

    @NotNull
    ByteBuffer read(int capacity) throws IOException;

    boolean isClosed();
}
