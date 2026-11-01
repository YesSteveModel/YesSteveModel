package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.buffer.NativeBuffer;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** One independently releasable use of a decoded preview backing. */
public final class PreviewPixels implements AutoCloseable {
    private final Backing backing;
    private boolean closed;

    private PreviewPixels(Backing backing) {
        this.backing = backing;
    }

    public int width() {
        return backing.width;
    }

    public int height() {
        return backing.height;
    }

    public synchronized PreviewPixels acquire() {
        if (closed)
            throw new IllegalStateException("Preview pixels are closed");
        synchronized (backing) {
            if (backing.retired || backing.owner.closed)
                throw new IllegalStateException("Preview pixel owner is closed");
            backing.references++;
        }
        return new PreviewPixels(backing);
    }

    public void borrow(Consumer<ByteBuffer> consumer) {
        synchronized (this) {
            if (closed)
                throw new IllegalStateException("Preview pixels are closed");
            synchronized (backing) {
                if (backing.retired || backing.owner.closed)
                    throw new IllegalStateException("Preview pixel owner is closed");
                backing.borrows++;
            }
        }
        try {
            var data = backing.pixels.nio();
            consumer.accept(data.asReadOnlyBuffer().order(data.order()));
        } finally {
            synchronized (backing) {
                backing.borrows--;
                backing.releaseIfUnused();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        synchronized (backing) {
            backing.references--;
            backing.releaseIfUnused();
        }
    }

    /** Retires decoded backings without turning their size into admission policy. */
    public static final class Owner implements AutoCloseable {
        private final Set<Backing> backings = new HashSet<>();
        private boolean reloading;
        private volatile boolean closed;

        /** A rejected adoption leaves buffer ownership with the caller. */
        public synchronized PreviewPixels adopt(NativeStorage pixels, int width, int height) {
            if (closed)
                throw new IllegalStateException("Preview pixel owner is closed");
            if (width <= 0 || height <= 0 || pixels.size() != (long)width * height * 4)
                throw new IllegalArgumentException("Preview pixels do not match their dimensions");
            var backing = new Backing(pixels, width, height, this);
            var result = new PreviewPixels(backing);
            backings.add(backing);
            return result;
        }

        public synchronized Reload beginReload() {
            if (closed || reloading)
                throw new IllegalStateException("Preview reload requires the serial host");
            var reload = new Reload(this);
            reloading = true;
            return reload;
        }

        private synchronized void release(Backing backing) {
            backings.remove(backing);
        }

        @Override
        public void close() {
            Set<Backing> current;
            synchronized (this) {
                closed = true;
                current = Set.copyOf(backings);
            }
            current.forEach(Backing::retire);
        }
    }

    public static final class Reload implements AutoCloseable {
        private final Owner owner;
        private boolean closed;

        private Reload(Owner owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            synchronized (owner) {
                if (!closed) {
                    closed = true;
                    owner.reloading = false;
                }
            }
        }
    }

    private static final class Backing {
        private final NativeStorage pixels;
        private final int width;
        private final int height;
        private final Owner owner;
        private int references = 1;
        private int borrows;
        private boolean retired;
        private boolean released;

        private Backing(NativeStorage pixels, int width, int height, Owner owner) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.owner = owner;
        }

        private synchronized void retire() {
            retired = true;
            releaseIfUnused();
        }

        private void releaseIfUnused() {
            if (!released && borrows == 0 && (references == 0 || retired)) {
                released = true;
                pixels.close();
                owner.release(this);
            }
        }
    }
}
