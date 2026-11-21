package com.elfmcys.ysm.expression.aot;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.RoamingRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class DefaultAnimationInputTest {
    private static final String PREFIX = "fallback/" + "a".repeat(64) + "/player/main/";
    private final MolangFrontend frontend = new MolangFrontend();

    @Test
    void receivingOptionsCoverBothAotSetsDefaultsPublicFunctionsAndFrozenTransient(@TempDir Path cache)
        throws Exception {
        var source = defaults("1/0+11");
        var factory = factory(cache);
        for (boolean enabled : List.of(false, true)) {
            var options = new dev.semaweave.compilation.CompilerOptions(enabled);
            for (var set : ExpressionSet.values()) {
                for (var purpose : List.of(ModelResourcePurpose.FULL)) {
                    try (
                        var cold = module(factory, source, "player", set, "1/0+5", purpose, false,
                            factory.expressionBinding(), options);
                        var warm = module(factory, source, "player", set, "1/0+5", purpose, true,
                            factory.expressionBinding(), options);
                        var runtime = runtime(warm)) {
                        assertEquals(options, cold.diagnostics().identity().expected().options());
                        assertEquals(options, warm.diagnostics().identity().expected().options());
                        assertEquals(cold.diagnostics().identity(), warm.diagnostics().identity());
                        if (enabled) {
                            assertEquals(5f, evaluate(runtime, warm, false));
                            assertEquals(11f, evaluate(runtime, warm, true));
                        } else {
                            for (boolean borrowed : List.of(false, true)) {
                                var failure =
                                    assertThrows(ExecutionFailure.class, () -> evaluate(runtime, warm, borrowed));
                                assertInstanceOf(dev.semaweave.ValueContractException.class, failure.getCause());
                            }
                        }
                        try (var transientCode = runtime.compileTransient(runtime.generation(),
                            "return 1/0+fn.common();", TransientExpressionSession.Privilege.LOCAL_WATCH,
                            TransientExpressionSession.Purpose.WATCH, "fixture:division", () -> false)) {
                            var inputs = new EntityExpressionRuntime.InvocationInputs(
                                new InvocationInputs<net.minecraft.world.entity.Entity>() {});
                            if (enabled)
                                assertEquals(5f, runtime.executeTransient(inputs, transientCode));
                            else {
                                var failure = assertThrows(ExecutionFailure.class,
                                    () -> runtime.executeTransient(inputs, transientCode));
                                assertInstanceOf(dev.semaweave.ValueContractException.class, failure.getCause());
                            }
                            runtime.install(
                                runtime.prepare(cold, TestComponentHolders.linkageScope(), RoamingRole.PRODUCER),
                                () -> {
                                });
                            assertThrows(IllegalStateException.class,
                                () -> runtime.executeTransient(new EntityExpressionRuntime.InvocationInputs(
                                    new InvocationInputs<net.minecraft.world.entity.Entity>() {}), transientCode));
                        }
                    }
                }
            }
        }
    }

    @Test
    void guiReceivingOptionsApplyToLocalAndDefaultFunctionsWithoutAot(@TempDir Path cache) throws Exception {
        var source = defaults("1/0+11");
        var factory = factory(cache);
        for (boolean enabled : List.of(false, true)) {
            var options = new dev.semaweave.compilation.CompilerOptions(enabled);
            try (var module = module(factory, source, "player", ExpressionSet.MAIN, "1/0+5", ModelResourcePurpose.GUI,
                false, factory.expressionBinding(), options)) {
                assertTrue(module.interpreted());
                for (boolean borrowed : List.of(false, true)) {
                    try (var runtime = runtime(module)) {
                        if (enabled)
                            assertEquals(borrowed ? 11F : 5F, evaluate(runtime, module, borrowed));
                        else {
                            var failure =
                                assertThrows(ExecutionFailure.class, () -> evaluate(runtime, module, borrowed));
                            assertInstanceOf(dev.semaweave.ValueContractException.class, failure.getCause());
                        }
                    }
                }
            }
        }
    }

    @Test
    void sourceFunctionsAndMutableStateStayIndependentAcrossModelsAndInstances(@TempDir Path cache) throws Exception {
        var source = defaults("11");
        var factory = factory(cache);
        try (var first = module(factory, source, "player", ExpressionSet.MAIN, "101", ModelResourcePurpose.FULL, false);
            var second = module(factory, source, "player", ExpressionSet.MAIN, "202", ModelResourcePurpose.FULL, false);
            var firstRuntime = runtime(first); var anotherRuntime = runtime(first);
            var secondRuntime = runtime(second)) {
            assertEquals(101F, evaluate(firstRuntime, first, false));
            assertEquals(202F, evaluate(secondRuntime, second, false));
            assertEquals(11F, evaluate(firstRuntime, first, true));
            assertEquals(22F, evaluate(firstRuntime, first, true));
            assertEquals(11F, evaluate(anotherRuntime, first, true));
            assertEquals(11F, evaluate(secondRuntime, second, true));
            assertTrue(first.diagnostics().compilationReport().replacements().isEmpty());
            assertTrue(second.diagnostics().compilationReport().replacements().isEmpty());
        }
    }

    @Test
    void defaultVersionAndNonPlayerSetsCompileTheirOwnMeaning(@TempDir Path cache) throws Exception {
        var old = defaults("11");
        var replacement = defaults("29");
        var factory = factory(cache);
        for (var target : List.of("projectile", "vehicle")) {
            for (var set : ExpressionSet.values()) {
                try (var before = module(factory, old, target, set, "101", ModelResourcePurpose.FULL, false);
                    var after = module(factory, replacement, target, set, "101", ModelResourcePurpose.FULL, false);
                    var beforeRuntime = runtime(before); var afterRuntime = runtime(after)) {
                    assertEquals(11F, evaluate(beforeRuntime, before, true));
                    assertEquals(29F, evaluate(afterRuntime, after, true));
                    assertEquals(101F, evaluate(afterRuntime, after, false));
                    assertNotEquals(before.diagnostics().identity().cacheKey(),
                        after.diagnostics().identity().cacheKey());
                }
            }
        }
    }

    @Test
    void defaultOriginUsesFunctionsFromTheCurrentComposition(@TempDir Path cache) throws Exception {
        var source = defaults("11");
        var factory = factory(cache);
        var definition =
            ModelDataProto.newBuilder().putAnimationFiles(PREFIX + "main", file(source.animation())).build();
        var strings = StringDataProto.newBuilder().addUserFunctions(function("common", "41")).build();
        try (
            var module = factory.createStore(() -> false, new Hash256(new byte[Hash256.SIZE]), "projectile",
                ExpressionSet.FP_ARM, factory.expressionBinding(), definition, strings, null, new byte[0], false,
                ModelResourcePurpose.FULL, dev.semaweave.compilation.CompilerOptions.DEFAULT);
            var runtime = runtime(module)) {
            assertEquals(41F, evaluate(runtime, module, true));
            assertTrue(module.diagnostics().compilationReport().replacements().isEmpty());
        }
    }

    @Test
    void purposeArtifactsCoexistAndReadOnlyMissDoesNotPoisonPreparation(@TempDir Path cache) throws Exception {
        var source = defaults("11");
        var factory = factory(cache);
        var miss = assertThrows(AssetLoadException.class,
            () -> module(factory, source, "player", ExpressionSet.MAIN, "101", ModelResourcePurpose.GUI, true));
        assertEquals(AssetLoadException.Reason.ACCESS, miss.reason());
        try (var full = module(factory, source, "player", ExpressionSet.MAIN, "101", ModelResourcePurpose.FULL, false);
            var gui = module(factory, source, "player", ExpressionSet.MAIN, "101", ModelResourcePurpose.GUI, false);
            var fullWarm =
                module(factory, source, "player", ExpressionSet.MAIN, "101", ModelResourcePurpose.FULL, true);
            var guiWarm = module(factory, source, "player", ExpressionSet.MAIN, "101", ModelResourcePurpose.GUI, true);
            var fullRuntime = runtime(fullWarm); var guiRuntime = runtime(guiWarm)) {
            assertTrue(gui.interpreted());
            assertTrue(guiWarm.interpreted());
            assertEquals(full.diagnostics().identity().cacheKey(), fullWarm.diagnostics().identity().cacheKey());
            assertNotSame(gui.metadata().executionGeneration(), guiWarm.metadata().executionGeneration());
            assertEquals(11F, evaluate(fullRuntime, fullWarm, true));
            assertEquals(11F, evaluate(guiRuntime, guiWarm, true));
        }
    }

    @Test
    void subjectPartitionsCoexistInMemoryAndDiskWithoutFalsePlayerHits(@TempDir Path cache) throws Exception {
        var source = defaults("11");
        var shared = track(new AtomicSharedCache(cache));
        var store = track(new ExpressionArtifactStore(cache, shared));
        var host = ExpressionHost.create(HostAvailability.none());
        var factory = new RenderTargetExpressionModuleFactory(store, new AnimationInputStore(cache, shared), host,
            new ExpressionEngineMetrics());
        var bindings =
            List.of(factory.expressionBinding(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class),
                factory.expressionBinding(net.minecraft.client.player.AbstractClientPlayer.class),
                factory.expressionBinding(net.minecraft.world.entity.LivingEntity.class));
        var purposes = List.of(ModelResourcePurpose.FULL, ModelResourcePurpose.FULL, ModelResourcePurpose.FULL);
        var containerId = new Hash256(new byte[Hash256.SIZE]);
        var identities = new ArrayList<ExpressionArtifactIdentity>();
        var paths = new HashSet<Path>();
        assertEquals(3, new HashSet<>(bindings).size());
        for (int index = 0; index < bindings.size(); index++) {
            var binding = bindings.get(index);
            assertFalse(factory.mayHaveCachedArtifacts(containerId, binding, purposes.get(index)));
            try (
                var cold =
                    module(factory, source, "player", ExpressionSet.MAIN, "101", purposes.get(index), false, binding);
                var warm =
                    module(factory, source, "player", ExpressionSet.MAIN, "101", purposes.get(index), true, binding)) {
                assertEquals(ExpressionArtifactStore.Source.COLD, cold.source());
                assertEquals(ExpressionArtifactStore.Source.MEMORY, warm.source());
                assertEquals(cold.diagnostics().identity(), warm.diagnostics().identity());
                assertTrue(factory.mayHaveCachedArtifacts(containerId, binding, purposes.get(index)));
                identities.add(cold.diagnostics().identity());
                store.awaitPersistence();
                var path = store.path(cold.diagnostics().identity());
                assertEquals(cache.resolve("f/b"), path.getParent());
                assertTrue(path.getFileName().toString().matches("[0-9a-f]{16}"));
                assertTrue(Files.isRegularFile(path));
                assertTrue(paths.add(path));
            }
            for (int absent = index + 1; absent < bindings.size(); absent++) {
                assertFalse(factory.mayHaveCachedArtifacts(containerId, bindings.get(absent), purposes.get(absent)));
            }
        }
        assertEquals(3, identities.stream().map(ExpressionArtifactIdentity::cacheKey).distinct().count());
        var diskFactory = factory(cache);
        for (int index = 0; index < bindings.size(); index++) {
            var diskBinding =
                diskFactory.expressionBinding(bindings.get(index).bindingPlan().rootSubjectType().orElseThrow());
            assertEquals(bindings.get(index), diskBinding);
            assertTrue(diskFactory.mayHaveCachedArtifacts(containerId, diskBinding, purposes.get(index)));
            try (var disk = module(diskFactory, source, "player", ExpressionSet.MAIN, "101", purposes.get(index), true,
                diskBinding)) {
                assertEquals(ExpressionArtifactStore.Source.DISK, disk.source());
                assertEquals(identities.get(index).cacheKey(), disk.diagnostics().identity().cacheKey());
                assertEquals(identities.get(index).expected().identity(),
                    disk.diagnostics().identity().expected().identity());
            }
        }
    }

    @Test
    void negativeProbeAlsoFindsMemoryOnlyArtifactsOfTheRequestedBinding(@TempDir Path cache) throws Exception {
        var source = defaults("11");
        var shared = track(new AtomicSharedCache(cache));
        var store = track(new ExpressionArtifactStore(cache, shared, (key, target, validator, writer, gate) -> {
            throw new IOException("test has no persistent artifact store");
        }, 16));
        var factory = new RenderTargetExpressionModuleFactory(store, new AnimationInputStore(cache, shared),
            ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics());
        var gui = factory.expressionBinding(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class);
        var player = factory.expressionBinding(net.minecraft.client.player.AbstractClientPlayer.class);
        var containerId = new Hash256(new byte[Hash256.SIZE]);
        try (var module =
            module(factory, source, "player", ExpressionSet.MAIN, "101", ModelResourcePurpose.FULL, false, gui)) {
            assertThrows(java.nio.file.NoSuchFileException.class, () -> store.path(module.diagnostics().identity()));
            assertTrue(factory.mayHaveCachedArtifacts(containerId, gui, ModelResourcePurpose.FULL));
            assertFalse(factory.mayHaveCachedArtifacts(containerId, player, ModelResourcePurpose.FULL));
        }
    }

    private Source defaults(String value) throws Exception {
        var animation = animation("idle", "v.counter=(v.counter??0)+fn.common();return v.counter;");
        var strings = StringDataProto.newBuilder().addUserFunctions(function(PREFIX + "common", value)).build();
        return new Source(animation, strings);
    }

    private AnimationStore module(RenderTargetExpressionModuleFactory factory, Source source, String target,
        ExpressionSet set, String localValue, ModelResourcePurpose purpose, boolean cacheOnly) throws Exception {
        return module(factory, source, target, set, localValue, purpose, cacheOnly, factory.expressionBinding());
    }

    private AnimationStore module(RenderTargetExpressionModuleFactory factory, Source source, String target,
        ExpressionSet set, String localValue, ModelResourcePurpose purpose, boolean cacheOnly,
        ExpressionBinding binding) throws Exception {
        return module(factory, source, target, set, localValue, purpose, cacheOnly, binding,
            dev.semaweave.compilation.CompilerOptions.DEFAULT);
    }

    private AnimationStore module(RenderTargetExpressionModuleFactory factory, Source source, String target,
        ExpressionSet set, String localValue, ModelResourcePurpose purpose, boolean cacheOnly,
        ExpressionBinding binding, dev.semaweave.compilation.CompilerOptions options) throws Exception {
        var definition = ModelDataProto.newBuilder().putAnimationFiles("main", file(animation("idle", "fn.common()")))
            .putAnimationFiles(PREFIX + "main", file(source.animation())).build();
        var strings = StringDataProto.newBuilder().addUserFunctions(function("common", localValue));
        strings.addAllUserFunctions(source.strings().userFunctions());
        return factory.createStore(() -> false, new Hash256(new byte[Hash256.SIZE]), target, set, binding, definition,
            strings.build(), SettingsProto.newBuilder().build(), new byte[0], cacheOnly, purpose, options);
    }

    private static RenderTargetExpressionModuleFactory factory(Path cache) {
        var shared = track(new AtomicSharedCache(cache));
        return new RenderTargetExpressionModuleFactory(track(new ExpressionArtifactStore(cache, shared)),
            new AnimationInputStore(cache, shared), ExpressionHost.create(HostAvailability.none()),
            new ExpressionEngineMetrics());
    }

    private static EntityExpressionRuntime runtime(AnimationStore module) {
        var runtime = new EntityExpressionRuntime();
        runtime.install(runtime.prepare(module, TestComponentHolders.linkageScope(), RoamingRole.PRODUCER), () -> {
        });
        return runtime;
    }

    private static FloatExpression.Program program(AnimationStore store,
        ModelProgramInventory.SemanticLocation location) {
        try (var preparation = store.prepare()) {
            return preparation.requireFloatProgram(location);
        }
    }

    private static float evaluate(EntityExpressionRuntime runtime, AnimationStore module, boolean borrowed) {
        var location = new ModelProgramInventory.SemanticLocation(module.metadata().targetId(), module.metadata().set(),
            "animation/" + (borrowed ? PREFIX : "") + "main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, "");
        return runtime.update(
            new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<net.minecraft.world.entity.Entity>() {

            }), evaluation -> evaluation.evaluate(program(module, location)));
    }

    private AnimationProto animation(String name, String source) throws Exception {
        return AnimationProto.newBuilder().setName(name)
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(program(source)).build()).build();
    }

    private static AnimationFileProto file(AnimationProto animation) {
        return AnimationFileProto.newBuilder().addAnimations(animation).build();
    }

    private UserFunctionProto function(String name, String source) throws Exception {
        return UserFunctionProto.newBuilder().setName(name).setBody(program(source)).build();
    }

    private ProgramProto program(String source) throws Exception {
        return EncodedProgramMapper.toProto(frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("default-animation-input-test")).program());
    }

    private record Source(AnimationProto animation, StringDataProto strings) {
    }
}
