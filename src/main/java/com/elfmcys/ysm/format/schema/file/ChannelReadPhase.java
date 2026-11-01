package com.elfmcys.ysm.format.schema.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A lexical owner of one admitted channel; callbacks only borrow its cursor.
 *
 * <p>Execution: concurrent reads serialize channel I/O. Admission and close are ordered separately;
 * close seals new borrowers but does not await callbacks already admitted. The last borrower after
 * sealing performs physical close. Callbacks must not export the channel or use it asynchronously.
 *
 * <p>Failure: reader or validation failure seals the phase. Physical close failure has its own channel
 * and must not be recovered as a payload miss.
 *
 * <p>Seam: maintains the source-use window for
 * [validated content reads](docs/architecture/asset-pipeline/implementation/container-and-validation.md#metadata-与内容取得).
 */
public final class ChannelReadPhase implements AutoCloseable {
    private final SeekableByteChannel channel;
    private final Validator validator;
    private final Runnable admitBorrow;
    private final Object io = new Object();
    private boolean sealed;
    private int borrowers;

    private ChannelReadPhase(SeekableByteChannel channel, Validator validator, Runnable admitBorrow) {
        this.channel = channel;
        this.validator = validator;
        this.admitBorrow = admitBorrow;
    }

    /**
     * Opens and validates one channel, taking its close responsibility. Validation failure closes
     * the newly opened channel before propagation; neither callback may retain the borrowed cursor.
     */
    public static ChannelReadPhase open(FileChunkDataSource.ChannelOpener opener, Validator validator)
        throws IOException {
        return open(opener, validator, () -> {
        });
    }

    static ChannelReadPhase open(FileChunkDataSource.ChannelOpener opener, Validator validator, Runnable admitBorrow)
        throws IOException {
        Objects.requireNonNull(validator);
        var channel = opener.open();
        try {
            validator.validate(channel);
            return new ChannelReadPhase(channel, validator, admitBorrow);
        } catch (IOException | RuntimeException | Error failure) {
            closeChannel(channel, failure);
            throw failure;
        }
    }

    public <T> T read(Reader<T> reader) throws IOException {
        return read(reader, ignored -> {
        });
    }

    /**
     * Runs reader between source validations while exclusively borrowing the channel cursor.
     *
     * <p>Ownership: normal return transfers the result to the caller. If a produced result cannot be
     * returned, discard receives it. The callback owns partial resources that it has not returned.
     * Reader and discard run inline; discard must release the result without throwing.
     *
     * @throws java.nio.channels.ClosedChannelException if the phase was sealed before admission
     */
    public <T> T read(Reader<T> reader, Consumer<T> discard) throws IOException {
        final Borrow borrow;
        synchronized (this) {
            if (sealed)
                throw new ClosedChannelException();
            try {
                admitBorrow.run();
                borrow = new Borrow(channel);
            } catch (RuntimeException | Error failure) {
                sealed = true;
                if (borrowers == 0)
                    closeChannel(channel, failure);
                throw failure;
            }
            borrowers++;
        }
        T result = null;
        Throwable failed = null;
        try {
            synchronized (io) {
                validator.validate(channel);
                result = reader.read(borrow);
                validator.validate(channel);
                return result;
            }
        } catch (IOException | RuntimeException | Error failure) {
            failed = failure;
            synchronized (this) {
                sealed = true;
            }
            if (result != null) {
                discard.accept(result);
                result = null;
            }
            throw failure;
        } finally {
            borrow.active = false;
            boolean release;
            synchronized (this) {
                release = --borrowers == 0 && sealed;
            }
            if (release) {
                try {
                    closeChannel(channel, failed);
                } catch (IOException cleanup) {
                    if (result != null)
                        discard.accept(result);
                    throw cleanup;
                }
            }
        }
    }

    /** Seals new reads; repeated close is harmless but does not join already admitted callbacks. */
    @Override
    public void close() throws IOException {
        boolean release;
        synchronized (this) {
            if (sealed)
                return;
            sealed = true;
            release = borrowers == 0;
        }
        if (release)
            closeChannel(channel, null);
    }

    private static void closeChannel(SeekableByteChannel channel, Throwable original) throws IOException {
        try {
            channel.close();
        } catch (IOException | RuntimeException cleanup) {
            var failure = new CloseFailure(cleanup);
            if (original instanceof java.util.concurrent.CancellationException) {
                original.addSuppressed(failure);
                return;
            }
            if (original != null)
                failure.addSuppressed(original);
            throw failure;
        }
    }

    /** Physical close failure cannot be recovered as an ordinary payload miss. */
    public static final class CloseFailure extends IOException {
        private CloseFailure(Throwable cause) {
            super("Failed to close a read channel", cause);
        }
    }

    @FunctionalInterface
    public interface Reader<T> {
        T read(SeekableByteChannel channel) throws IOException;
    }

    @FunctionalInterface
    public interface Validator {
        void validate(SeekableByteChannel channel) throws IOException;
    }

    private static final class Borrow implements SeekableByteChannel {
        private final SeekableByteChannel channel;
        private volatile boolean active = true;

        private Borrow(SeekableByteChannel channel) {
            this.channel = channel;
        }

        private void requireActive() throws ClosedChannelException {
            if (!active)
                throw new ClosedChannelException();
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            requireActive();
            return channel.read(target);
        }

        @Override
        public long position() throws IOException {
            requireActive();
            return channel.position();
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            requireActive();
            channel.position(position);
            return this;
        }

        @Override
        public long size() throws IOException {
            requireActive();
            return channel.size();
        }

        @Override
        public boolean isOpen() {
            return active && channel.isOpen();
        }

        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public void close() {
            // A callback cannot revoke another borrow or the phase owner's channel.
            active = false;
        }
    }
}
