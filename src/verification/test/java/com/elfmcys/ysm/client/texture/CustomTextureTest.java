package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.natives.image.ImageSource;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CustomTextureTest {
    private static final ImageSource UNUSED_SOURCE = () -> {
        throw new IOException("unused");
    };

    @BeforeAll
    static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
    }

    @Test
    void firstLoadAndReloadDecodeUploadSynchronouslyAndClosePixels() {
        var events = new ArrayList<String>();
        var images = new ArrayList<NativeImage>();
        var texture = new CustomTexture(UNUSED_SOURCE, source -> {
            events.add("decode");
            var image = new NativeImage(1, 1, false);
            images.add(image);
            return image;
        }, (ignored, image) -> {
            image.getPixelRGBA(0, 0);
            events.add("upload");
        });

        texture.load(null);
        assertEquals(List.of("decode", "upload"), events);
        assertClosed(images.get(0));

        texture.load(null);
        assertEquals(List.of("decode", "upload", "decode", "upload"), events);
        assertClosed(images.get(1));
        assertTrue(texture.failure().isEmpty());
    }

    @Test
    void decodeFailureIsVisibleBeforeLoadReturnsAndBlocksReload() {
        var attempts = new AtomicInteger();
        var failure = new IOException("broken");
        var texture = new CustomTexture(UNUSED_SOURCE, source -> {
            attempts.incrementAndGet();
            throw failure;
        }, (ignored, image) -> {
            throw new AssertionError("upload must not run after decode failure");
        });

        texture.load(null);
        texture.load(null);

        assertEquals(1, attempts.get());
        assertSame(failure, texture.failure().orElseThrow());
    }

    @Test
    void uploadFailureClosesPixelsAndRemainsVisibleAcrossClose() {
        var image = new NativeImage(1, 1, false);
        var failure = new IllegalStateException("upload failed");
        var texture = new CustomTexture(UNUSED_SOURCE, source -> image, (ignored, pixels) -> {
            throw failure;
        });

        texture.load(null);
        texture.close();

        assertSame(failure, texture.failure().orElseThrow());
        assertClosed(image);
    }

    @Test
    void preparedPixelsUploadOnceAndReloadIsAnIndependentDecode() throws Exception {
        try (var pending = com.elfmcys.ysm.model.storage.TestPreviews.pending();
            var owner = new com.elfmcys.ysm.model.storage.PreviewPixels.Owner()) {
            var encoded = pending.verify(owner, true);
            var decodes = new AtomicInteger();
            var uploads = new AtomicInteger();
            var texture = new CustomTexture(encoded, source -> {
                decodes.incrementAndGet();
                try (var image = source.open(); var decoded = image.decodeToBuffer()) {
                    assertEquals(4, decoded.size());
                }
                return new NativeImage(1, 1, false);
            }, (ignored, image) -> uploads.incrementAndGet(), (ignored, pixels) -> {
                pixels.borrow(bytes -> assertEquals(4, bytes.remaining()));
                uploads.incrementAndGet();
            });
            var prepared = preparedPixels(texture);
            encoded.close();
            texture.load(null);
            assertEquals(0, decodes.get());
            assertEquals(1, uploads.get());
            assertThrows(IllegalStateException.class, () -> prepared.borrow(ignored -> fail()));
            var waiting = owner.adopt(com.elfmcys.ysm.buffer.NativeBuffer.allocate(4), 1, 1);
            texture.load(null);
            waiting.borrow(bytes -> assertEquals(4, bytes.remaining()));
            waiting.close();
            assertEquals(1, decodes.get());
            assertEquals(2, uploads.get());
            texture.close();
            texture.close();
        }
    }

    @Test
    void preparedUploadFailureAndRejectedHostReleaseTheirExactCapabilities() throws Exception {
        for (var upload : List.of(false, true)) {
            try (var pending = com.elfmcys.ysm.model.storage.TestPreviews.pending();
                var owner = new com.elfmcys.ysm.model.storage.PreviewPixels.Owner();
                var encoded = pending.verify(owner, true)) {
                var failure = new IllegalStateException("Host rejected upload");
                var texture = new CustomTexture(encoded, ignored -> {
                    throw new AssertionError("First upload must not decode");
                }, (ignored, image) -> {
                }, (ignored, pixels) -> {
                    throw failure;
                });
                var prepared = preparedPixels(texture);
                if (upload) {
                    texture.load(null);
                    assertSame(failure, texture.failure().orElseThrow());
                }
                texture.close();
                texture.close();
                assertThrows(IllegalStateException.class, () -> prepared.borrow(ignored -> fail()));
            }
        }
    }

    private static com.elfmcys.ysm.model.storage.PreviewPixels preparedPixels(CustomTexture texture) throws Exception {
        var field = CustomTexture.class.getDeclaredField("prepared");
        field.setAccessible(true);
        return (com.elfmcys.ysm.model.storage.PreviewPixels)field.get(texture);
    }

    private static void assertClosed(NativeImage image) {
        assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
    }
}
