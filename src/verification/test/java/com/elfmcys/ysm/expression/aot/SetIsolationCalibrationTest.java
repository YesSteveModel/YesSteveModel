package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.format.parser.AnimationBuilder;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

final class SetIsolationCalibrationTest {
    @Test
    void commonLifecycleHasOneBodyOwnerAndDeferredAndCodedCallbacksStayInTheirSet(@TempDir Path root) throws Exception {
        var host = ExpressionHost.create(HostAvailability.none());
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(root, track(new AtomicSharedCache(root)))), host,
            new ExpressionEngineMetrics());
        var strings = StringDataProto.newBuilder().addUserFunctions(function("@player_init", "v.lifecycle=1;"))
            .addUserFunctions(function("@player_update", "v.lifecycle=2;"))
            .addUserFunctions(function("@sync", "v.sync=args[0];"))
            .addUserFunctions(function("@defer", "v.deferred=args[0];"))
            .addUserFunctions(function("@player_ctrl_main", "return 2;")).build();
        var profile = factory.expressionBinding();
        var definition = ModelDataProto.newBuilder().build();
        var settings = SettingsProto.newBuilder().build();
        var id = new Hash256(new byte[32]);
        try (
            var body =
                factory.createSet(() -> false, id, "player", com.elfmcys.ysm.expression.inventory.ExpressionSet.MAIN,
                    profile, definition, strings, settings, new byte[0], false);
            var arm =
                factory.createSet(() -> false, id, "player", com.elfmcys.ysm.expression.inventory.ExpressionSet.FP_ARM,
                    profile, definition, strings, settings, new byte[0], false);
            var bodyRuntime = new EntityExpressionRuntime(); var armRuntime = new EntityExpressionRuntime()) {
            var bodyInit = event(body, "player_init", ProgramRole.EVENT_PLAYER_INIT);
            var bodyUpdate = event(body, "player_update", ProgramRole.EVENT_PLAYER_UPDATE);
            var bodySync = event(body, "sync", ProgramRole.EVENT_SYNC);
            var bodyDefer = event(body, "defer", ProgramRole.EVENT_DEFER);
            var armDefer = event(arm, "defer", ProgramRole.EVENT_DEFER);
            TestExpressionModules.replace(bodyRuntime, body,
                Map.of("player_init", java.util.List.of(bodyInit), "player_update", java.util.List.of(bodyUpdate),
                    "sync", java.util.List.of(bodySync), "defer", java.util.List.of(bodyDefer)));
            TestExpressionModules.replace(armRuntime, arm, Map.of("defer", java.util.List.of(armDefer)),
                RoamingRole.CONSUMER);
            assertThrows(IllegalArgumentException.class,
                () -> event(arm, "player_init", ProgramRole.EVENT_PLAYER_INIT));
            assertThrows(IllegalArgumentException.class, () -> event(arm, "sync", ProgramRole.EVENT_SYNC));
            bodyRuntime.update(inputs(), evaluation -> {
                evaluation.dispatchPlayerInit();
                evaluation.dispatchPlayerUpdate(true);
                evaluation.dispatchSync(EventArguments.sync(3));
                evaluation.dispatchDeferred(EventArguments.deferredFloats(10));
                return null;
            });
            armRuntime.update(inputs(), evaluation -> {
                evaluation.dispatchPlayerInit();
                evaluation.dispatchPlayerUpdate(true);
                evaluation.dispatchSync(EventArguments.sync(9));
                evaluation.dispatchDeferred(EventArguments.deferredFloats(20));
                assertEquals(com.elfmcys.ysm.geckolib3.core.PlayState.CONTINUE,
                    evaluation.codedController(arm
                        .requireFloatProgram(new ModelProgramInventory.SemanticLocation("player", arm.identity().set(),
                            "function/@player_ctrl_main", ProgramRole.EVENT_CODED_CONTROLLER, "player_ctrl_main:0"))));
                return null;
            });
            assertEquals(10F, watch(body, bodyRuntime, "v.deferred"));
            assertEquals(20F, watch(arm, armRuntime, "v.deferred"));
            assertEquals(2F, watch(body, bodyRuntime, "v.lifecycle"));
            assertEquals(3F, watch(body, bodyRuntime, "v.sync"));
            assertTrue(body.compilationReport().replacements().isEmpty());
            assertTrue(arm.compilationReport().replacements().isEmpty());
        }
    }

    private static VoidProgram event(RenderTargetExpressionModule module, String name, ProgramRole role) {
        return module.requireVoidProgram(new ModelProgramInventory.SemanticLocation("player", module.identity().set(),
            "function/@" + name, role, name + ":0"));
    }

    private static Object watch(RenderTargetExpressionModule module, EntityExpressionRuntime runtime, String expression)
        throws Exception {
        try (var watch = runtime.compileTransient(runtime.generation(), "return " + expression + ";",
            TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
            "fixture:common", () -> false)) {
            return runtime.executeTransient(inputs(), watch);
        }
    }

    @Test
    void borrowedDefaultFunctionsKeepTheirOriginWhenLocalCommonNamesCollide(@TempDir Path root) throws Exception {
        var host = ExpressionHost.create(HostAvailability.none());
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(root, track(new AtomicSharedCache(root)))), host,
            new ExpressionEngineMetrics());
        var origin = "fallback/" + new Hash256(new byte[32]) + "/player/main/";
        var local = animation("return fn.offset();");
        var borrowed = animation("return fn.nested();");
        var definition = ModelDataProto.newBuilder().putAnimationFiles("main", local)
            .putAnimationFiles(origin + "main", borrowed).build();
        var strings = StringDataProto.newBuilder().addUserFunctions(function("offset", "return 100;"))
            .addUserFunctions(function(origin + "offset", "return 23;"))
            .addUserFunctions(function(origin + "nested", "return fn.offset()+5;")).build();
        try (
            var module = factory.createSet(() -> false, new Hash256(new byte[32]), "player",
                com.elfmcys.ysm.expression.inventory.ExpressionSet.MAIN, factory.expressionBinding(), definition,
                strings, SettingsProto.newBuilder().build(), new byte[0], false);
            var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            assertTrue(module.compilationReport().replacements().isEmpty());
            float localValue = runtime.update(inputs(), evaluation -> evaluation.evaluate(program(module, "main")));
            float borrowedValue =
                runtime.update(inputs(), evaluation -> evaluation.evaluate(program(module, origin + "main")));
            assertEquals(100F, localValue);
            assertEquals(28F, borrowedValue);
        }
    }

    private static com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto animation(String expression) {
        var json = new JsonObject();
        var idle = new JsonObject();
        idle.addProperty("blend_weight", expression);
        var animations = new JsonObject();
        animations.add("idle", idle);
        json.add("animations", animations);
        return AnimationBuilder.build(AnimationFile.createGson(false).fromJson(json, AnimationFile.class));
    }

    private static UserFunctionProto function(String name, String body) throws Exception {
        var compiled = new MolangFrontend().compile(body, MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("fixture/Common/" + name)).program();
        return UserFunctionProto.newBuilder().setName(name).setBody(EncodedProgramMapper.toProto(compiled)).build();
    }

    @Test
    void independentFixtureCompilesAndDistinguishesSameNameAndOrdinaryTypes(@TempDir Path root) throws Exception {
        JsonObject fixture;
        try (var input = new InputStreamReader(getClass().getResourceAsStream("/render-target-calibration/sets.json"),
            StandardCharsets.UTF_8)) {
            fixture = JsonParser.parseReader(input).getAsJsonObject();
        }
        var host = ExpressionHost.create(HostAvailability.none());
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(root, track(new AtomicSharedCache(root)))), host,
            new ExpressionEngineMetrics());
        try (var body = module(factory, fixture, "body"); var arm = module(factory, fixture, "arm");
            var bodyRuntime = new EntityExpressionRuntime(); var armRuntime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(bodyRuntime, body, Map.of(), RoamingRole.PRODUCER);
            TestExpressionModules.replace(armRuntime, arm, Map.of(), RoamingRole.CONSUMER);
            var payload = new RoamingPayload(new TreeMap<>(Map.of("shared", 3F)));
            bodyRuntime.initializeRoaming(payload);
            armRuntime.initializeRoaming(payload);
            var bodyProgram = program(body, "main");
            var armProgram = program(arm, "fp_arm");
            assertNotSame(bodyProgram.generation(), armProgram.generation());
            assertTrue(body.compilationReport().replacements().isEmpty());
            assertTrue(arm.compilationReport().replacements().isEmpty());
            float bodyValue = bodyRuntime.update(inputs(), evaluation -> evaluation.evaluate(bodyProgram));
            float armValue = armRuntime.update(inputs(), evaluation -> evaluation.evaluate(armProgram));
            assertEquals(103F, bodyValue);
            assertEquals(203F, armValue);
            assertEquals(1F, ordinary(body, bodyRuntime));
            assertEquals("arm", ordinary(arm, armRuntime));
            armRuntime.roamingState().orElseThrow().write("shared", 5);
            assertEquals(3F, bodyRuntime.roamingState().orElseThrow().read("shared"));
            float changedArmValue = armRuntime.update(inputs(), evaluation -> evaluation.evaluate(armProgram));
            assertEquals(205F, changedArmValue);
        }
    }

    private static RenderTargetExpressionModule module(RenderTargetExpressionModuleFactory factory, JsonObject fixture,
        String side) throws Exception {
        var entry = fixture.getAsJsonObject(side);
        var file = new JsonObject();
        file.add("animations", entry.get("animations"));
        var definition =
            ModelDataProto.newBuilder()
                .putAnimationFiles(entry.get("set").getAsString(),
                    AnimationBuilder.build(AnimationFile.createGson(false).fromJson(file, AnimationFile.class)))
                .build();
        var source = fixture.getAsJsonObject("commonFunctions").get("offset").getAsString();
        var encoded =
            EncodedProgramMapper.toProto(new MolangFrontend().compile(source, MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("fixture/Common/offset")).program());
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("offset").setBody(encoded).build()).build();
        var bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte)1);
        var set = side.equals("body") ? com.elfmcys.ysm.expression.inventory.ExpressionSet.MAIN
            : com.elfmcys.ysm.expression.inventory.ExpressionSet.FP_ARM;
        return factory.createSet(() -> false, new Hash256(bytes), "player", set, factory.expressionBinding(),
            definition, strings, SettingsProto.newBuilder().build(), new byte[0], false);
    }

    private static FloatExpression.Program program(RenderTargetExpressionModule module, String set) {
        return module.requireFloatProgram(new ModelProgramInventory.SemanticLocation("player", module.identity().set(),
            "animation/" + set + "/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""));
    }

    private static Object ordinary(RenderTargetExpressionModule module, EntityExpressionRuntime runtime)
        throws Exception {
        try (var watch = runtime.compileTransient(runtime.generation(), "return v.kind;",
            TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH, "fixture:kind",
            () -> false)) {
            return runtime.executeTransient(inputs(), watch);
        }
    }

    private static EntityExpressionRuntime.InvocationInputs inputs() {
        return new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<net.minecraft.world.entity.Entity>() {
            @Override
            public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {}
        }, (commands, provenance) -> () -> {
        }, () -> false);
    }
}
