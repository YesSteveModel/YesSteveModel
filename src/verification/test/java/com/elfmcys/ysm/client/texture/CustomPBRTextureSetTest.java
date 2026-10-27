package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomPBRTextureSetTest {
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
    void uploadFailureDetachesAndClosesPreparedImageExactlyOnce() {
        var image = new NativeImage(1, 1, false);
        var closeCalls = new AtomicInteger();
        var failure = new IllegalStateException("upload failed");
        var failureGate = new RecordingFailureGate();
        var textureIO = new CustomPBRTextureSet.TextureIO() {
            @Override
            public void upload(AbstractTexture texture, NativeImage pixels) {
                assertSame(image, pixels);
                throw failure;
            }

            @Override
            public void close(NativeImage pixels) {
                closeCalls.incrementAndGet();
                pixels.close();
            }
        };
        var texture = new CustomPBRTextureSet(new PBRImageSources(UNUSED_SOURCE, null, null), image, null, null,
            failureGate, ModelResourceFailureGate.none(), ModelResourceFailureGate.none(), textureIO);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> texture.load(null)));
        texture.closeUnregistered();

        assertSame(failure, failureGate.failure().orElseThrow());
        assertEquals(1, closeCalls.get());
    }

    private static final class RecordingFailureGate implements ModelResourceFailureGate {
        private Throwable failure;

        @Override
        public Optional<Throwable> failure() {
            return Optional.ofNullable(failure);
        }

        @Override
        public void fail(Throwable cause) {
            failure = cause;
        }
    }
}
