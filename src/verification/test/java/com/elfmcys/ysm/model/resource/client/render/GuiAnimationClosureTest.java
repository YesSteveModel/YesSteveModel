package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationReferenceProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.InstructionKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StateProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.util.ProtoUtil;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GuiAnimationClosureTest {
    @Test
    void fullAnimationPreviewRetainsEveryMainAnimation() {
        var source = model(animation("idle"), animation("gui"), animation("world_only")).toBuilder()
            .putAnimationFiles("fp_arm", AnimationFileProto.newBuilder().addAnimations(animation("arm_only")).build())
            .build();
        var main = ExpressionSet.MAIN.select(source);

        var selected = GuiAnimationClosure.select(main, null, settings("gui"), ModelResourcePurpose.FULL,
            RenderTargetKind.PLAYER);

        assertSame(main, selected);
        assertEquals(Set.of("idle", "gui", "world_only"), names(selected, "local"));
        assertFalse(selected.animationFiles().containsKey("fp_arm"));
        assertFalse(ModelResourcePurpose.FULL.isGui());
        assertNotEquals(ModelResourcePurpose.GUI.ruleId(), ModelResourcePurpose.FULL.ruleId());
    }

    @Test
    void nonPlayerGuiTargetsRetainCompleteMainInsteadOfUsingPlayerControllerRoots() {
        var source = model(animation("idle"), animation("projectile_motion"), animation("vehicle_motion"));
        for (var kind : List.of(RenderTargetKind.PROJECTILE, RenderTargetKind.VEHICLE)) {
            var selected = GuiAnimationClosure.select(source, null, settings("idle"), ModelResourcePurpose.GUI, kind);
            assertSame(source, selected);
            assertEquals(Set.of("idle", "projectile_motion", "vehicle_motion"), names(selected, "local"));
        }
    }

    @Test
    void guiRootsAndDeclaredControllersRetainBothLocalAndDefaultSources() throws Exception {
        var expected = new HashSet<>(Set.of("gui", "idle", "hover", "hover_fadeout", "focus", "empty", "state_pose"));
        for (int index = 0; index < 8; index++) {
            expected.add("pre_parallel" + index);
            expected.add("parallel" + index);
        }
        var file = AnimationFileProto.newBuilder();
        expected.forEach(name -> file.addAnimations(animation(name)));
        file.addAnimations(animation("world_only"));
        var controllers =
            AnimationControllerFileProto.newBuilder()
                .addControllers(
                    AnimationControllerProto.newBuilder().setName("player.post_main")
                        .addStates(StateProto.newBuilder().setName("default").addAnimations(AnimationReferenceProto
                            .newBuilder().setName("state_pose").setCondition(program("1")).build()).build())
                        .build())
                .build();
        var input = ModelDataProto.newBuilder().putAnimationFiles("local", file.build())
            .putAnimationFiles("fallback/source/player/main/base", file.build())
            .putAnimationControllers("custom", controllers).build();

        var actual = GuiAnimationClosure.select(input, null, settings("gui"));

        assertEquals(expected, names(actual, "local"));
        assertEquals(expected, names(actual, "fallback/source/player/main/base"));
        assertSame(controllers, actual.animationControllers().get("custom"));
        assertEquals(Set.of("idle"),
            names(GuiAnimationClosure.select(model(animation("idle"), animation("world_only")), null, settings("")),
                "local"));
    }

    @Test
    void literalAndFiniteDynamicSelectionCloseTransitivelyThroughInstructionsAndFunctions() throws Exception {
        var first = animation("first").toBuilder().addInstructionKeyframes(InstructionKeyFrameProto.newBuilder()
            .setPrograms(program("ctrl.set_animation(v.flag ? 'second' : 'third')")).build()).build();
        var functions = StringDataProto.newBuilder()
            .addUserFunctions(
                UserFunctionProto.newBuilder().setName("init@player_init").setBody(program("fn.choose()")).build())
            .addUserFunctions(UserFunctionProto.newBuilder().setName("choose")
                .setBody(program("ctrl.set_animation('first')")).build())
            .build();
        var actual = GuiAnimationClosure.select(
            model(animation("gui"), first, animation("second"), animation("third"), animation("world_only")), functions,
            settings("gui"));

        assertEquals(Set.of("gui", "first", "second", "third"), names(actual, "local"));
    }

    @Test
    void unboundedNamesRetainAllMainInputsInsteadOfChangingPurpose() throws Exception {
        var gui = animation("gui").toBuilder().addInstructionKeyframes(
            InstructionKeyFrameProto.newBuilder().setPrograms(program("ctrl.set_animation(v.runtime_name)")).build())
            .build();
        var input = model(gui, animation("world_only"));
        assertSame(input, GuiAnimationClosure.select(input, null, settings("gui")));
    }

    @Test
    void prunedUnitExecutesAuthoredInitializationUpdateFunctionAndParallelEffects(@TempDir Path root) throws Exception {
        var gui = animation("gui").toBuilder()
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(program("v.trace + fn.offset()")).build())
            .build();
        var parallel =
            animation("parallel0").toBuilder()
                .addInstructionKeyframes(
                    InstructionKeyFrameProto.newBuilder().setPrograms(program("v.trace = v.trace + 11")).build())
                .build();
        var functions = StringDataProto.newBuilder()
            .addUserFunctions(
                UserFunctionProto.newBuilder().setName("@player_init").setBody(program("v.trace = 2")).build())
            .addUserFunctions(UserFunctionProto.newBuilder().setName("@player_update")
                .setBody(program("v.trace = v.trace + 3")).build())
            .addUserFunctions(UserFunctionProto.newBuilder().setName("offset").setBody(program("return 7;")).build())
            .build();
        var settings = settings("gui");
        var selected = GuiAnimationClosure.select(model(gui, parallel, animation("world_only")), functions, settings);
        var host = ExpressionHost.create(HostAvailability.none());
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(root, track(new AtomicSharedCache(root)))), host,
            new ExpressionEngineMetrics());
        try (
            var module = factory.createSet(() -> false, new Hash256(new byte[32]), "player", ExpressionSet.MAIN,
                host.expressionBinding(), selected, functions, settings, ProtoUtil.serializeToArray(settings), false,
                ModelResourcePurpose.GUI);
            var animations = module.animations(List.of("local"), null); var runtime = new EntityExpressionRuntime()) {
            assertTrue(module.compilationReport().replacements().isEmpty());
            var init = module
                .requireVoidProgram(location("function/@player_init", ProgramRole.EVENT_PLAYER_INIT, "player_init:0"));
            var update = module.requireVoidProgram(
                location("function/@player_update", ProgramRole.EVENT_PLAYER_UPDATE, "player_update:0"));
            TestExpressionModules.replace(runtime, module,
                Map.of("player_init", List.of(init), "player_update", List.of(update)));
            var inputs = new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<net.minecraft.world.entity.Entity>() {
                @Override
                public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {}
            }, (commands, provenance) -> () -> {
            }, () -> false);
            float actual = runtime.update(inputs, evaluation -> {
                evaluation.dispatchPlayerInit();
                evaluation.dispatchPlayerUpdate(true);
                assertEquals(12, evaluation.evaluate(animations.get("gui").blendWeight));
                evaluation.execute(animations.get("parallel0").customInstructionKeyframes.get(0).getEventData()[0]);
                return evaluation.evaluate(animations.get("gui").blendWeight);
            });
            assertEquals(23, actual);
            assertFalse(animations.containsKey("world_only"));
        }
    }

    private static SemanticLocation location(String path, ProgramRole role, String discriminator) {
        return new SemanticLocation("player", ExpressionSet.MAIN, path, role, discriminator);
    }

    private static SettingsProto settings(String preview) {
        return SettingsProto.newBuilder().setPreviewAnimation(preview).build();
    }

    private static AnimationProto animation(String name) {
        return AnimationProto.newBuilder().setName(name).build();
    }

    private static ModelDataProto model(AnimationProto... animations) {
        var file = AnimationFileProto.newBuilder();
        for (var animation : animations)
            file.addAnimations(animation);
        return ModelDataProto.newBuilder().putAnimationFiles("local", file.build()).build();
    }

    private static Set<String> names(ModelDataProto model, String file) {
        var names = new HashSet<String>();
        for (var animation : model.animationFiles().get(file).animations())
            names.add(animation.name());
        return names;
    }

    private static ProgramProto program(String source) throws Exception {
        return EncodedProgramMapper.toProto(new MolangFrontend().compile(source, MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("test/gui-closure")).program());
    }
}
