package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.EventKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.util.ProtoUtil;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoSource;

import java.io.IOException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationProtoMapperTest {
    @Test
    void treatsAbsentPostValuesAsAContiguousKeyframe() {
        var frame = BoneKeyFrameProto.newBuilder().setStartTick(0)
            .setEasingType(com.elfmcys.ysm.proto.mixel.asset.model.data.EasingType.LINEAR)
            .addPre(ExpressionValueProto.newBuilder().setNum(1).build()).build();
        var bone = BoneAnimationProto.newBuilder().setBoneName("root").addPosition(frame).build();
        var animation = AnimationProto.newBuilder().setName("test").setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).addBoneAnimations(bone).build();

        assertDoesNotThrow(() -> AnimationProtoMapper.animation(animation, "animation/test", unusedBinder()));
    }

    @Test
    void matchesFrozenFieldSevenWireAndRuntimeOrder() throws Exception {
        var animation = AnimationProto.newBuilder().setName("sound").setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE)
            .setBlendWeight(ExpressionValueProto.newBuilder().build())
            .addSoundKeyframes(EventKeyFrameProto.newBuilder().setData("模型:铃").setStartTick(0).build())
            .addSoundKeyframes(EventKeyFrameProto.newBuilder().setData("").setStartTick(1.25f).build())
            .addSoundKeyframes(EventKeyFrameProto.newBuilder().setData("").setStartTick(Float.NaN).build()).build();

        assertArrayEquals(CANONICAL_FIELD_SEVEN_WIRE, ProtoUtil.serializeToArray(animation));

        var parsed = AnimationProto.parseFrom(FROZEN_FIELD_SEVEN_WIRE);
        var runtime = AnimationProtoMapper.animation(parsed, "animation/test", unusedBinder());
        assertEquals(3, runtime.soundKeyFrames.size());
        assertEquals("模型:铃", runtime.soundKeyFrames.get(0).getEventData());
        assertEquals("", runtime.soundKeyFrames.get(1).getEventData());
        assertEquals(0.0f, runtime.soundKeyFrames.get(0).getStartTick());
        assertEquals(1.25f, runtime.soundKeyFrames.get(1).getStartTick());
        assertEquals("", runtime.soundKeyFrames.get(2).getEventData());
        assertTrue(Float.isNaN(runtime.soundKeyFrames.get(2).getStartTick()));
    }

    @Test
    void readsPreFieldSevenPayloadAsAnEmptyList() throws Exception {
        var parsed = AnimationProto.parseFrom(HexFormat.of().parseHex("0a05736f756e6415000000001802"));

        assertEquals("sound", parsed.name());
        assertEquals(0, parsed.soundKeyframes().size());
        assertEquals(0, AnimationProtoMapper.animation(parsed, "animation/test", unusedBinder()).soundKeyFrames.size());
    }

    @Test
    void preFieldSevenReaderSkipsUnknownSoundKeyframes() throws Exception {
        var current = AnimationProto.newBuilder().setName("sound").setLength(3.5f)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE)
            .addSoundKeyframes(EventKeyFrameProto.newBuilder().setData("minecraft:bell").setStartTick(1.25f).build())
            .build();

        var old = readPreFieldSeven(ProtoUtil.serializeToArray(current));

        assertEquals("sound", old.name());
        assertEquals(3.5f, old.length());
    }

    private static PreFieldSevenAnimation readPreFieldSeven(byte[] bytes) throws IOException {
        var source = ProtoSource.newInstance(bytes);
        var name = "";
        var length = 0.0f;
        for (int tag; (tag = source.readTag()) != 0;) {
            switch (tag) {
                case 10 -> name = source.readString();
                case 21 -> length = source.readFloat();
                default -> source.skipField(tag);
            }
        }
        return new PreFieldSevenAnimation(name, length);
    }

    private record PreFieldSevenAnimation(String name, float length) {
    }

    private static AnimationProtoMapper.ProgramBinder unusedBinder() {
        return new AnimationProtoMapper.ProgramBinder() {
            @Override
            public com.elfmcys.ysm.expression.aot.FloatExpression.Program bindFloat(String location,
                com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                throw unexpected();
            }

            @Override
            public com.elfmcys.ysm.expression.aot.BooleanProgram bindBoolean(String location,
                com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                throw unexpected();
            }

            @Override
            public com.elfmcys.ysm.expression.aot.VoidProgram bindVoid(String location,
                com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                throw unexpected();
            }

            private AssertionError unexpected() {
                return new AssertionError("literal-only fixture requested a program binding");
            }
        };
    }

    private static final byte[] FROZEN_FIELD_SEVEN_WIRE =
        HexFormat.of().parseHex("0a05736f756e641500000000180222003a110a0ae6a8a1e59e8b3ae993831500000000"
            + "3a070a00150000a03f3a070a00150000c07f");
    private static final byte[] CANONICAL_FIELD_SEVEN_WIRE =
        HexFormat.of().parseHex("0a05736f756e64180222003a0c0a0ae6a8a1e59e8b3ae993833a05150000a03f" + "3a05150000c07f");
}
