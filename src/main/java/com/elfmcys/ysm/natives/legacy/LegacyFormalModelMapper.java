package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.format.schema.model.ModelOriginVersion;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.frontend.MolangFrontendException;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationReferenceProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StateProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.TransitionProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;

import java.util.function.Consumer;

/** Compiles source-bearing Programs returned by the legacy importer before publication. */
final class LegacyFormalModelMapper {
    private final MolangFrontend frontend;

    LegacyFormalModelMapper() {
        this(new MolangFrontend());
    }

    LegacyFormalModelMapper(MolangFrontend frontend) {
        this.frontend = frontend;
    }

    ManifestProto manifest(ManifestProto source) {
        var info = source.info();
        var properties = info.properties();
        var originVer = ModelOriginVersion.toModVersion(Integer.parseInt(properties.originVer()));
        return source.toBuilder().setInfo(info.toBuilder().setSettings(settings(info.settings()))
            .setProperties(properties.toBuilder().setOriginVer(originVer).build()).build()).build();
    }

    StringDataProto strings(StringDataProto source) {
        var result = source.toBuilder().clearUserFunctions();
        for (var function : source.userFunctions()) {
            result.addUserFunctions(function.toBuilder().setBody(
                compile(function.body(), MolangFrontend.SourceMode.USER_FUNCTION, "legacy/function/" + function.name()))
                .build());
        }
        return result.build();
    }

    ModelDataProto model(ModelDataProto source) {
        var result = source.toBuilder().clearAnimationFiles().clearAnimationControllers();
        for (var value : source.animationFiles().object2ObjectEntrySet()) {
            result.putAnimationFiles(value.getKey(),
                animationFile(value.getValue(), "legacy/animation/" + value.getKey()));
        }
        for (var value : source.animationControllers().object2ObjectEntrySet()) {
            result.putAnimationControllers(value.getKey(),
                controllerFile(value.getValue(), "legacy/controller/" + value.getKey()));
        }
        return result.build();
    }

    private SettingsProto settings(SettingsProto source) {
        var result = source.toBuilder().clearExtraAnimationButtons();
        var buttonIndex = 0;
        for (var button : source.extraAnimationButtons()) {
            var target = button.toBuilder().clearConfigForms();
            var formIndex = 0;
            for (var form : button.configForms()) {
                target.addConfigForms(config(form, "legacy/config/" + buttonIndex + '/' + formIndex++));
            }
            result.addExtraAnimationButtons(target.build());
            buttonIndex++;
        }
        return result.build();
    }

    private ConfigFormsProto config(ConfigFormsProto source, String location) {
        var result = source.toBuilder()
            .setReadProgram(compile(source.readProgram(), MolangFrontend.SourceMode.EXPRESSION, location + "/read"))
            .setWriteProgram(
                compile(source.writeProgram(), MolangFrontend.SourceMode.CONFIG_WRITE, location + "/write"))
            .clearLabels();
        var index = 0;
        for (var label : source.labels()) {
            result.addLabels(label.toBuilder().setActionProgram(
                compile(label.actionProgram(), MolangFrontend.SourceMode.INSTRUCTION, location + "/label/" + index++))
                .build());
        }
        return result.build();
    }

    private AnimationFileProto animationFile(AnimationFileProto source, String location) {
        var result = source.toBuilder().clearAnimations();
        var index = 0;
        for (var animation : source.animations()) {
            result.addAnimations(animation(animation, location + '/' + index++));
        }
        return result.build();
    }

    private AnimationProto animation(AnimationProto source, String location) {
        var result = source.toBuilder().clearBoneAnimations().clearInstructionKeyframes();
        if (source.hasBlendWeight()) {
            result.setBlendWeight(expression(source.blendWeightUnsafe(), location + "/blend-weight"));
        }
        var boneIndex = 0;
        for (var bone : source.boneAnimations()) {
            result.addBoneAnimations(bone(bone, location + "/bone/" + boneIndex++));
        }
        var frameIndex = 0;
        for (var frame : source.instructionKeyframes()) {
            result.addInstructionKeyframes(frame.toBuilder().setPrograms(compile(frame.programs(),
                MolangFrontend.SourceMode.INSTRUCTION, location + "/instruction/" + frameIndex++)).build());
        }
        return result.build();
    }

    private BoneAnimationProto bone(BoneAnimationProto source, String location) {
        var result = source.toBuilder().clearRotation().clearPosition().clearScale();
        frames(source.rotation(), result::addRotation, location + "/rotation");
        frames(source.position(), result::addPosition, location + "/position");
        frames(source.scale(), result::addScale, location + "/scale");
        return result.build();
    }

    private void frames(Iterable<BoneKeyFrameProto> source, Consumer<BoneKeyFrameProto> target, String location) {
        var frameIndex = 0;
        for (var frame : source) {
            var result = frame.toBuilder().clearPre().clearPost();
            var valueIndex = 0;
            for (var value : frame.pre()) {
                result.addPre(expression(value, location + '/' + frameIndex + "/pre/" + valueIndex++));
            }
            valueIndex = 0;
            for (var value : frame.post()) {
                result.addPost(expression(value, location + '/' + frameIndex + "/post/" + valueIndex++));
            }
            target.accept(result.build());
            frameIndex++;
        }
    }

    private ExpressionValueProto expression(ExpressionValueProto source, String location) {
        if (source.hasNum()) {
            return source;
        }
        if (source.hasProgram()) {
            return source.toBuilder()
                .setProgram(compile(source.program(), MolangFrontend.SourceMode.EXPRESSION, location)).build();
        }
        throw new IllegalArgumentException("empty legacy expression at " + location);
    }

    private AnimationControllerFileProto controllerFile(AnimationControllerFileProto source, String location) {
        var result = source.toBuilder().clearControllers();
        var controllerIndex = 0;
        for (var controller : source.controllers()) {
            result.addControllers(controller(controller, location + '/' + controllerIndex++));
        }
        return result.build();
    }

    private AnimationControllerProto controller(AnimationControllerProto source, String location) {
        var result = source.toBuilder().clearStates();
        var stateIndex = 0;
        for (var state : source.states()) {
            result.addStates(state(state, location + '/' + stateIndex++));
        }
        return result.build();
    }

    private StateProto state(StateProto source, String location) {
        var result = source.toBuilder().clearAnimations().clearTransitions();
        var index = 0;
        for (var animation : source.animations()) {
            result.addAnimations(animation(animation, location + "/animation/" + index++));
        }
        index = 0;
        for (var transition : source.transitions()) {
            result.addTransitions(transition(transition, location + "/transition/" + index++));
        }
        if (source.hasOnEntry()) {
            result.setOnEntry(
                compile(source.onEntryUnsafe(), MolangFrontend.SourceMode.INSTRUCTION, location + "/on-entry"));
        }
        if (source.hasOnExit()) {
            result.setOnExit(
                compile(source.onExitUnsafe(), MolangFrontend.SourceMode.INSTRUCTION, location + "/on-exit"));
        }
        return result.build();
    }

    private AnimationReferenceProto animation(AnimationReferenceProto source, String location) {
        return source.toBuilder()
            .setCondition(compile(source.condition(), MolangFrontend.SourceMode.EXPRESSION, location)).build();
    }

    private TransitionProto transition(TransitionProto source, String location) {
        return source.toBuilder()
            .setCondition(compile(source.condition(), MolangFrontend.SourceMode.EXPRESSION, location)).build();
    }

    private ProgramProto compile(ProgramProto source, MolangFrontend.SourceMode mode, String location) {
        if (source.format() != 0 || !source.hasSource()) {
            throw new IllegalArgumentException("legacy Program does not contain source at " + location);
        }
        try {
            return EncodedProgramMapper.toProto(
                frontend.compile(source.source(), mode, new MolangFrontend.SourceLocation(location)).program());
        } catch (MolangFrontendException failure) {
            try {
                return EncodedProgramMapper
                    .toProto(frontend.compile("0", mode, new MolangFrontend.SourceLocation(location)).program());
            } catch (MolangFrontendException impossible) {
                throw new IllegalStateException("constant legacy fallback did not compile", impossible);
            }
        }
    }
}
