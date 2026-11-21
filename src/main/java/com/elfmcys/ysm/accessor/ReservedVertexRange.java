package com.elfmcys.ysm.accessor;

/**
 * Borrows the host allocation after reservation has completed.
 *
 * <p>
 * Invariant: address starts a writable range of byteCapacity bytes in the current host layout. byteCapacity is
 * nonnegative. anchor retains the actual GC owner of the backing allocation.
 *
 * <p>
 * Ownership: no release authority. The caller excludes resize, reset, release, and conflicting access until synchronous
 * native output finishes, and retains anchor through that call. Advance or abandonment ends the borrow.
 *
 * <p>
 * Seam: carries the established range and lifetime guarantee of the [frame borrow
 * contract](docs/architecture/native-runtime/implementation/jni-and-memory.md#帧内借用).
 */
public record ReservedVertexRange(long address, int byteCapacity, Object anchor) {
}
