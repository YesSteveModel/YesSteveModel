package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.catalog.content.ModelContent;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageEncoder;
import com.elfmcys.ysm.natives.image.ImageSource;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Independently replaceable preview images, weakly associated by container id only. */
public final class PreviewStore implements PreviewStorage {
    public static final int MAX_STORED_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DIMENSION = 4_096;
    public static final long MAX_PIXELS = 16L * 1024 * 1024;

    private static final String PREVIEW_SUFFIX = ".image";

    /** Independent preview images; weakly associated by container id. */
    private static final String PREVIEW_DIRECTORY = "previews";

    private final Path cacheRoot;
    private final AtomicSharedCache cache;
    private Consumer<Hash256> storedObserver;

    public PreviewStore(Path cacheRoot, AtomicSharedCache cache, Consumer<Hash256> storedObserver) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        this.cache = Objects.requireNonNull(cache, "cache");
        this.storedObserver = Objects.requireNonNull(storedObserver, "storedObserver");
    }

    public PreviewStore(Path cacheRoot, Consumer<Hash256> storedObserver) {
        this(cacheRoot, new AtomicSharedCache(cacheRoot), storedObserver);
    }

    public PreviewStore(Path cacheRoot, AtomicSharedCache cache) {
        this(cacheRoot, cache, ignored -> {
        });
    }

    public PreviewStore(Path cacheRoot) {
        this(cacheRoot, new AtomicSharedCache(cacheRoot));
    }

    @Override
    public PreviewStore forContent(ModelContent content) {
        return this;
    }

    public void storedObserver(Consumer<Hash256> observer) {
        storedObserver = Objects.requireNonNull(observer);
    }

    private Path previewPath(Hash256 containerId) {
        return cacheRoot.resolve(PREVIEW_DIRECTORY).resolve(containerId + PREVIEW_SUFFIX);
    }

    public Optional<EncodedPreview> load(Hash256 containerId) {
        Objects.requireNonNull(containerId, "containerId");
        var path = previewPath(containerId);
        try {
            return probe(containerId);
        } catch (IOException | RuntimeException invalid) {
            YesSteveModel.LOGGER.debug("Ignoring invalid preview cache entry {}", path, invalid);
            return Optional.empty();
        }
    }

    /** Returns owned encoded bytes without interpreting media; access failures remain failures. */
    public Optional<ArrayBuffer> readEncoded(Hash256 containerId) throws IOException {
        var path = previewPath(Objects.requireNonNull(containerId, "containerId"));
        Optional<ArrayBuffer> stored = Optional.empty();
        try {
            var parent = cache.observe(path.resolveSibling(".guard"), false);
            var fixed = parent.target().getParent().resolve(path.getFileName());
            var attributes = Files.readAttributes(fixed, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther())
                return Optional.empty();
            try (var input = cache.openRead(fixed)) {
                parent.verify();
                stored = readSnapshot(input);
            }
            return stored;
        } catch (NoSuchFileException absent) {
            stored.ifPresent(ArrayBuffer::close);
            return Optional.empty();
        } catch (IOException failure) {
            stored.ifPresent(ArrayBuffer::close);
            throw AssetLoadException.access("Failed to read guarded preview cache", failure);
        } catch (RuntimeException | Error failure) {
            stored.ifPresent(ArrayBuffer::close);
            throw failure;
        }
    }

    static Optional<ArrayBuffer> readSnapshot(SeekableByteChannel input) throws IOException {
        var size = input.size();
        if (size <= 0 || size > MAX_STORED_BYTES)
            return Optional.empty();
        var bytes = ArrayBuffer.allocate((int)size);
        try {
            var data = bytes.nio();
            while (data.hasRemaining()) {
                if (input.read(data) < 0)
                    throw new IOException("Preview changed during its encoded read");
            }
            if (input.size() != size || input.read(ByteBuffer.allocate(1)) != -1)
                throw new IOException("Preview changed during its encoded read");
            return Optional.of(bytes);
        } catch (Throwable failure) {
            bytes.close();
            throw failure;
        }
    }

    /** Management must distinguish an inaccessible cache from a successfully observed miss. */
    public Optional<EncodedPreview> probe(Hash256 containerId) throws IOException {
        var stored = readEncoded(containerId);
        if (stored.isEmpty())
            return Optional.empty();
        try (var bytes = stored.orElseThrow()) {
            try {
                return Optional.of(decode(bytes));
            } catch (AssetLoadException invalidMedia) {
                if (invalidMedia.reason() != AssetLoadException.Reason.CONTENT)
                    throw invalidMedia;
                return Optional.empty();
            }
        }
    }

    /** Returns the valid in-memory image even when optional cache persistence fails. */
    public EncodedPreview accept(Hash256 containerId, byte[] bytes) throws IOException {
        return accept(containerId, ArrayBuffer.borrow(bytes));
    }

    /** Returns the valid in-memory image even when optional cache persistence fails. */
    public EncodedPreview accept(Hash256 containerId, UniBuffer bytes) throws IOException {
        Objects.requireNonNull(containerId, "containerId");
        var preview = decode(bytes);
        accept(containerId, preview);
        return preview;
    }

    public void accept(Hash256 containerId, EncodedPreview preview) throws IOException {
        Objects.requireNonNull(containerId, "containerId");
        var expected = preview.bytes();
        var target = previewPath(containerId);
        try {
            cache.materializeReplacing("preview", containerId.toString(), target, path -> {
                try {
                    return Arrays.equals(readBounded(path), expected);
                } catch (IOException invalid) {
                    return false;
                }
            }, file -> file.writeBytes(expected));
            notifyStored(containerId);
        } catch (IOException | RuntimeException failure) {
            YesSteveModel.LOGGER.warn("Failed to persist usable preview cache entry {}", target, failure);
        }
    }

    private void notifyStored(Hash256 containerId) {
        try {
            storedObserver.accept(containerId);
        } catch (RuntimeException failure) {
            YesSteveModel.LOGGER.warn("Failed to notify preview storage observers", failure);
        }
    }

    /** Success means a validated, persistent replacement passed the caller's effect gate. */
    public void commit(Hash256 containerId, UniBuffer bytes, AtomicSharedCache.CommitGate gate) throws IOException {
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(gate, "gate");
        try (var preview = decode(bytes)) {
            commit(containerId, preview, gate);
        }
    }

    public void commit(Hash256 containerId, EncodedPreview preview, AtomicSharedCache.CommitGate gate)
        throws IOException {
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(gate, "gate");
        try (var encoded = preview.retainBytes()) {
            cache.replaceValidated("preview", containerId.toString(), previewPath(containerId), path -> {
                try (var input = path; var scratch = ArrayBuffer.allocate(24 * 1024)) {
                    if (input.size() != encoded.size())
                        return false;
                    for (var offset = 0; offset < encoded.size();) {
                        var length = Math.min(scratch.size(), encoded.size() - offset);
                        var data = scratch.nio();
                        data.limit(length);
                        while (data.hasRemaining()) {
                            if (input.read(data) < 0)
                                return false;
                        }
                        if (!com.elfmcys.ysm.buffer.UniBufferIO.equals(encoded, offset, scratch, 0, length))
                            return false;
                        offset += length;
                    }
                    return true;
                }
            }, path -> {
                try (var output = path) {
                    var data = encoded.nio();
                    while (data.hasRemaining())
                        output.write(data);
                }
            }, gate);
            notifyStored(containerId);
        }
    }

    public static EncodedPreview read(ImageSource source, int encodedSize) throws IOException {
        try (var pending = pending(source, encodedSize)) {
            return pending.verify(null, false);
        }
    }

    /** Copies raw input before establishing any media qualification. */
    public static PendingPreview pending(ImageSource source, int encodedSize) throws IOException {
        Objects.requireNonNull(source, "source");
        validateStoredSize(encodedSize);
        try (var image = source.open()) {
            validateDimensions(image.width(), image.height());
            if (image.format() == Image.Format.RGBA) {
                try (var pixels = image.data().acquireNative();
                    var encoded = ImageEncoder.encodeLossless(pixels, image.width(), image.height())) {
                    return pending(encoded.data());
                }
            }
            validateStoredSize(image.data().size());
            return new PendingPreview(new Image(image.format(), image.width(), image.height(), image.data().copy()));
        }
    }

    public static PendingPreview pending(UniBuffer bytes) throws IOException {
        validateStoredSize(bytes.size());
        try (var fixed = bytes.copy(); var image = Image.probe(fixed)) {
            if (image.format() == Image.Format.RGBA)
                throw AssetLoadException.content("Standalone preview must use a self-describing encoding");
            validateDimensions(image.width(), image.height());
            return new PendingPreview(image.share());
        } catch (UnsupportedEncodingException invalidMedia) {
            throw AssetLoadException.content("Standalone preview media is invalid", invalidMedia);
        }
    }

    public static void validate(ImageSource source, int encodedSize) throws IOException {
        try (var ignored = read(source, encodedSize)) {
            // Validation has no display use.
        }
    }

    public static EncodedPreview decode(byte[] bytes) throws IOException {
        return decode(ArrayBuffer.borrow(Objects.requireNonNull(bytes, "bytes")));
    }

    public static EncodedPreview decode(UniBuffer bytes) throws IOException {
        try (var pending = pending(bytes)) {
            return pending.verify(null, false);
        }
    }

    /** A sealed input with probed dimensions; only verify can create qualified content. */
    public static final class PendingPreview implements AutoCloseable {
        private final Image image;
        private boolean closed;

        private PendingPreview(Image image) {
            this.image = image;
        }

        public synchronized EncodedPreview verify(PreviewPixels.Owner owner, boolean display) throws IOException {
            if (closed)
                throw new IllegalStateException("Preview input is closed");
            if (display && owner == null)
                throw new IllegalArgumentException("Display pixels require an owner");
            try {
                var decoded = image.decodeToBuffer();
                PreviewPixels pixels = null;
                try {
                    if (display) {
                        pixels = owner.adopt(decoded, image.width(), image.height());
                        decoded = null;
                    }
                    try (var content = com.elfmcys.ysm.util.ScopeGuard.create(image.data().acquire())) {
                        var result = new EncodedPreview(content.get(), image.format(), image.width(), image.height(),
                            pixels, owner);
                        content.release();
                        pixels = null;
                        return result;
                    }
                } finally {
                    if (pixels != null)
                        pixels.close();
                    if (decoded != null)
                        decoded.close();
                }
            } catch (UnsupportedEncodingException invalidMedia) {
                throw AssetLoadException.content("Standalone preview media is invalid", invalidMedia);
            }
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                image.close();
            }
        }
    }

    private static void validateDimensions(int width, int height) throws IOException {
        final long pixels;
        try {
            pixels = Math.multiplyExact((long)width, height);
        } catch (ArithmeticException overflow) {
            throw AssetLoadException.content("Preview dimensions overflow", overflow);
        }
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION || pixels > MAX_PIXELS) {
            throw AssetLoadException.content("Preview dimensions exceed their bound: " + width + "x" + height);
        }
    }

    private static void validateStoredSize(int size) throws IOException {
        if (size <= 0 || size > MAX_STORED_BYTES) {
            throw AssetLoadException.content("Preview encoded size exceeds its bound");
        }
    }

    private byte[] readBounded(CacheFile file) throws IOException {
        try (var input = file.inputStream()) {
            var bytes = input.readNBytes(MAX_STORED_BYTES + 1);
            validateStoredSize(bytes.length);
            return bytes;
        }
    }

    public static final class EncodedPreview implements ImageSource, AutoCloseable {
        private final UniBuffer bytes;
        private final Image.Format format;
        private final int width;
        private final int height;
        private PreviewPixels pixels;
        private final PreviewPixels.Owner pixelOwner;
        private boolean closed;

        private EncodedPreview(UniBuffer bytes, Image.Format format, int width, int height, PreviewPixels pixels,
            PreviewPixels.Owner pixelOwner) {
            this.bytes = bytes;
            this.format = format;
            this.width = width;
            this.height = height;
            this.pixels = pixels;
            this.pixelOwner = pixelOwner;
        }

        public synchronized byte[] bytes() {
            requireOpen();
            var result = new byte[bytes.size()];
            bytes.nio().get(result);
            return result;
        }

        /** External callers receive a copy, never a mutable alias of qualified content. */
        public synchronized UniBuffer acquireBytes() {
            requireOpen();
            return bytes.copy();
        }

        private synchronized UniBuffer retainBytes() {
            requireOpen();
            return bytes.acquire();
        }

        public synchronized EncodedPreview acquire() {
            requireOpen();
            return new EncodedPreview(bytes.acquire(), format, width, height, pixels == null ? null : pixels.acquire(),
                pixelOwner);
        }

        public synchronized EncodedPreview acquireContent() {
            requireOpen();
            return new EncodedPreview(bytes.acquire(), format, width, height, null, pixelOwner);
        }

        public synchronized PreviewPixels.Reload beginReload() {
            requireOpen();
            return pixelOwner == null ? null : pixelOwner.beginReload();
        }

        public synchronized PreviewPixels takePixels() {
            requireOpen();
            var result = pixels;
            pixels = null;
            return result;
        }

        public synchronized void releasePixels() {
            if (pixels != null) {
                pixels.close();
                pixels = null;
            }
        }

        @Override
        public synchronized void close() {
            if (closed)
                return;
            closed = true;
            releasePixels();
            bytes.close();
        }

        public Image.Format format() {
            return format;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        @Override
        public synchronized Image open() {
            return new Image(format, width, height, acquireBytes());
        }

        public synchronized boolean matches(Image image) {
            requireOpen();
            return format == image.format() && width == image.width() && height == image.height()
                && bytes.size() == image.data().size()
                && com.elfmcys.ysm.buffer.UniBufferIO.equals(bytes, 0, image.data(), 0, bytes.size());
        }

        private void requireOpen() {
            if (closed)
                throw new IllegalStateException("Preview content is closed");
        }
    }
}
