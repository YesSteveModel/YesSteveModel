package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Aligned;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.config.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;

public class NativeRenderer {
    private static final int SUCCESS = 1;
    private static final int OUTPUT_REJECTED = 2;

    public static boolean render(VertexConsumer vertexConsumer, PoseStack.Pose pose, NativeModelState modelState,
        int vertexCount, int light, int overlay, int color, RenderContextType contextType) {
        if (!modelState.isValid()) {
            return false;
        }
        if (vertexCount != modelState.getTotalVertexCount()) {
            modelState.invalidate();
            return false;
        }
        if (vertexCount == 0) {
            var result = 0;
            try {
                result = nRender(null, 0, 0, modelState.get(), 0, 0, 0, 0, 0);
                return result == SUCCESS;
            } finally {
                if (result != SUCCESS)
                    modelState.invalidate();
                Reference.reachabilityFence(modelState);
            }
        }
        var result = 0;
        try {
            var vb = probeVertexConsumer(vertexConsumer, vertexCount);
            var isUnknownType = vb.type == VertexFormatType.FALLBACK;

            var matBuffer = getMatBuffer(pose);
            var lightAndOverlay = packLightAndOverlay(light, overlay);
            var flags = packFlags(vb.type, contextType);

            Object bufferObj;
            int bufferFlags;
            if (!isUnknownType && vb.region != null) {
                bufferObj = vb.region.nio();
                bufferFlags = vb.region.size();
            } else {
                bufferObj = FallbackVertexWriter.getVertexData(vertexCount);
                bufferFlags = 0;
            }

            try {
                result = nRender(bufferObj, bufferFlags, matBuffer.ptr(), modelState.get(), vertexCount,
                    lightAndOverlay, packColor(color), flags, IrisCompat.getEntityId());
            } finally {
                Reference.reachabilityFence(vertexConsumer);
                Reference.reachabilityFence(bufferObj);
                Reference.reachabilityFence(vb.region);
                Reference.reachabilityFence(matBuffer);
                Reference.reachabilityFence(modelState);
            }

            if (result == SUCCESS) {
                if (isUnknownType || vb.region == null) {
                    FallbackVertexWriter.write(vertexConsumer, vertexCount, overlay);
                } else if (vb.accessor != null) {
                    vb.accessor.ysm$advance(vertexCount);
                }
            }
            return result == SUCCESS;
        } finally {
            if (result != SUCCESS && result != OUTPUT_REJECTED) {
                modelState.invalidate();
            }
        }
    }

    @SuppressWarnings("resource")
    private static VertexBuffer probeVertexConsumer(VertexConsumer vertexConsumer, int vertexCount) {
        if (vertexConsumer instanceof VertexBufferAccessor accessor && accessor.ysm$ok()) {
            if (!ClientConfig.USE_COMPATIBILITY_RENDERER.get()) {
                var format = accessor.ysm$vertexFormat();
                if (format == DefaultVertexFormat.NEW_ENTITY) {
                    return new VertexBuffer(accessor, VertexFormatType.VANILLA, accessor.ysm$reserve(vertexCount));
                } else {
                    var irisType = IrisCompat.determineVertexFormatType(format);
                    if (irisType.isPresent()) {
                        return new VertexBuffer(accessor, irisType.get(), accessor.ysm$reserve(vertexCount));
                    }
                }
            }
            accessor.ysm$reserve(vertexCount); // 有必要吗？
        }
        return new VertexBuffer(null, VertexFormatType.FALLBACK, null);
    }

    @Borrowed
    @Aligned(64)
    public static NativeBuffer getMatBuffer(PoseStack.Pose pose) {
        var buffer = MatBufferHolder.BUFFER;
        var buf = buffer.nio();

        var view = RenderSystem.getModelViewMatrix();
        var proj = RenderSystem.getProjectionMatrix();

        pose.pose().get(buf);
        view.get(64, buf);
        proj.get(128, buf);
        pose.normal().get(192, buf);

        return buffer;
    }

    private static long packFlags(VertexFormatType v, RenderContextType c) {
        return ((long)v.id() << 2) | c.id();
    }

    private static long packLightAndOverlay(int lightUv, int overlayOv) {
        return ((long)lightUv << 32) | overlayOv;
    }

    static int packColor(int argb) {
        return ((argb >>> 16) & 0xFF) | (argb & 0xFF00) | ((argb & 0xFF) << 16) | (argb & 0xFF000000);
    }

    private static native int nRender(Object vertexBuffer, int vertexBufferFlag, long matPtr, long modelStatePtr,
        int vertexCount, long lightAndOverlay, int color, long flags, long irisEntityId);

    private static final class MatBufferHolder {
        private static final NativeStorage BUFFER = NativeBuffer.allocate(256, 64);
    }

    private record VertexBuffer(@Nullable VertexBufferAccessor accessor, VertexFormatType type,
        @Nullable BorrowedNativeBuffer region) {
    }
}
