package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.storage.PreviewPixels;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.image.ImageSource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Content associations and consumer uses; host retention and physical release stay in the manager.
 *
 * <p>Execution: lookup, claim, publication, and Use access run on the render owner. Synchronization
 * also permits preparation completion from workers; it does not permit host texture work on any thread.
 * A Preparation is single-consumer work and a Prepared result needs an ordered handoff before publication.
 *
 * <p>Seam: implements [independent image use and host retention](docs/architecture/client-presentation/implementation/page-runtime.md#页面与资源-owner) without owning model-target textures.
 */
public final class ResidentImages {
    private final Map<ImageIdentity, Entry> residents = new HashMap<>();
    private final Map<ImageIdentity, Preparation> preparing = new HashMap<>();
    private final Host host;
    private final Decoder decoder;

    public ResidentImages() {
        this(new Host() {
            public CustomTexture create(PreviewStore.EncodedPreview image) {
                return new CustomTexture(image);
            }
            public TextureHolder register(CustomTexture texture) {
                return CustomTextureManager.register(texture);
            }
            public CustomTextureManager.Registration observe(CustomTexture texture, Runnable invalidated) {
                return CustomTextureManager.observe(texture, invalidated);
            }
            public void release(CustomTexture texture) {
                CustomTextureManager.release(texture);
            }
        });
    }

    ResidentImages(Host host) {
        this(host, PreviewStore::prepareTexture);
    }

    ResidentImages(Host host, Decoder decoder) {
        this.host = host;
        this.decoder = decoder;
    }

    /**
     * Returns an independently closeable use only for current authority and a live host registration;
     * returns null on a miss. Does not read, decode, or acquire model content.
     */
    public synchronized Use lookup(ImageRequest request) {
        if (!request.current(null))
            return null;
        var entry = residents.get(request.identity());
        if (entry == null)
            return null;
        if (!entry.registration.live()) {
            residents.remove(entry.identity, entry);
            return null;
        }
        if (entry.uses == 0)
            entry.holder = entry.registration.acquire();
        entry.uses++;
        return new Use(entry, request);
    }

    /**
     * Reserves one current identity for CPU preparation, or returns null if stale, resident, or already claimed.
     *
     * <p>Ownership: retains an independent use of encoded input on success. The caller remains responsible
     * for its original input and must close an unused claim; successful prepare transfers cleanup to Prepared.
     */
    public synchronized Preparation claim(ImageRequest request, ImageSource source) {
        if (!request.current(source))
            return null;
        var identity = request.identity(source);
        if (residents.containsKey(identity) || preparing.containsKey(identity))
            return null;
        var owned = source;
        if (source instanceof PreviewStore.EncodedPreview encoded) {
            owned = encoded.acquire();
            encoded.releasePixels();
        }
        var preparation = new Preparation(identity, request, owned);
        preparing.put(identity, preparation);
        return preparation;
    }

    /**
     * Consumes a non-null, unconsumed Prepared result on every path, including rejection and failure.
     * Normal return may discard a stale or duplicate result; it does not create a consumer use.
     * Host registration/upload failures propagate after this result's cleanup.
     */
    public synchronized void publish(Prepared image) {
        Objects.requireNonNull(image);
        if (image.content == null)
            throw new IllegalStateException("Prepared image was already consumed");
        try (image) {
            if (!image.request.current(image.content))
                return;
            var identity = image.request.identity(image.content);
            if (residents.containsKey(identity))
                return;
            CustomTexture texture = null;
            boolean registered = false;
            try {
                texture = host.create(image.content);
                var holder = host.register(texture);
                registered = true;
                if (texture.failure().isPresent())
                    throw new IllegalStateException("Standalone texture upload failed", texture.failure().orElseThrow());
                var entry = new Entry(identity, texture, holder);
                entry.registration = host.observe(texture, () -> invalidate(entry));
                residents.put(identity, entry);
                // Publication does not create a consumer. Unclaimed results enter the original retention policy.
                host.release(texture);
            } catch (RuntimeException | Error failure) {
                if (texture != null) {
                    try {
                        if (registered)
                            host.release(texture);
                        else
                            texture.close();
                    } catch (Throwable cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }
    }

    private synchronized void invalidate(Entry entry) {
        residents.remove(entry.identity, entry);
    }

    private synchronized void returned(Preparation preparation) {
        preparing.remove(preparation.identity, preparation);
    }

    /** One consumer's host retention; closing it never closes another consumer's use. */
    public final class Use implements AutoCloseable {
        private final Entry entry;
        private final ImageRequest request;
        private boolean closed;

        private Use(Entry entry, ImageRequest request) {
            this.entry = entry;
            this.request = request;
        }

        /** Returns a borrowed texture or null after use close, authority loss, or registration invalidation. */
        public TextureHolder texture() {
            return !closed && request.allowed() && entry.registration.live() ? entry.holder : null;
        }

        @Override
        public void close() {
            synchronized (ResidentImages.this) {
                if (closed)
                    return;
                closed = true;
                if (--entry.uses == 0) {
                    entry.registration.release();
                    entry.holder = null;
                }
            }
        }
    }

    public final class Preparation implements AutoCloseable {
        private final ImageIdentity identity;
        private final ImageRequest request;
        private ImageSource source;
        private boolean consumed;

        private Preparation(ImageIdentity identity, ImageRequest request, ImageSource source) {
            this.identity = identity;
            this.request = request;
            this.source = source;
        }

        /**
         * Performs synchronous CPU work once on the designated task; does not register a host texture.
         * Normal return transfers the prepared input to the caller, who must publish or close it.
         * Failure releases this claim; closing Preparation after consumption does not close its result.
         *
         * @throws IllegalStateException if this preparation was already executed or closed
         * @throws IOException if image preparation fails
         */
        public Prepared prepare() throws IOException {
            final ImageSource input;
            synchronized (this) {
                if (consumed)
                    throw new IllegalStateException("Image preparation was already consumed");
                consumed = true;
                input = source;
                source = null;
            }
            PreviewStore.EncodedPreview encoded = null;
            try {
                if (input instanceof PreviewStore.EncodedPreview existing) {
                    encoded = existing;
                    encoded.preparePixels(new PreviewPixels.Owner());
                } else {
                    encoded = decoder.prepare(input);
                }
                var result = new Prepared(this, request, encoded);
                encoded = null;
                return result;
            } catch (IOException | RuntimeException | Error failure) {
                returned(this);
                if (encoded != null) {
                    try { encoded.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            ImageSource closing;
            synchronized (this) {
                if (consumed)
                    return;
                consumed = true;
                closing = source;
                source = null;
            }
            try {
                if (closing instanceof PreviewStore.EncodedPreview encoded)
                    encoded.close();
            } finally {
                returned(this);
            }
        }
    }

    /**
     * One owning CPU result, used exclusively until publish or close consumes it. Closing an unpublished
     * result releases its input and identity reservation; it does not invalidate another resident entry.
     */
    public final class Prepared implements AutoCloseable {
        private final Preparation preparation;
        private final ImageRequest request;
        private PreviewStore.EncodedPreview content;

        private Prepared(Preparation preparation, ImageRequest request, PreviewStore.EncodedPreview content) {
            this.preparation = preparation;
            this.request = request;
            this.content = content;
        }

        @Override
        public void close() {
            var closing = content;
            content = null;
            try {
                if (closing != null)
                    closing.close();
            } finally {
                returned(preparation);
            }
        }
    }

    private static final class Entry {
        private final ImageIdentity identity;
        private final CustomTexture texture;
        private TextureHolder holder;
        private CustomTextureManager.Registration registration;
        private int uses;

        private Entry(ImageIdentity identity, CustomTexture texture, TextureHolder holder) {
            this.identity = identity;
            this.texture = texture;
            this.holder = holder;
        }
    }

    @FunctionalInterface
    interface Decoder {
        PreviewStore.EncodedPreview prepare(ImageSource source) throws IOException;
    }

    interface Host {
        CustomTexture create(PreviewStore.EncodedPreview image);
        TextureHolder register(CustomTexture texture);
        CustomTextureManager.Registration observe(CustomTexture texture, Runnable invalidated);
        void release(CustomTexture texture);
    }
}
