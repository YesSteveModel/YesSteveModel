package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.model.resource.client.render.AnimationProtoMapper;
import com.elfmcys.ysm.format.parser.pojo.animation.Animation;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.EventKeyFrame;
import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.InstructionKeyFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationBuilderTest {
    @Test
    void preservesSoundKeyframeValuesAndSourceOrder() {
        var source = new AnimationFile();
        var animation = new Animation();
        animation.animationName = "sound";
        animation.soundKeyFrames.add(frame("minecraft:bell", 1.25f));
        animation.soundKeyFrames.add(frame("模型:铃", 1.25f));
        source.animations.add(animation);

        var proto = AnimationBuilder.build(source).animations().get(0);

        assertEquals(2, proto.soundKeyframes().size());
        assertEquals("minecraft:bell", proto.soundKeyframes().get(0).data());
        assertEquals("模型:铃", proto.soundKeyframes().get(1).data());
        assertEquals(1.25f, proto.soundKeyframes().get(0).startTick());
        assertEquals(1.25f, proto.soundKeyframes().get(1).startTick());

        var runtime = AnimationProtoMapper.animation(proto, "animation/test", unusedBinder());
        assertEquals(2, runtime.soundKeyFrames.size());
        assertEquals("minecraft:bell", runtime.soundKeyFrames.get(0).getEventData());
        assertEquals("模型:铃", runtime.soundKeyFrames.get(1).getEventData());
        assertEquals(1.25f, runtime.soundKeyFrames.get(0).getStartTick());
        assertEquals(1.25f, runtime.soundKeyFrames.get(1).getStartTick());
    }

    @Test
    void leavesSoundKeyframesAbsentWhenRawAnimationHasNone() {
        var source = new AnimationFile();
        source.animations.add(new Animation());

        var proto = AnimationBuilder.build(source).animations().get(0);

        assertEquals(0, proto.soundKeyframes().size());
        assertEquals(0, AnimationProtoMapper.animation(proto, "animation/test", unusedBinder()).soundKeyFrames.size());
    }

    @Test
    void omitsInstructionFramesWithoutStatements() {
        var animation = new Animation();
        animation.animationName = "idle";
        animation.customInstructionKeyframes.add(new InstructionKeyFrame());
        var file = new AnimationFile();
        file.animations.add(animation);

        var result = AnimationBuilder.build(file);

        assertTrue(result.animations().get(0).instructionKeyframes().isEmpty());
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

    private static EventKeyFrame frame(String data, float startTick) {
        var frame = new EventKeyFrame();
        frame.eventData = data;
        frame.startTick = startTick;
        return frame;
    }
}
