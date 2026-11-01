package com.elfmcys.ysm.natives.render;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Reads the native state's borrowed, 128-byte-per-bone pose layout.
 *
 * <p>Requires: the consumer retains the publishing NativeModelState and finishes access before
 * its next extract, invalidate, or close. Local extraction permits access only to published indices.
 * This view has no owner reference and cannot detect an expired native address.
 *
 * <p>Ownership: scalar reads copy values. Matrix reads copy into the supplied destination;
 * that matrix does not borrow native storage.
 *
 * <p>Seam: consumes the
 * [frame view publication](docs/architecture/rendering/implementation/frame-execution.md#输入输出与状态).
 */
public final class BonePoseView {
    static final int STRIDE = 128;

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
    private static final int NORMAL_OFFSET = 64;
    private static final int UNIFORM_SCALE_OFFSET = 100;
    private static final int TANGENT_ORIENTATION_OFFSET = 104;
    private static final int NORMAL_SCALE_OFFSET = 108;
    private static final int COLOR_OFFSET = 112;
    private static final int GLOW_OFFSET = 116;

    private final ByteBuffer data;
    private final int boneCount;

    BonePoseView(long ptr, int boneCount) {
        if (boneCount < 0) {
            throw new IllegalArgumentException("Negative bone count");
        }
        var byteCount = Math.multiplyExact(boneCount, STRIDE);
        if (byteCount != 0 && ptr == 0) {
            throw new IllegalStateException("Native bone pose pointer is null");
        }
        data = byteCount == 0 ? EMPTY_BUFFER : MemoryUtil.memByteBuffer(ptr, byteCount);
        data.order(ByteOrder.nativeOrder());
        this.boneCount = boneCount;
    }

    public int getBoneCount() {
        return boneCount;
    }

    public Matrix4f getPose(int boneIndex, Matrix4f destination) {
        Objects.requireNonNull(destination, "destination");
        destination.set(offset(boneIndex), data);
        return destination;
    }

    public Matrix3f getNormal(int boneIndex, Matrix3f destination) {
        Objects.requireNonNull(destination, "destination");
        destination.set(offset(boneIndex) + NORMAL_OFFSET, data);
        return destination;
    }

    public boolean isUniformScale(int boneIndex) {
        return data.get(offset(boneIndex) + UNIFORM_SCALE_OFFSET) != 0;
    }

    public float getTangentOrientation(int boneIndex) {
        return data.getFloat(offset(boneIndex) + TANGENT_ORIENTATION_OFFSET);
    }

    public float getNormalScale(int boneIndex) {
        return data.getFloat(offset(boneIndex) + NORMAL_SCALE_OFFSET);
    }

    public int getColor(int boneIndex) {
        return data.getInt(offset(boneIndex) + COLOR_OFFSET);
    }

    public int getLightLevel(int boneIndex) {
        var light = Byte.toUnsignedInt(data.get(offset(boneIndex) + GLOW_OFFSET));
        return light == 0xFF ? -1 : light;
    }

    public int getLightmapUv(int boneIndex) {
        var light = getLightLevel(boneIndex);
        return light == -1 ? -1 : LightTexture.pack(light, light);
    }

    private int offset(int boneIndex) {
        if (boneIndex < 0 || boneIndex >= boneCount) {
            throw new IndexOutOfBoundsException("bone index " + boneIndex + " out of bounds for " + boneCount);
        }
        return boneIndex * STRIDE;
    }
}
