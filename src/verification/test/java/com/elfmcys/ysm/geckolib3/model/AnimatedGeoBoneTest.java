package com.elfmcys.ysm.geckolib3.model;

import com.elfmcys.ysm.geckolib3.geo.render.built.GeoBone;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.util.StringPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnimatedGeoBoneTest {
    @Test
    void preservesEmptyNameInAnimationState() {
        var data = new GeoBone(boneData(""), null);
        var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
        var bone = new AnimatedGeoBone(data, attributes, 0);

        assertEquals("", data.name());
        assertEquals("", bone.getName());
        assertEquals(StringPool.EMPTY, bone.getPooledName());
        assertEquals(1, attributes[6]);
        assertEquals(1, attributes[7]);
        assertEquals(1, attributes[8]);
        assertEquals(0, attributes[9]);
        assertEquals(0, attributes[10]);
        assertEquals(0xFFFF, (int)attributes[13]);
    }

    @Test
    void rejectsIncompleteTransformsWithEmptyName() {
        var missingRotation = BoneProto.newBuilder().setName("").addPivot(0).addPivot(0).addPivot(0).build();
        var missingPivot = BoneProto.newBuilder().setName("").addRotate(0).addRotate(0).addRotate(0).build();

        assertThrows(IllegalArgumentException.class, () -> new GeoBone(missingRotation, null));
        assertThrows(IllegalArgumentException.class, () -> new GeoBone(missingPivot, null));
    }

    @ParameterizedTest
    @CsvSource({"ysmGlow,0x0FFF", "ysmGlowEye,0x0FFF", "ysmGlow_1,0x0FFF", "root,0xFFFF", "YsmGlowEye,0xFFFF",
        "ysmglowEye,0xFFFF", "prefixysmGlow,0xFFFF"})
    void initializesGlowFromCaseSensitiveNamePrefix(String name, int expected) {
        var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT * 2];
        var bone = new GeoBone(boneData(name), null);
        assertEquals(expected == 0x0FFF, bone.glow());
        new AnimatedGeoBone(bone, attributes, 0);
        AnimatedGeoBone.initializeAttributes(bone, attributes, 1);

        assertEquals(expected, (int)attributes[13]);
        assertEquals(expected, (int)attributes[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT + 13]);
        assertEquals(0xFFFFFF, (int)attributes[12]);
    }

    @Test
    void dynamicGlowOverridesNamedDefaultWithoutChangingTransparency() {
        var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
        var bone = new AnimatedGeoBone(new GeoBone(boneData("ysmGlowEye"), null), attributes, 0);
        assertEquals(0x0FFF, (int)attributes[13]);

        bone.setTransparency(127);
        assertEquals(0x0F7F, (int)attributes[13]);
        bone.setGlow(0);
        assertEquals(0x007F, (int)attributes[13]);
        bone.setGlow(-1);
        assertEquals(0xFF7F, (int)attributes[13]);
        bone.setGlow(15);
        assertEquals(0x0F7F, (int)attributes[13]);
    }

    private static BoneProto boneData(String name) {
        return BoneProto.newBuilder().setName(name).addRotate(0).addRotate(0).addRotate(0).addPivot(0).addPivot(0)
            .addPivot(0).build();
    }

    @Test
    void packsIndependentRenderAttributesWithIdentityDefaults() {
        var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
        var data = BoneProto.newBuilder().setName("root").addRotate(0).addRotate(0).addRotate(0).addPivot(0).addPivot(0)
            .addPivot(0).build();
        var bone = new AnimatedGeoBone(new GeoBone(data, null), attributes, 0);

        assertEquals(0xFFFFFF, (int)attributes[12]);
        assertEquals(0xFFFF, (int)attributes[13]);

        bone.setColor(1, 2, 3);
        assertEquals(0x030201, (int)attributes[12]);

        bone.setTransparency(127);
        assertEquals(0xFF7F, (int)attributes[13]);
        bone.setGlow(7);
        assertEquals(0x077F, (int)attributes[13]);

        bone.setTransparency(255);
        assertEquals(0x07FF, (int)attributes[13]);
        bone.setGlow(-1);
        assertEquals(0xFFFF, (int)attributes[13]);
    }
}
