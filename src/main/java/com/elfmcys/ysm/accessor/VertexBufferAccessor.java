package com.elfmcys.ysm.accessor;

import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import com.mojang.blaze3d.vertex.VertexFormat;

public interface VertexBufferAccessor {
    boolean ysm$ok();

    VertexFormat ysm$vertexFormat();

    /** The synchronous write must finish before advance or any builder mutation. */
    @Borrowed
    BorrowedNativeBuffer ysm$reserve(int maxVertexCount);

    void ysm$advance(int actualVertexCount);
}
