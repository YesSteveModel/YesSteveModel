package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.util.DirectoryAccess;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;

/** Borrowed file I/O; its cache operation owns creation, commit and close. */
public final class CacheFile implements SeekableByteChannel {
    private final DirectoryAccess parent;
    private final String name;
    private FileChannel channel;
    private Object identity;
    private boolean released;

    CacheFile(DirectoryAccess parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    CacheFile(FileChannel channel) {
        this.parent = null;
        this.name = null;
        this.channel = channel;
    }

    public static CacheFile borrow(FileChannel channel) {
        return new CacheFile(channel);
    }

    String name() { return name; }

    Object identity() { return identity; }

    FileChannel ownedChannel() throws IOException {
        if (released)
            throw new java.nio.channels.ClosedChannelException();
        if (channel == null) {
            channel = parent.file(name, true, true, true);
            identity = parent.fileIdentity(name);
        }
        return channel;
    }

    void release() throws IOException {
        released = true;
        if (channel != null)
            channel.close();
    }

    void delete() throws IOException {
        if (identity != null)
            parent.delete(name, identity, false);
    }

    public InputStream inputStream() throws IOException {
        position(0);
        return Channels.newInputStream(this);
    }

    public OutputStream outputStream() throws IOException {
        position(0);
        truncate(0);
        return Channels.newOutputStream(this);
    }

    public byte[] readNBytes(int maximum) throws IOException {
        return inputStream().readNBytes(maximum);
    }

    public byte[] readAllBytes() throws IOException {
        return inputStream().readAllBytes();
    }

    public void writeBytes(byte[] bytes) throws IOException {
        var data = ByteBuffer.wrap(bytes);
        while (data.hasRemaining())
            write(data);
    }

    public void force() throws IOException {
        ownedChannel().force(true);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return ownedChannel().read(dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return ownedChannel().write(src);
    }

    @Override
    public long position() throws IOException {
        return ownedChannel().position();
    }

    @Override
    public CacheFile position(long value) throws IOException {
        ownedChannel().position(value);
        return this;
    }

    @Override
    public long size() throws IOException {
        return ownedChannel().size();
    }

    @Override
    public CacheFile truncate(long value) throws IOException {
        ownedChannel().truncate(value);
        return this;
    }

    @Override
    public boolean isOpen() {
        return !released && (channel == null || channel.isOpen());
    }

    @Override
    public void close() { /* The operation retains the same object through validation and commit. */ }
}
