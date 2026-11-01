package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.client.texture.StandaloneImageTestHost;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageSource;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ResidentImagesTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void fourImageUsesHitBeforeEveryRepeatedWorkBoundary() throws Exception {
        for (var kind : new String[]{"preview", "foreground", "background", "avatar/0"}) {
            var f = new Fixture();
            var firstRequest = f.request(kind);
            var source = ((PreviewResult.Data)firstRequest.acquire().join()).image();
            try (var preparation = f.owner.claim(firstRequest, source)) {
                f.owner.publish(preparation.prepare());
            }
            var first = f.owner.lookup(firstRequest);
            var id = first.texture().id();
            first.close();
            int[] before = f.counts();
            // A fresh request and fresh source object must not start acquisition on a resident hit.
            var next = f.request(kind);
            try (var second = f.owner.lookup(next)) {
                assertEquals(id, second.texture().id());
                assertArrayEquals(before, f.counts());
            }
            f.host.tick();
            assertEquals(0, f.host.releases);
            f.host.tick();
            assertEquals(1, f.host.releases);
            assertNull(f.owner.lookup(next));
            f.host.pixels.forEach(pixels -> assertThrows(IllegalStateException.class, () -> pixels.borrow(data -> fail())));
        }
    }

    @Test
    void twoConsumersCloseIndependentlyAndReactivationDoesNotRegister() throws Exception {
        var f = new Fixture();
        var request = f.request("foreground");
        f.publish(request);
        var first = f.owner.lookup(request);
        var second = f.owner.lookup(f.request("foreground"));
        first.close();
        first.close();
        f.host.tick();
        f.host.tick();
        assertNotNull(second.texture());
        assertEquals(0, f.host.releases);
        second.close();
        f.host.tick();
        try (var reused = f.owner.lookup(f.request("foreground"))) {
            assertNotNull(reused.texture());
            f.host.tick();
            assertEquals(1, f.host.registrations);
            assertEquals(0, f.host.releases);
        }
        f.host.tick();
        f.host.tick();
        assertEquals(1, f.host.releases);
    }

    @Test
    void onePreparationClaimAndExplicitDiscardAllowAnotherAttempt() throws Exception {
        var f = new Fixture();
        var request = f.request("background");
        var first = f.owner.claim(request, f.source());
        assertNull(f.owner.claim(f.request("background"), f.source()));
        assertEquals(0, f.reads);
        first.close();
        first.close();
        var accepted = f.owner.claim(request, f.source());
        var prepared = accepted.prepare();
        assertThrows(IllegalStateException.class, accepted::prepare);
        accepted.close();
        assertNull(f.owner.claim(request, f.source()));
        f.owner.publish(prepared);
        assertThrows(IllegalStateException.class, () -> f.owner.publish(prepared));
        assertEquals(1, f.decodes);
        assertEquals(1, f.reads);
    }

    @Test
    void staleResultAndRevokedUseNeverPublishOrGrantAuthority() throws Exception {
        var f = new Fixture();
        var request = f.request("preview");
        var preparation = f.owner.claim(request, f.source());
        var prepared = preparation.prepare();
        f.allowed.set(false);
        f.owner.publish(prepared);
        assertEquals(0, f.host.constructions);
        f.allowed.set(true);
        f.publish(request);
        var use = f.owner.lookup(request);
        f.allowed.set(false);
        assertNull(use.texture());
        assertNull(f.owner.lookup(request));
        use.close();
    }

    @Test
    void rejectedRegistrationAndFailedUploadCloseTransferredPixels() throws Exception {
        for (boolean upload : new boolean[]{false, true}) {
            var f = new Fixture();
            f.host.reject = !upload;
            f.host.failUpload = upload;
            var request = f.request("avatar/0");
            var prepared = f.owner.claim(request, f.source()).prepare();
            assertThrows(RuntimeException.class, () -> f.owner.publish(prepared));
            prepared.close();
            assertNull(f.owner.lookup(request));
            f.host.pixels.forEach(pixels -> assertThrows(IllegalStateException.class, () -> pixels.borrow(data -> fail())));
            f.host.tick();
            f.host.tick();
            assertEquals(upload ? 1 : 0, f.host.releases);
        }
    }

    @Test
    void distinctMembersAndContentVersionsCannotAliasAndOldReleaseCannotRemoveReplacement() throws Exception {
        var f = new Fixture();
        var first = f.request("avatar/0");
        f.publish(first);
        var old = f.owner.lookup(first);
        assertNull(f.owner.lookup(f.request("avatar/1")));
        f.version++;
        var replacement = f.request("avatar/0");
        assertNull(f.owner.lookup(replacement));
        f.publish(replacement);
        var current = f.owner.lookup(replacement);
        assertNotEquals(old.texture().id(), current.texture().id());
        old.close();
        f.host.tick();
        f.host.tick();
        assertNotNull(current.texture());
        assertEquals(1, f.host.releases);
        current.close();
    }

    @Test
    void reloadRevokesExactResidentRegistration() throws Exception {
        var f = new Fixture();
        var request = f.request("foreground");
        f.publish(request);
        var old = f.owner.lookup(request);
        // The host's reload decoder deliberately fails. Invalidation must precede it.
        assertThrows(AssertionError.class, f.host::reload);
        assertNull(old.texture());
        assertNull(f.owner.lookup(f.request("foreground")));
        f.publish(request);
        var replacement = f.owner.lookup(request);
        old.close();
        f.host.tick();
        f.host.tick();
        assertNotNull(replacement.texture());
        replacement.close();
    }

    @Test
    void preparationFailurePreservesItsCauseAndReturnsTheUniqueClaim() throws Exception {
        var f = new Fixture();
        var request = f.request("foreground");
        var cause = new java.io.IOException("source unavailable");
        try (var preparation = f.owner.claim(request, () -> { throw cause; })) {
            assertSame(cause, assertThrows(java.io.IOException.class, preparation::prepare));
        }
        assertEquals(0, f.host.constructions);
        f.publish(request);
        assertNotNull(f.owner.lookup(request));
    }

    static final class Fixture implements ResidentImages.Host {
        final StandaloneImageTestHost host = new StandaloneImageTestHost();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final ResidentImages owner = new ResidentImages(this, source -> {
            this.decodes++;
            return PreviewStore.prepareTexture(source);
        });
        int reads;
        int downloads;
        int decodes;
        int version;

        ImageSource source() {
            return () -> {
                reads++;
                return Image.probe(ArrayBuffer.move(PNG.clone()));
            };
        }

        ImageRequest request(String kind) {
            var identity = new ImageIdentity(kind, version);
            var source = source();
            return new ImageRequest(ignored -> identity, allowed::get, ignored -> true, () -> {
                downloads++;
                return CompletableFuture.completedFuture(new PreviewResult.Data(source));
            });
        }

        void publish(ImageRequest request) throws Exception {
            try (var preparation = owner.claim(request, source())) {
                owner.publish(preparation.prepare());
            }
        }

        int[] counts() {
            return new int[]{reads, downloads, decodes, host.constructions, host.registrations, host.uploads};
        }

        public CustomTexture create(PreviewStore.EncodedPreview image) { return host.create(image); }
        public TextureHolder register(CustomTexture texture) { return host.register(texture); }
        public CustomTextureManager.Registration observe(CustomTexture texture, Runnable invalidated) {
            return host.observe(texture, invalidated);
        }
        public void release(CustomTexture texture) { host.release(texture); }
    }
}
