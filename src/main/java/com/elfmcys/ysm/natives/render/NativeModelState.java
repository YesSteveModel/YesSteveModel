package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.NativeObject;
import com.elfmcys.ysm.util.ExposedShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.function.Function;

public final class NativeModelState extends NativeObject {
    private static final int BONE_INFO_INT_COUNT = 8;
    private static final int CUBE_INFO_HEADER_INT_COUNT = 2;
    private static final int CUBE_INFO_INT_COUNT = 2;

    private static final ByteBuffer EMPTY_NATIVE_BUFFER = ByteBuffer.allocateDirect(0);
    private static final ShortBuffer EMPTY_SHORT_BUFFER =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asShortBuffer();
    private static final ThreadLocal<int[]> BONE_INFO_BUFFER = ThreadLocal.withInitial(() -> new int[0]);
    private static final ThreadLocal<int[]> CUBE_INFO_BUFFER = ThreadLocal.withInitial(() -> new int[0]);

    private final ExposedShortArrayList locatorBoneIndices = new ExposedShortArrayList(0);
    private final long[] extractOutput = new long[4];

    private BonePoseView bonePoses;
    private ShortBuffer renderBoneIndices;
    private int locatorCount;
    private int totalVertexCount;
    private int translucentVertexCount;

    private boolean valid;

    private NativeModelState(State state) {
        super(state);
    }

    public static NativeModelState create() {
        Function<State, NativeModelState> factory = NativeModelState::new;
        var ptr = nCreate();
        if (ptr == 0) {
            throw new RuntimeException("Failed to create ModelState");
        }
        return adopt(ptr, factory);
    }

    @Borrowed
    // Result and BonePoseView are reused by this state. Consume them before
    // the next extract call or close.
    public boolean extract(NativeBakedModel bakedModel, float[] boneAttributes) {
        return extract(bakedModel, boneAttributes, false, 0);
    }

    @Borrowed
    public boolean extractSubtree(NativeBakedModel bakedModel, float[] boneAttributes, int rootIndex) {
        return extract(bakedModel, boneAttributes, true, rootIndex);
    }

    private boolean extract(NativeBakedModel bakedModel, float[] boneAttributes, boolean local, int rootIndex) {
        clearResult();
        var statePtr = get();
        try {
            var expectedBoneCount = boneAttributes.length / AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
            if (locatorBoneIndices.size() < expectedBoneCount) {
                locatorBoneIndices.size(expectedBoneCount);
            }
            var success = local
                ? nExtractSubtree(statePtr, bakedModel.get(), boneAttributes, locatorBoneIndices.getUnderlyingArray(),
                    extractOutput, rootIndex)
                : nExtract(statePtr, bakedModel.get(), boneAttributes, locatorBoneIndices.getUnderlyingArray(),
                    extractOutput);
            if (!success) {
                return false;
            }

            var renderBoneCount = Math.toIntExact(extractOutput[2] & 0xFFFFFFFFL);
            var nextLocatorCount = Math.toIntExact(extractOutput[2] >>> 32);
            var nextVertexCount = Math.toIntExact(extractOutput[3] & 0xFFFFFFFFL);
            var nextTranslucentCount = Math.toIntExact(extractOutput[3] >>> 32);
            if (renderBoneCount > expectedBoneCount || nextLocatorCount > expectedBoneCount
                || nextTranslucentCount > nextVertexCount) {
                throw new IllegalStateException("Invalid native ModelState counts");
            }
            var nextPoses = new BonePoseView(extractOutput[0], expectedBoneCount);
            var nextIndices = borrowShortBuffer(extractOutput[1], renderBoneCount);
            bonePoses = nextPoses;
            renderBoneIndices = nextIndices;
            locatorCount = nextLocatorCount;
            totalVertexCount = nextVertexCount;
            translucentVertexCount = nextTranslucentCount;
            valid = true;
            return true;
        } finally {
            if (!valid) {
                clearResult();
                nInvalidate(statePtr);
            }
            Reference.reachabilityFence(this);
            Reference.reachabilityFence(bakedModel);
        }
    }

    public void invalidate() {
        clearResult();
        try {
            nInvalidate(get());
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private void clearResult() {
        bonePoses = null;
        renderBoneIndices = null;
        locatorCount = 0;
        totalVertexCount = 0;
        translucentVertexCount = 0;
        valid = false;
    }

    public BonePoseView getBonePoses() {
        return bonePoses;
    }

    /// Direct
    public ShortBuffer getRenderBoneIndices() {
        return renderBoneIndices;
    }

    public ShortList getLocatorBoneIndices() {
        return locatorBoneIndices.subList(0, locatorCount);
    }

    public int getTotalVertexCount() {
        return totalVertexCount;
    }

    public int getTranslucentVertexCount() {
        return translucentVertexCount;
    }

    public boolean isValid() {
        return valid;
    }

    // 获取需要渲染的所有 bone 在最终 vertex buffer 内的位置
    // 返回的 View 借用 thread-local carrier；同一线程再次调用本方法后不可继续使用旧 View。
    public BoneInfoView calculateRenderBoneInfo() {
        var requiredSize = checkedArraySize(renderBoneIndices.capacity(), BONE_INFO_INT_COUNT, 0);
        var data = getIntBuffer(BONE_INFO_BUFFER, requiredSize);
        final boolean success;
        try {
            success = nCalculateRenderBoneInfo(get(), data);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (!success) {
            throw new RuntimeException("Failed to get ModelState bone info");
        }
        return new BoneInfoView(data, renderBoneIndices.capacity());
    }

    // 获取需要渲染的所有 cube 在最终 vertex buffer 内的位置
    // 返回的 View 仅为借用；同一线程再次调用本方法后不可继续使用旧 View。
    public CubeInfoView calculateRenderCubeInfo() {
        var headerIntCount = checkedArraySize(renderBoneIndices.capacity(), CUBE_INFO_HEADER_INT_COUNT, 0) + 1;
        var data = CUBE_INFO_BUFFER.get();
        final int[] result;
        try {
            result = nCalculateRenderCubeInfo(get(), data);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (result == null) {
            throw new RuntimeException("Failed to get ModelState cube info");
        }
        if (data != result) {
            CUBE_INFO_BUFFER.set(result);
        }

        return new CubeInfoView(result, renderBoneIndices.capacity(), result[0], headerIntCount);
    }

    private static int checkedArraySize(int count, int stride, int header) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative element count");
        }
        return Math.addExact(header, Math.multiplyExact(count, stride));
    }

    private static int[] getIntBuffer(ThreadLocal<int[]> buffer, int requiredSize) {
        var data = buffer.get();
        if (data.length < requiredSize) {
            data = new int[requiredSize];
            buffer.set(data);
        }
        return data;
    }

    private static void checkIndex(int index, int count, String name) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(name + " index " + index + " out of bounds for " + count);
        }
    }

    private static NativeBuffer borrowNativeBuffer(long ptr, int size) {
        if (size == 0) {
            return NativeBuffer.borrow(EMPTY_NATIVE_BUFFER);
        }
        if (ptr == 0) {
            throw new IllegalStateException("Native buffer pointer is null");
        }
        return NativeBuffer.borrow(MemoryUtil.memByteBuffer(ptr, size));
    }

    private static ShortBuffer borrowShortBuffer(long ptr, int size) {
        if (size == 0) {
            return EMPTY_SHORT_BUFFER;
        }
        if (ptr == 0) {
            throw new IllegalStateException("Native short buffer pointer is null");
        }
        return MemoryUtil.memShortBuffer(ptr, size);
    }

    @Override
    public void close() {
        clearResult();
        super.close();
    }

    private static native long nCreate();

    private static native void nInvalidate(long state);

    private static native boolean nExtract(long state, long bakedModel, float[] boneAttributes,
        short[] locatorBoneIndices, long[] output);

    private static native boolean nExtractSubtree(long state, long bakedModel, float[] boneAttributes,
        short[] locatorBoneIndices, long[] output, int rootIndex);

    private static native boolean nCalculateRenderBoneInfo(long state, int[] dst);

    private static native int[] nCalculateRenderCubeInfo(long state, int[] dst);

    // 相对于最终 vertex buffer 的 offset；以及根据是否剔除和是否为负尺寸块计算的最大顶点数量。
    public record VertexRange(long vertexOffset, long expectedVertexCount) {
        public static VertexRange unpack(int[] data, int offset) {
            return new VertexRange(Integer.toUnsignedLong(data[offset]), Integer.toUnsignedLong(data[offset + 1]));
        }
    }

    public static final class BoneInfoView {
        private final int[] data;
        private final int boneCount;

        private BoneInfoView(int[] data, int boneCount) {
            this.data = data;
            this.boneCount = boneCount;
        }

        public int boneCount() {
            return boneCount;
        }

        public VertexRange getCutout(int boneIndex) {
            return get(boneIndex, 0);
        }

        public VertexRange getCutoutNoCulling(int boneIndex) {
            return get(boneIndex, 2);
        }

        public VertexRange getTranslucent(int boneIndex) {
            return get(boneIndex, 4);
        }

        public VertexRange getTranslucentCulling(int boneIndex) {
            return get(boneIndex, 6);
        }

        private VertexRange get(int boneIndex, int partitionOffset) {
            checkIndex(boneIndex, boneCount, "bone");
            return VertexRange.unpack(data, boneIndex * BONE_INFO_INT_COUNT + partitionOffset);
        }
    }

    public static final class CubeInfoView {
        private final int[] data;
        private final int boneCount;
        private final int cubeCount;
        private final int headerIntCount;

        private CubeInfoView(int[] data, int boneCount, int cubeCount, int headerIntCount) {
            this.data = data;
            this.boneCount = boneCount;
            this.cubeCount = cubeCount;
            this.headerIntCount = headerIntCount;

            var parsedCubeCount = 0;
            for (var boneIndex = 0; boneIndex < boneCount; ++boneIndex) {
                parsedCubeCount = Math.addExact(parsedCubeCount, getBoneCubeCountUnchecked(boneIndex));
            }
            if (parsedCubeCount != cubeCount) {
                throw new IllegalStateException("Invalid native cube info");
            }
        }

        public int boneCount() {
            return boneCount;
        }

        public int cubeCount() {
            return cubeCount;
        }

        public int getCutoutCubeCount(int boneIndex) {
            return getPartitionCubeCount(boneIndex, 0);
        }

        public int getCutoutNoCullingCubeCount(int boneIndex) {
            return getPartitionCubeCount(boneIndex, 1);
        }

        public int getTranslucentCubeCount(int boneIndex) {
            return getPartitionCubeCount(boneIndex, 2);
        }

        public int getTranslucentCullingCubeCount(int boneIndex) {
            return getPartitionCubeCount(boneIndex, 3);
        }

        public VertexRange getCutout(int boneIndex, int cubeIndex) {
            return get(boneIndex, 0, cubeIndex);
        }

        public VertexRange getCutoutNoCulling(int boneIndex, int cubeIndex) {
            return get(boneIndex, 1, cubeIndex);
        }

        public VertexRange getTranslucent(int boneIndex, int cubeIndex) {
            return get(boneIndex, 2, cubeIndex);
        }

        public VertexRange getTranslucentCulling(int boneIndex, int cubeIndex) {
            return get(boneIndex, 3, cubeIndex);
        }

        private VertexRange get(int boneIndex, int partition, int cubeIndex) {
            checkIndex(boneIndex, boneCount, "bone");
            var partitionCubeCount = getPartitionCubeCountInternal(boneIndex, partition);
            checkIndex(cubeIndex, partitionCubeCount, "cube");

            var packedCubeIndex = 0;
            for (var index = 0; index < boneIndex; ++index) {
                packedCubeIndex = Math.addExact(packedCubeIndex, getBoneCubeCountUnchecked(index));
            }
            for (var index = 0; index < partition; ++index) {
                packedCubeIndex = Math.addExact(packedCubeIndex, getPartitionCubeCountInternal(boneIndex, index));
            }
            packedCubeIndex = Math.addExact(packedCubeIndex, cubeIndex);
            return VertexRange.unpack(data, headerIntCount + packedCubeIndex * CUBE_INFO_INT_COUNT);
        }

        private int getPartitionCubeCount(int boneIndex, int partition) {
            checkIndex(boneIndex, boneCount, "bone");
            return getPartitionCubeCountInternal(boneIndex, partition);
        }

        private int getPartitionCubeCountInternal(int boneIndex, int partition) {
            var headerOffset = boneIndex * CUBE_INFO_HEADER_INT_COUNT + 1;
            var packedCount = data[headerOffset + partition / 2];
            return partition % 2 == 0 ? packedCount & 0xffff : packedCount >>> 16;
        }

        private int getBoneCubeCountUnchecked(int boneIndex) {
            return Math.addExact(
                Math.addExact(getPartitionCubeCountInternal(boneIndex, 0), getPartitionCubeCountInternal(boneIndex, 1)),
                Math.addExact(getPartitionCubeCountInternal(boneIndex, 2),
                    getPartitionCubeCountInternal(boneIndex, 3)));
        }
    }
}
