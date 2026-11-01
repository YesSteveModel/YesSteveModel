package com.elfmcys.ysm.buffer;

/** A fixed owner must close this allocation exactly once, or transfer it to a GC owner. */
public interface BufferStorage extends UniBuffer, AutoCloseable {
    @Override
    void close();
}
