package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.format.parser.pojo.animation.Animation;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.format.parser.pojo.animation.BoneAnimation;
import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.BoneKeyFrame;
import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.BoneKeyFrameList;
import com.elfmcys.ysm.format.parser.pojo.animation.value.UnionValue;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.EasingType;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.EventKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.InstructionKeyFrameProto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;

public class AnimationBuilder {
    private AnimationBuilder() {}

    public static AnimationFileProto build(AnimationFile pojo) {
        return build(pojo, new MolangFrontend(), "animation");
    }

    static AnimationFileProto build(AnimationFile pojo, MolangFrontend frontend, String sourceName) {
        var result = AnimationFileProto.newBuilder();
        var animations = new ArrayList<>(pojo.animations);
        animations.sort(Comparator.comparing(value -> Objects.requireNonNullElse(value.animationName, "")));
        for (var animation : animations) {
            result.addAnimations(toProto(animation, frontend, sourceName + "/animation/" + animation.animationName));
        }
        return result.build();
    }

    private static AnimationProto toProto(Animation src, MolangFrontend frontend, String location) {
        var dst = AnimationProto.newBuilder().setName(Objects.requireNonNullElse(src.animationName, ""))
            .setLength(src.animationLength).setLoop(toProto(src.loop));
        if (src.blendWeight != null) {
            dst.setBlendWeight(toProto(src.blendWeight, frontend, location + "/blend-weight"));
        }
        var bones = new ArrayList<>(src.boneAnimations);
        bones.sort(Comparator.comparing(value -> Objects.requireNonNullElse(value.boneName, "")));
        for (var boneAnimation : bones) {
            dst.addBoneAnimations(toProto(boneAnimation, frontend, location + "/bone/" + boneAnimation.boneName));
        }
        for (var frameIndex = 0; frameIndex < src.customInstructionKeyframes.size(); frameIndex++) {
            var instruction = src.customInstructionKeyframes.get(frameIndex);
            if (instruction.eventData.isEmpty()) {
                continue;
            }
            dst.addInstructionKeyframes(InstructionKeyFrameProto.newBuilder().setPrograms(FrontendPrograms
                .compileStatements(frontend, instruction.eventData, location + "/instruction/" + frameIndex))
                .setStartTick(instruction.startTick).build());
        }
        for (var sound : src.soundKeyFrames) {
            dst.addSoundKeyframes(EventKeyFrameProto.newBuilder()
                .setData(Objects.requireNonNullElse(sound.eventData, "")).setStartTick(sound.startTick).build());
        }
        return dst.build();
    }

    private static BoneAnimationProto toProto(BoneAnimation src, MolangFrontend frontend, String location) {
        var dst = BoneAnimationProto.newBuilder().setBoneName(Objects.requireNonNullElse(src.boneName, ""));
        addKeyFrames(dst::addRotation, src.rotationKeyFrames, frontend, location + "/rotation");
        addKeyFrames(dst::addPosition, src.positionKeyFrames, frontend, location + "/position");
        addKeyFrames(dst::addScale, src.scaleKeyFrames, frontend, location + "/scale");
        return dst.build();
    }

    private static void addKeyFrames(Consumer<BoneKeyFrameProto> adder, BoneKeyFrameList list, MolangFrontend frontend,
        String location) {
        for (var index = 0; index < list.keyFrames.size(); index++) {
            adder.accept(toProto(list.keyFrames.get(index), frontend, location + '/' + index));
        }
    }

    private static BoneKeyFrameProto toProto(BoneKeyFrame src, MolangFrontend frontend, String location) {
        var dst = BoneKeyFrameProto.newBuilder().setStartTick(src.startTick)
            .setEasingType(src.easingType == EasingType.CATMULLROM
                ? com.elfmcys.ysm.proto.mixel.asset.model.data.EasingType.CATMULLROM
                : com.elfmcys.ysm.proto.mixel.asset.model.data.EasingType.LINEAR);
        if (src.preValue != null) {
            for (var index = 0; index < src.preValue.components.size(); index++) {
                dst.addPre(toProto(src.preValue.components.get(index), frontend, location + "/pre/" + index));
            }
        }
        if (src.postValue != null) {
            for (var index = 0; index < src.postValue.components.size(); index++) {
                dst.addPost(toProto(src.postValue.components.get(index), frontend, location + "/post/" + index));
            }
        }
        return dst.build();
    }

    private static ExpressionValueProto toProto(UnionValue src, MolangFrontend frontend, String location) {
        var dst = ExpressionValueProto.newBuilder();
        if (src.type == UnionValue.ValueType.FLOAT) {
            dst.setNum(src.floatValue);
        } else if (src.type == UnionValue.ValueType.STRING) {
            dst.setProgram(
                FrontendPrograms.compile(frontend, src.stringValue, MolangFrontend.SourceMode.EXPRESSION, location));
        }
        return dst.build();
    }

    private static com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType toProto(LoopType loop) {
        if (loop == LoopType.LOOP) {
            return com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.LOOP;
        }
        if (loop == LoopType.HOLD_ON_LAST_FRAME) {
            return com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.HOLD_ON_LAST_FRAME;
        }
        return com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE;
    }
}
