package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.expression.aot.FloatExpression;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.aot.VoidProgram;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.keyframe.BoneAnimation;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.BoneKeyFrameProcessor;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.EasingType;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.RawBoneKeyFrame;
import com.elfmcys.ysm.geckolib3.core.keyframe.event.EventKeyFrame;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;
import com.elfmcys.ysm.proto.baked.asset.BakedBoneAnimationProto;
import com.elfmcys.ysm.proto.baked.asset.BakedBoneKeyFrameProto;
import com.elfmcys.ysm.proto.baked.asset.BakedExpressionValueProto;
import com.elfmcys.ysm.proto.baked.asset.BakedInstructionKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/** Converts animation programs to references within the accompanying artifact catalog. */
public final class BakedAnimationMapper {
    private BakedAnimationMapper() {}

    public static BakedAnimationProto bake(AnimationProto source, String domain, ToIntFunction<String> samples,
        ToIntFunction<String> instructions) {
        var base = domain + '/' + source.name();
        var result = BakedAnimationProto.newBuilder().setName(source.name()).setLength(source.length());
        source.loop().ifPresent(result::setLoop);
        source.blendWeight()
            .ifPresent(value -> result.setBlendWeight(bakeValue(value, base + "/blend-weight", samples)));
        for (var bone : source.boneAnimations()) {
            var location = base + "/bone/" + bone.boneName();
            var baked = BakedBoneAnimationProto.newBuilder().setBoneName(bone.boneName());
            bakeFrames(bone.rotation(), location + "/rotation", samples).forEach(baked::addRotation);
            bakeFrames(bone.position(), location + "/position", samples).forEach(baked::addPosition);
            bakeFrames(bone.scale(), location + "/scale", samples).forEach(baked::addScale);
            result.addBoneAnimations(baked.build());
        }
        for (int index = 0; index < source.instructionKeyframes().size(); index++) {
            var frame = source.instructionKeyframes().get(index);
            result.addInstructionKeyframes(BakedInstructionKeyFrameProto.newBuilder().setStartTick(frame.startTick())
                .setProgramId(instructions.applyAsInt(base + "/instruction/" + index)).build());
        }
        source.soundKeyframes().forEach(result::addSoundKeyframes);
        return result.build();
    }

    private static List<BakedBoneKeyFrameProto> bakeFrames(Iterable<BoneKeyFrameProto> frames, String location,
        ToIntFunction<String> samples) {
        var result = new java.util.ArrayList<BakedBoneKeyFrameProto>();
        int index = 0;
        for (var frame : frames) {
            var baked =
                BakedBoneKeyFrameProto.newBuilder().setStartTick(frame.startTick()).setEasingType(frame.easingType());
            for (int axis = 0; axis < frame.pre().size(); axis++)
                baked.addPre(bakeValue(frame.pre().get(axis), location + '/' + index + "/pre/" + axis, samples));
            for (int axis = 0; axis < frame.post().size(); axis++)
                baked.addPost(bakeValue(frame.post().get(axis), location + '/' + index + "/post/" + axis, samples));
            result.add(baked.build());
            index++;
        }
        return result;
    }

    private static BakedExpressionValueProto bakeValue(ExpressionValueProto value, String location,
        ToIntFunction<String> samples) {
        var result = BakedExpressionValueProto.newBuilder();
        if (value.hasNum())
            result.setNum(value.num());
        else if (value.hasProgram())
            result.setProgramId(samples.applyAsInt(location));
        return result.build();
    }

    public static Animation animation(BakedAnimationProto source, RenderTargetExpressionModule module) {
        return animation(source, module::requireFloatProgram, module::requireVoidProgram);
    }

    static Animation animation(BakedAnimationProto source, IntFunction<FloatExpression.Program> samples,
        IntFunction<VoidProgram> instructionsById) {
        var bones = new ReferenceArrayList<BoneAnimation>(source.boneAnimations().size());
        for (var sourceBone : source.boneAnimations()) {
            bones.add(new BoneAnimation(sourceBone.boneName(), frames(sourceBone.rotation(), true, samples),
                frames(sourceBone.position(), false, samples), frames(sourceBone.scale(), false, samples)));
        }

        var instructions = new ReferenceArrayList<EventKeyFrame<VoidProgram[]>>(source.instructionKeyframes().size());
        for (var frameIndex = 0; frameIndex < source.instructionKeyframes().size(); frameIndex++) {
            var sourceFrame = source.instructionKeyframes().get(frameIndex);
            var values = new VoidProgram[] {instructionsById.apply(sourceFrame.programId())};
            instructions.add(new EventKeyFrame<>(sourceFrame.startTick(), values));
        }
        instructions.sort(Comparator.comparingDouble(EventKeyFrame::getStartTick));

        var sounds = new ReferenceArrayList<EventKeyFrame<String>>(source.soundKeyframes().size());
        for (var sourceFrame : source.soundKeyframes()) {
            sounds.add(new EventKeyFrame<>(sourceFrame.startTick(), sourceFrame.data()));
        }

        var blendWeight = source.blendWeight().map(value -> value(value, samples)).orElse(null);
        return new Animation(source.name(), source.length(),
            loop(source.loop().orElse(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.UNSPECIFIED)), blendWeight,
            bones, sounds, instructions);
    }

    private static List<BoneKeyFrame> frames(Iterable<BakedBoneKeyFrameProto> source, boolean rotation,
        IntFunction<FloatExpression.Program> samples) {
        var raw = new ReferenceArrayList<RawBoneKeyFrame>();
        for (var frame : source) {
            var target = new RawBoneKeyFrame();
            target.startTick = frame.startTick();
            target.easingType = frame.easingType() == com.elfmcys.ysm.proto.mixel.asset.model.data.EasingType.CATMULLROM
                ? EasingType.CATMULLROM : EasingType.LINEAR;
            if (!frame.pre().isEmpty()) {
                assign(frame.pre(), target, false, samples);
            }
            target.contiguous = frame.post().isEmpty();
            if (!target.contiguous) {
                assign(frame.post(), target, true, samples);
            }
            raw.add(target);
        }
        raw.sort(Comparator.comparingDouble(RawBoneKeyFrame::startTick));
        return BoneKeyFrameProcessor.process(raw, rotation);
    }

    private static void assign(ObjectList<BakedExpressionValueProto> source, RawBoneKeyFrame target, boolean post,
        IntFunction<FloatExpression.Program> samples) {
        if (source.isEmpty()) {
            return;
        }
        var x = value(source.get(0), samples);
        var y = source.size() >= 3 ? value(source.get(1), samples) : x;
        var z = source.size() >= 3 ? value(source.get(2), samples) : x;
        if (post) {
            target.postXValue = x;
            target.postYValue = y;
            target.postZValue = z;
        } else {
            target.preXValue = x;
            target.preYValue = y;
            target.preZValue = z;
        }
    }

    private static FloatExpression value(BakedExpressionValueProto source,
        IntFunction<FloatExpression.Program> samples) {
        if (source.hasNum()) {
            return new FloatExpression.Literal(source.num());
        }
        if (source.hasProgramId()) {
            return samples.apply(source.programId());
        }
        return new FloatExpression.Literal(0.0f);
    }

    private static LoopType loop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType loop) {
        return switch (loop) {
            case LOOP -> LoopType.LOOP;
            case HOLD_ON_LAST_FRAME -> LoopType.HOLD_ON_LAST_FRAME;
            default -> LoopType.PLAY_ONCE;
        };
    }

}
