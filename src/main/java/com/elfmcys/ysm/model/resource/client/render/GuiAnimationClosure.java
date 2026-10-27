package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.host.HostSymbol;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ProgramNode;
import dev.semaweave.program.ProgramStructuralException;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** A complete GUI input unit; uncertain animation selection retains the entire MAIN input. */
final class GuiAnimationClosure {
    private static final String SET_ANIMATION = HostSymbol.CTRL_SET_ANIMATION.stableId();
    private final Set<String> names = new HashSet<>();
    private boolean unbounded;

    static ModelDataProto select(ModelDataProto source, StringDataProto functions, SettingsProto settings,
        ModelResourcePurpose purpose, RenderTargetKind kind) {
        return purpose == ModelResourcePurpose.GUI && kind == RenderTargetKind.PLAYER
            ? select(source, functions, settings) : source;
    }

    static ModelDataProto select(ModelDataProto source, StringDataProto functions, SettingsProto settings) {
        var closure = new GuiAnimationClosure();
        var preview = settings.previewAnimation().orElse("");
        // Screenshots and texture cards use idle even when catalog cards use an authored preview.
        closure.names.add("idle");
        if (!preview.isBlank())
            closure.names.add(preview);
        closure.names.addAll(Set.of("hover", "hover_fadeout", "focus", "empty"));
        for (int index = 0; index < 8; index++) {
            closure.names.add("parallel" + index);
            closure.names.add("pre_parallel" + index);
        }

        // Declared controllers replace coded predicates, including predicates that stop in GUI.
        for (var file : source.animationControllers().values()) {
            for (var controller : file.controllers()) {
                for (var state : controller.states()) {
                    for (var animation : state.animations()) {
                        closure.names.add(animation.name());
                        closure.program(animation.condition());
                    }
                    for (var transition : state.transitions())
                        closure.program(transition.condition());
                    state.onEntry().ifPresent(closure::program);
                    state.onExit().ifPresent(closure::program);
                }
            }
        }
        if (functions != null)
            functions.userFunctions().forEach(function -> closure.program(function.body()));
        for (var button : settings.extraAnimationButtons()) {
            for (var form : button.configForms()) {
                closure.program(form.readProgram());
                closure.program(form.writeProgram());
                for (var label : form.labels())
                    closure.program(label.actionProgram());
            }
        }

        var visited = new HashSet<String>();
        while (!closure.unbounded) {
            var pending = new HashSet<>(closure.names);
            pending.removeAll(visited);
            if (pending.isEmpty())
                break;
            visited.addAll(pending);
            for (var file : source.animationFiles().values()) {
                for (var animation : file.animations()) {
                    if (pending.contains(animation.name()))
                        closure.animation(animation);
                }
            }
        }
        if (closure.unbounded)
            return source;

        var result = source.toBuilder().clearAnimationFiles();
        source.animationFiles().forEach((name, file) -> {
            var retained = file.toBuilder().clearAnimations();
            for (var animation : file.animations()) {
                if (closure.names.contains(animation.name()))
                    retained.addAnimations(animation);
            }
            result.putAnimationFiles(name, retained.build());
        });
        return result.build();
    }

    private void animation(AnimationProto animation) {
        animation.blendWeight().ifPresent(value -> {
            if (value.hasProgram())
                program(value.program());
        });
        for (var bone : animation.boneAnimations()) {
            frames(bone.rotation());
            frames(bone.position());
            frames(bone.scale());
        }
        for (var frame : animation.instructionKeyframes())
            program(frame.programs());
    }

    private void frames(Iterable<BoneKeyFrameProto> frames) {
        for (var frame : frames) {
            for (var value : frame.pre())
                if (value.hasProgram())
                    program(value.program());
            for (var value : frame.post())
                if (value.hasProgram())
                    program(value.program());
        }
    }

    private void program(ProgramProto source) {
        if (unbounded)
            return;
        try {
            var codec = PortableProgramCodecs.v1();
            var pending = new ArrayDeque<>(codec.decode(EncodedProgramMapper.fromProto(source, codec)).roots());
            while (!pending.isEmpty()) {
                var node = pending.removeLast();
                if (node instanceof ProgramNode.Symbol symbol) {
                    if (symbol.ref().domain() == ProgramNode.SymbolDomain.EXTERNAL
                        && symbol.ref().name().equals(SET_ANIMATION)
                        && symbol.accessForm() == ProgramNode.AccessForm.INVOKE) {
                        if (symbol.arguments().isEmpty() || !animationNames(symbol.arguments().get(0))) {
                            unbounded = true;
                            return;
                        }
                    }
                    pending.addAll(symbol.arguments());
                } else if (node instanceof ProgramNode.Member value)
                    pending.add(value.target());
                else if (node instanceof ProgramNode.Index value) {
                    pending.add(value.target());
                    pending.add(value.index());
                } else if (node instanceof ProgramNode.Unary value)
                    pending.add(value.operand());
                else if (node instanceof ProgramNode.Binary value) {
                    pending.add(value.left());
                    pending.add(value.right());
                } else if (node instanceof ProgramNode.Math value)
                    pending.addAll(value.arguments());
                else if (node instanceof ProgramNode.Coalesce value) {
                    pending.add(value.left());
                    pending.add(value.right());
                } else if (node instanceof ProgramNode.Conditional value) {
                    pending.add(value.condition());
                    pending.add(value.ifTrue());
                    pending.add(value.ifFalse());
                } else if (node instanceof ProgramNode.Assignment value) {
                    pending.add(value.target());
                    pending.add(value.value());
                } else if (node instanceof ProgramNode.Block value)
                    pending.addAll(value.children());
                else if (node instanceof ProgramNode.Control value)
                    value.value().ifPresent(pending::add);
                else if (node instanceof ProgramNode.Loop value) {
                    pending.add(value.count());
                    pending.add(value.body());
                } else if (node instanceof ProgramNode.ForEach value) {
                    pending.add(value.iterable());
                    pending.add(value.body());
                } else if (node instanceof ProgramNode.WithContext value) {
                    pending.add(value.subject());
                    pending.add(value.body());
                }
            }
        } catch (ProgramStructuralException | IllegalArgumentException failure) {
            // Let the ordinary compiler own invalid-input diagnostics after conservative expansion.
            unbounded = true;
        }
    }

    private boolean animationNames(ProgramNode node) {
        if (node instanceof ProgramNode.StringLiteral literal) {
            names.add(literal.value());
            return true;
        }
        if (node instanceof ProgramNode.Conditional conditional)
            return animationNames(conditional.ifTrue()) && animationNames(conditional.ifFalse());
        if (node instanceof ProgramNode.Coalesce coalesce)
            return animationNames(coalesce.left()) && animationNames(coalesce.right());
        return false;
    }
}
