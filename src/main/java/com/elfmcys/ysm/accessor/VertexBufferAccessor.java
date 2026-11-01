package com.elfmcys.ysm.accessor;

import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Borrows and commits a contiguous range in the host builder's current vertex layout.
 *
 * <p>Execution: the host render owner serializes reserve, synchronous write, and advance.
 * No builder mutation, reserve, resize, reset, or release may overlap that write.
 *
 * <p>Ownership: returned ranges retain an anchor, not ownership of the host allocation.
 * Advance or abandonment ends the borrow; anchor reachability does not extend that window.
 *
 * <p>Seam: provides the reserve/commit boundary required by the
 * [frame borrow contract](docs/architecture/native-runtime/implementation/jni-and-memory.md#帧内借用).
 */
public interface VertexBufferAccessor {
    /** @return whether this consumer currently exposes the remaining accessor operations */
    boolean ysm$ok();

    /** @return the current builder layout; the caller keeps that layout unchanged during a borrow */
    VertexFormat ysm$vertexFormat();

    /**
     * <p>Requires: ysm$ok() holds and maxVertexCount is nonnegative.
     * The implementation grows storage before publishing the address and does not advance the builder.
     *
     * @return a writable range for maxVertexCount vertices in the current layout
     */
    @Borrowed
    BorrowedNativeBuffer ysm$reserve(int maxVertexCount);

    /**
     * <p>Requires: the synchronous writer succeeded, all workers finished, and actualVertexCount
     * is in [0, the reserved count]. The range and builder layout are unchanged since reserve.
     *
     * <p>Ensures: commits that vertex count and ends the borrow. Failed writes are abandoned without advance.
     */
    void ysm$advance(int actualVertexCount);
}
