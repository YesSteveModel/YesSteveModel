package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.animation.Animation;
import com.elfmcys.ysm.format.parser.pojo.animation.BoneAnimation;
import com.elfmcys.ysm.format.parser.pojo.animation.value.UnionValue;
import com.elfmcys.ysm.format.parser.pojo.animation.value.Vector3v;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class JsonAnimationBoundaryTest {
    private static final Gson GSON = new Gson();

    @Test
    void preservesMetadataValueKindsAndIgnoredShapes() {
        var animation = GSON.fromJson("""
            {"start_delay":2,"loop_delay":"2","anim_time_update":true,
             "blend_weight":[],"bones":{"second":{},"first":{"rotation":3}}}
            """, Animation.class);
        assertEquals(UnionValue.ValueType.FLOAT, animation.startDelay.type);
        assertEquals(2, animation.startDelay.floatValue);
        assertEquals(UnionValue.ValueType.STRING, animation.loopDelay.type);
        assertEquals("2", animation.loopDelay.stringValue);
        assertNull(animation.animTimeUpdate);
        assertNull(animation.blendWeight);
        assertEquals("second", animation.boneAnimations.get(0).boneName);
        assertEquals("first", animation.boneAnimations.get(1).boneName);
        assertTrue(animation.boneAnimations.get(0).rotationKeyFrames.keyFrames.isEmpty());
        assertEquals(3,
            animation.boneAnimations.get(1).rotationKeyFrames.keyFrames.get(0).preValue.components.get(0).floatValue);
    }

    @Test
    void preservesExplicitNullChannelsAndStandaloneBoneNameCoercion() {
        var bone = GSON.fromJson("{\"boneName\":7,\"rotation\":null}", BoneAnimation.class);
        assertEquals("7", bone.boneName);
        assertNull(bone.rotationKeyFrames);
        assertTrue(bone.positionKeyFrames.keyFrames.isEmpty());
        assertTrue(bone.scaleKeyFrames.keyFrames.isEmpty());
        assertNull(GSON.fromJson("null", BoneAnimation.class));
        assertNull(GSON.fromJson("{\"boneName\":null}", BoneAnimation.class).boneName);
        assertEquals("true", GSON.fromJson("{\"boneName\":true}", BoneAnimation.class).boneName);
    }

    @Test
    void rejectsMalformedBoneAndChannelShapesLocally() {
        for (String json : new String[] {"{\"bones\":[]}", "{\"bones\":{\"root\":null}}", "{\"bones\":{\"root\":[]}}",
            "{\"bones\":{\"root\":{\"rotation\":true}}}", "{\"bones\":{\"root\":{\"rotation\":null}}}",
            "{\"bones\":{\"root\":{\"boneName\":[7]}}}", "{\"bones\":{\"root\":{\"boneName\":{}}}}"}) {
            assertThrows(RuntimeException.class, () -> GSON.fromJson(json, Animation.class), json);
        }
        assertDoesNotThrow(() -> GSON.fromJson("{\"bones\":{\"root\":{\"rotation\":3}}}", Animation.class));
    }

    @Test
    void preservesNullAndNumericInterpretationAtValueBoundary() {
        assertNull(GSON.fromJson("null", Vector3v.class));
        assertNull(GSON.fromJson("null", UnionValue.class));
        for (String json : new String[] {"[null]", "[1,null,3]", "[[1],2,3]", "[{},2,3]"}) {
            assertThrows(RuntimeException.class, () -> GSON.fromJson(json, Vector3v.class), json);
        }
        assertEquals(UnionValue.ValueType.STRING, GSON.fromJson("\"1e999\"", UnionValue.class).type);
        assertTrue(Float.isInfinite(GSON.fromJson("1e999", UnionValue.class).floatValue));
    }

    public static void main(String[] args) {
        var test = new JsonAnimationBoundaryTest();
        test.preservesMetadataValueKindsAndIgnoredShapes();
        test.preservesExplicitNullChannelsAndStandaloneBoneNameCoercion();
        test.rejectsMalformedBoneAndChannelShapesLocally();
        test.preservesNullAndNumericInterpretationAtValueBoundary();
        var oracle = new JsonMaterializationOracleTest();
        oracle.preservesIndependentValuesOrderPresenceAndEasing();
        oracle.rejectsIndependentInvalidShapes();
        System.out.println("PASS: independent JSON value/order/easing and animation shape boundaries");
    }
}
