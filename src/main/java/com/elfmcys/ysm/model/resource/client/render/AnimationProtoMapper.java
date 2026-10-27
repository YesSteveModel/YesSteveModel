package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.expression.aot.BooleanProgram;
import com.elfmcys.ysm.expression.aot.FloatExpression;
import com.elfmcys.ysm.expression.aot.VoidProgram;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerState;
import com.elfmcys.ysm.geckolib3.core.controller.transition.IBlendTransition;
import com.elfmcys.ysm.geckolib3.core.controller.transition.LinearBlendTransition;
import com.elfmcys.ysm.geckolib3.core.controller.transition.SegmentedBlendTransition;
import com.elfmcys.ysm.geckolib3.file.AnimationControllerFile;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BlendPointProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StateProto;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Comparator;

public final class AnimationProtoMapper {
    private AnimationProtoMapper() {}

    public static Animation animation(AnimationProto source, String domain, ProgramBinder binder) {
        var samples = new java.util.HashMap<Integer, FloatExpression.Program>();
        var instructions = new java.util.HashMap<Integer, VoidProgram>();
        var baked = BakedAnimationMapper.bake(source, domain, location -> {
            var program = binder.bindFloat(location, ProgramRole.ANIMATION_SAMPLE, "");
            samples.put(program.reference().sourceId(), program);
            return program.reference().sourceId();
        }, location -> {
            var program = binder.bindVoid(location, ProgramRole.ANIMATION_INSTRUCTION, "");
            instructions.put(program.reference().sourceId(), program);
            return program.reference().sourceId();
        });
        var animation = BakedAnimationMapper.animation(baked, samples::get, instructions::get);
        ResourceDiagnostics.track(ResourceDiagnostics.Kind.ANIM, animation, domain + '/' + source.name() + " bones="
            + animation.boneAnimations.size() + " ticks=" + animation.animationLength);
        return animation;
    }

    public static AnimationControllerFile controllerFile(AnimationControllerFileProto source, String domain,
        ProgramBinder binder) {
        var controllers = new Object2ReferenceOpenHashMap<String, AnimationControllerData>();
        for (var controller : source.controllers()) {
            var states = new AnimationControllerState[controller.states().size()];
            for (var index = 0; index < states.length; index++) {
                states[index] = state(controller.states().get(index), domain + '/' + controller.name(), binder);
            }
            controllers.put(controller.name(),
                new AnimationControllerData(controller.defaultState().orElse(""), states));
        }
        return new AnimationControllerFile(controllers);
    }

    @SuppressWarnings("unchecked")
    private static AnimationControllerState state(StateProto source, String controller, ProgramBinder binder) {
        var base = controller + "/state/" + source.name();
        var animations = new Pair[source.animations().size()];
        for (var index = 0; index < animations.length; index++) {
            var animation = source.animations().get(index);
            animations[index] = Pair.of(animation.name(), binder.bindBoolean(base + "/animation",
                ProgramRole.CONTROLLER_ANIMATION, animation.name() + ':' + index));
        }
        var transitions = new Pair[source.transitions().size()];
        for (var index = 0; index < transitions.length; index++) {
            var transition = source.transitions().get(index);
            transitions[index] = Pair.of(transition.dst(), binder.bindBoolean(base + "/transition",
                ProgramRole.CONTROLLER_TRANSITION, transition.dst() + ':' + index));
        }
        var onEntry = source.hasOnEntry()
            ? new VoidProgram[] {binder.bindVoid(base + "/on-entry", ProgramRole.CONTROLLER_ON_ENTRY, "")}
            : new VoidProgram[0];
        var onExit = source.hasOnExit()
            ? new VoidProgram[] {binder.bindVoid(base + "/on-exit", ProgramRole.CONTROLLER_ON_EXIT, "")}
            : new VoidProgram[0];
        return new AnimationControllerState(source.name(), animations, transitions, new String[0], onEntry, onExit,
            blend(source), false);
    }

    private static IBlendTransition blend(StateProto source) {
        if (!source.hasBlendTransition()) {
            return new LinearBlendTransition(0);
        }
        var blend = source.blendTransition().orElseThrow();
        if (blend.hasLinearLength()) {
            return new LinearBlendTransition(blend.linearLengthUnsafe());
        }
        if (blend.points().size() < 2) {
            return new LinearBlendTransition(0);
        }
        var points = new ArrayList<BlendPointProto>();
        blend.points().forEach(points::add);
        points.sort(Comparator.comparingDouble(BlendPointProto::key));
        var times = new float[points.size()];
        var positions = new float[points.size()];
        for (var index = 0; index < points.size(); index++) {
            times[index] = points.get(index).key();
            positions[index] = points.get(index).value_();
        }
        return new SegmentedBlendTransition(times, positions);
    }

    public interface ProgramBinder {
        FloatExpression.Program bindFloat(String location, ProgramRole role, String discriminator);

        BooleanProgram bindBoolean(String location, ProgramRole role, String discriminator);

        VoidProgram bindVoid(String location, ProgramRole role, String discriminator);
    }
}
