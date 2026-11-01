package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.ArrayStorage;
import com.elfmcys.ysm.model.catalog.content.ModelContent;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageEncoder;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.util.ScopeGuard;

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
    private final java.util.Map<Hash256, Version> versions = new java.util.HashMap<>();

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

    /** Observes replacement authority without reading image bytes. */
    public synchronized Version observe(Hash256 containerId) {
        return versions.computeIfAbsent(containerId, ignored -> new Version());
    }

    public synchronized boolean current(Hash256 containerId, Version version) {
        var current = versions.get(containerId);
        return current != null && version != null && (current == version || current == version.basis);
    }

    private synchronized void replaced(Hash256 containerId, EncodedPreview preview) {
        var version = new Version();
        versions.put(containerId, version);
        preview.version = version;
    }

    public static final class Version {
        private final Version basis;

        private Version() {
            this(null);
        }

        private Version(Version basis) {
            this.basis = basis;
        }
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
    public Optional<ArrayStorage> readEncoded(Hash256 containerId) throws IOException {
        var path = previewPath(Objects.requireNonNull(containerId, "containerId"));
        Optional<ArrayStorage> stored = Optional.empty();
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
            stored.ifPresent(ArrayStorage::close);
            return Optional.empty();
        } catch (IOException failure) {
            stored.ifPresent(ArrayStorage::close);
            throw AssetLoadException.access("Failed to read guarded preview cache", failure);
        } catch (RuntimeException | Error failure) {
            stored.ifPresent(ArrayStorage::close);
            throw failure;
        }
    }

    static Optional<ArrayStorage> readSnapshot(SeekableByteChannel input) throws IOException {
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
        var version = observe(containerId);
        var stored = readEncoded(containerId);
        if (stored.isEmpty())
            return Optional.empty();
        var bytes = SharedBuffer.adopt(stored.orElseThrow());
        try {
            var result = read(() -> Image.probe(bytes), bytes.size());
            result.version(version);
            return Optional.of(result);
        } catch (AssetLoadException invalidMedia) {
            if (invalidMedia.reason() != AssetLoadException.Reason.CONTENT)
                throw invalidMedia;
            return Optional.empty();
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
        var observed = new java.util.concurrent.atomic.AtomicReference<Version>();
        var original = observe(containerId);
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            cache.materializeReplacing("preview", containerId.toString(), target, path -> {
                try {
                    var same = Arrays.equals(readBounded(path), expected);
                    if (same)
                        observed.set(observe(containerId));
                    return same;
                } catch (IOException invalid) {
                    return false;
                }
            }, file -> file.writeBytes(expected), replacement -> {
                synchronized (this) {
                    replacement.run();
                    replaced(containerId, preview);
                    observed.set(preview.version());
                    committed.set(true);
                }
            });
            preview.version(observed.get());
            notifyStored(containerId);
        } catch (IOException | RuntimeException failure) {
            // Usable memory content must not impersonate the unchanged persistent observation.
            if (!committed.get())
                preview.version(new Version(original));
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
        var encoded = preview.retainBytes();
        try {
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
            }, replacement -> gate.commit(() -> {
                synchronized (this) {
                    replacement.run();
                    replaced(containerId, preview);
                }
            }));
            notifyStored(containerId);
        } finally {
            java.lang.ref.Reference.reachabilityFence(encoded);
        }
    }

    /** Standalone declared images keep their own media policy, without preview-cache bounds. */
    public static EncodedPreview prepareTexture(ImageSource source) throws IOException {
        try (var image = source.open()) {
            if (image.format() == Image.Format.RGBA) {
                try (var encoded = ImageEncoder.encodeLossless(image.data().shareNative(), image.width(), image.height())) {
                    return prepareTexture(() -> new Image(encoded.format(), encoded.width(), encoded.height(), encoded.data()));
                }
            }
            var owner = new PreviewPixels.Owner();
            var decoded = image.decodeToBuffer();
            PreviewPixels pixels;
            try {
                pixels = owner.adopt(decoded, image.width(), image.height());
            } catch (Throwable failure) {
                decoded.close();
                throw failure;
            }
            try {
                return new EncodedPreview(image.data(), image.format(), image.width(), image.height(), pixels, owner);
            } catch (Throwable failure) {
                pixels.close();
                throw failure;
            }
        }
    }

    public static EncodedPreview read(ImageSource source, int encodedSize) throws IOException {
        try (var pending = pending(source, encodedSize)) {
            return pending.verify(null, false);
        }
    }

    /** Consumes the opened image; its completed bytes must remain unmodified. */
    public static PendingPreview pending(ImageSource source, int encodedSize) throws IOException {
        Objects.requireNonNull(source, "source");
        validateStoredSize(encodedSize);
        try {
            return pending(source.open());
        } catch (UnsupportedEncodingException invalidMedia) {
            throw AssetLoadException.content("Standalone preview media is invalid", invalidMedia);
        }
    }

    /** Consumes the image on success and failure; the caller must stop using all mutable aliases. */
    public static PendingPreview pending(Image image) throws IOException {
        try (var scope = ScopeGuard.create(image)) {
            validateDimensions(image.width(), image.height());
            if (image.format() == Image.Format.RGBA) {
                var pixels = image.data().shareNative();
                return pending(ImageEncoder.encodeLossless(pixels, image.width(), image.height()));
            }
            validateStoredSize(image.data().size());
            var result = new PendingPreview(image);
            scope.release();
            return result;
        }
    }

    /** Copies mutable or borrowed input before establishing any media qualification. */
    public static PendingPreview pending(UniBuffer bytes) throws IOException {
        validateStoredSize(bytes.size());
        try {
            var image = Image.probe(SharedBuffer.copyOf(bytes));
            if (image.format() == Image.Format.RGBA) {
                image.close();
                throw AssetLoadException.content("Standalone preview must use a self-describing encoding");
            }
            return pending(image);
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
        private volatile Version version;

        private PendingPreview(Image image) {
            this.image = image;
        }

        public synchronized PendingPreview version(Version version) {
            this.version = version;
            return this;
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
                    var result =
                        new EncodedPreview(image.data(), image.format(), image.width(), image.height(), pixels, owner);
                    result.version = version;
                    pixels = null;
                    return result;
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
        private SharedBuffer bytes;
        private final Image.Format format;
        private final int width;
        private final int height;
        private PreviewPixels pixels;
        private final PreviewPixels.Owner pixelOwner;
        private boolean closed;
        private volatile Version version;

        private EncodedPreview(SharedBuffer bytes, Image.Format format, int width, int height, PreviewPixels pixels,
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
        public synchronized BufferStorage acquireBytes() {
            requireOpen();
            return bytes.copy();
        }

        private synchronized SharedBuffer retainBytes() {
            requireOpen();
            return bytes;
        }

        public synchronized EncodedPreview acquire() {
            requireOpen();
            var result = new EncodedPreview(bytes, format, width, height, pixels == null ? null : pixels.acquire(),
                pixelOwner);
            result.version = version;
            return result;
        }

        public synchronized EncodedPreview acquireContent() {
            requireOpen();
            var result = new EncodedPreview(bytes, format, width, height, null, pixelOwner);
            result.version = version;
            return result;
        }

        public synchronized Version version() {
            return version;
        }

        /** The operation passes its read-time observation through media verification. */
        public synchronized void version(Version version) {
            this.version = version;
        }

        public synchronized void preparePixels(PreviewPixels.Owner owner) throws IOException {
            requireOpen();
            if (pixels != null)
                return;
            try (var image = open()) {
                var decoded = image.decodeToBuffer();
                try {
                    pixels = owner.adopt(decoded, width, height);
                } catch (Throwable failure) {
                    decoded.close();
                    throw failure;
                }
            }
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
            bytes = null;
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
            return Image.adopt(format, width, height, acquireBytes());
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
