package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.controller.AnimationController;
import com.elfmcys.ysm.format.parser.pojo.controller.AnimationControllerFile;
import com.elfmcys.ysm.format.parser.pojo.controller.State;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationReferenceProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BlendPointProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BlendTransitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StateProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.TransitionProto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

public class AnimationControllerBuilder {
    private AnimationControllerBuilder() {}

    public static AnimationControllerFileProto build(AnimationControllerFile pojo) {
        return build(pojo, new MolangFrontend(), "controller");
    }

    static AnimationControllerFileProto build(AnimationControllerFile pojo, MolangFrontend frontend,
        String sourceName) {
        var result = AnimationControllerFileProto.newBuilder();
        var entries = new ArrayList<>(pojo.animationControllers.entrySet());
        entries.sort(java.util.Map.Entry.comparingByKey());
        for (var entry : entries) {
            result.addControllers(
                toProto(entry.getKey(), entry.getValue(), frontend, sourceName + "/controller/" + entry.getKey()));
        }
        return result.build();
    }

    private static AnimationControllerProto toProto(String name, AnimationController src, MolangFrontend frontend,
        String location) {
        var dst = AnimationControllerProto.newBuilder().setName(name)
            .setDefaultState(Objects.requireNonNullElse(src.initialState, "default"));
        var states = new ArrayList<>(src.states.entrySet());
        states.sort(java.util.Map.Entry.comparingByKey());
        for (var state : states) {
            dst.addStates(toProto(state.getKey(), state.getValue(), frontend, location + "/state/" + state.getKey()));
        }
        return dst.build();
    }

    private static StateProto toProto(String name, State src, MolangFrontend frontend, String location) {
        var dst = StateProto.newBuilder().setName(name);
        for (var index = 0; index < src.animations.size(); index++) {
            var animation = src.animations.get(index);
            var condition = Objects.requireNonNullElse(animation.condition, "");
            dst.addAnimations(
                AnimationReferenceProto.newBuilder().setName(Objects.requireNonNullElse(animation.name, ""))
                    .setCondition(FrontendPrograms.compile(frontend, condition.isBlank() ? "true" : condition,
                        MolangFrontend.SourceMode.EXPRESSION, location + "/animation/" + index))
                    .build());
        }
        for (var index = 0; index < src.transitions.size(); index++) {
            var transition = src.transitions.get(index);
            dst.addTransitions(TransitionProto
                .newBuilder().setDst(transition.destStateName).setCondition(FrontendPrograms.compile(frontend,
                    transition.condition, MolangFrontend.SourceMode.EXPRESSION, location + "/transition/" + index))
                .build());
        }
        if (!src.onEntry.isEmpty()) {
            dst.setOnEntry(FrontendPrograms.compileStatements(frontend, src.onEntry, location + "/on-entry"));
        }
        if (!src.onExit.isEmpty()) {
            dst.setOnExit(FrontendPrograms.compileStatements(frontend, src.onExit, location + "/on-exit"));
        }
        for (var sound : src.soundEffects) {
            dst.addSoundEffects(sound.effect);
        }
        if (src.blendTransition != null) {
            var blend = BlendTransitionProto.newBuilder();
            if (src.blendTransition.linearLength != null) {
                blend.setLinearLength(src.blendTransition.linearLength);
            } else {
                for (var point : src.blendTransition.points.entrySet()) {
                    blend.addPoints(
                        BlendPointProto.newBuilder().setKey(point.getKey()).setValue(point.getValue()).build());
                }
            }
            dst.setBlendTransition(blend.build());
        }
        return dst.build();
    }
}
