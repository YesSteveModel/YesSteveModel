package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.NativeBuffer;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ForgeUniBufferIOTest {
    @Test
    void copiesFromCompositeNettyBufferIntoNativeOffset() {
        var source = Unpooled.compositeBuffer();
        source.addComponents(true, Unpooled.wrappedBuffer(new byte[] {1, 2}),
            Unpooled.wrappedBuffer(new byte[] {3, 4}));
        try {
            var target = NativeBuffer.move(ByteBuffer.allocateDirect(6));
            ForgeUniBufferIO.copyFromNetty(source, 1, target, 2, 3);

            var actual = new byte[6];
            target.nio().get(actual);
            assertArrayEquals(new byte[] {0, 0, 2, 3, 4, 0}, actual);
        } finally {
            source.release();
        }
    }
}
