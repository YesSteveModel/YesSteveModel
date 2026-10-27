package com.elfmcys.ysm.natives.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BonePoseViewTest {
    @Test
    void readsPoseStackFieldsAndRejectsInvalidAccess() {
        var data = ByteBuffer.allocateDirect(BonePoseView.STRIDE * 2).order(ByteOrder.nativeOrder());
        var offset = BonePoseView.STRIDE;
        var expectedPose =
            new Matrix4f().translationRotateScale(1.0f, -2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 0.9f, 2.0f, 3.0f, 4.0f);
        var expectedNormal = new Matrix3f().rotateXYZ(0.3f, -0.4f, 0.5f);
        expectedPose.get(offset, data);
        expectedNormal.get(offset + 64, data);
        data.put(offset + 100, (byte)0);
        data.putFloat(offset + 104, -1.0f);
        data.putFloat(offset + 108, 2.5f);
        data.putInt(offset + 112, 0x7F030201);
        data.put(offset + 116, (byte)4);
        data.put(116, (byte)0xFF);

        var view = new BonePoseView(MemoryUtil.memAddress(data), 2);
        assertEquals(2, view.getBoneCount());
        assertTrue(expectedPose.equals(view.getPose(1, new Matrix4f()), 0.0f));
        assertTrue(expectedNormal.equals(view.getNormal(1, new Matrix3f()), 0.0f));
        assertFalse(view.isUniformScale(1));
        assertEquals(-1.0f, view.getTangentOrientation(1));
        assertEquals(2.5f, view.getNormalScale(1));
        assertEquals(0x7F030201, view.getColor(1));
        assertEquals(0x00400040, view.getLightmapUv(1));
        assertEquals(-1, view.getLightmapUv(0));
        assertThrows(IndexOutOfBoundsException.class, () -> view.getPose(2, new Matrix4f()));
    }
}
