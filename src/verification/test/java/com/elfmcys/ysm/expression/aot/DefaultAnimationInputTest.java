package com.elfmcys.ysm.expression.aot;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.host.EffectCommand;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
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
            assertTrue(first.compilationReport().replacements().isEmpty());
            assertTrue(second.compilationReport().replacements().isEmpty());
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
                    assertNotEquals(before.identity().cacheKey(), after.identity().cacheKey());
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
        try (var module =
            factory.createSet(() -> false, new Hash256(new byte[Hash256.SIZE]), "projectile", ExpressionSet.FP_ARM,
                factory.expressionBinding(), definition, strings, null, new byte[0], false, ModelResourcePurpose.FULL);
            var runtime = runtime(module)) {
            assertEquals(41F, evaluate(runtime, module, true));
            assertTrue(module.compilationReport().replacements().isEmpty());
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
            assertNotEquals(full.identity().cacheKey(), gui.identity().cacheKey());
            assertEquals(full.identity().cacheKey(), fullWarm.identity().cacheKey());
            assertEquals(gui.identity().cacheKey(), guiWarm.identity().cacheKey());
            assertEquals(11F, evaluate(fullRuntime, fullWarm, true));
            assertEquals(11F, evaluate(guiRuntime, guiWarm, true));
        }
    }

    @Test
    void subjectPartitionsCoexistInMemoryAndDiskWithoutFalsePlayerHits(@TempDir Path cache) throws Exception {
        var source = defaults("11");
        var store = track(new ExpressionArtifactStore(cache, track(new AtomicSharedCache(cache))));
        var host = ExpressionHost.create(HostAvailability.none());
        var factory = new RenderTargetExpressionModuleFactory(store, host, new ExpressionEngineMetrics());
        var bindings =
            List.of(factory.expressionBinding(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class),
                factory.expressionBinding(net.minecraft.client.player.AbstractClientPlayer.class),
                factory.expressionBinding(net.minecraft.world.entity.LivingEntity.class));
        var purposes = List.of(ModelResourcePurpose.GUI, ModelResourcePurpose.FULL, ModelResourcePurpose.FULL);
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
                assertEquals(cold.identity(), warm.identity());
                assertTrue(factory.mayHaveCachedArtifacts(containerId, binding, purposes.get(index)));
                identities.add(cold.identity());
                var path = store.path(cold.identity());
                assertEquals(purposes.get(index).ruleId(), path.getParent().getFileName().toString());
                assertEquals(binding.key(), path.getParent().getParent().getFileName().toString());
                store.awaitPersistence();
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
                assertEquals(identities.get(index).cacheKey(), disk.identity().cacheKey());
                assertEquals(identities.get(index).expected().identity(), disk.identity().expected().identity());
            }
        }
    }

    @Test
    void negativeProbeAlsoFindsMemoryOnlyArtifactsOfTheRequestedBinding(@TempDir Path cache) throws Exception {
        var source = defaults("11");
        var store = track(new ExpressionArtifactStore(cache, (key, target, validator, writer, gate) -> {
            throw new IOException("test has no persistent artifact store");
        }, 16));
        var factory = new RenderTargetExpressionModuleFactory(store, ExpressionHost.create(HostAvailability.none()),
            new ExpressionEngineMetrics());
        var gui = factory.expressionBinding(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class);
        var player = factory.expressionBinding(net.minecraft.client.player.AbstractClientPlayer.class);
        var containerId = new Hash256(new byte[Hash256.SIZE]);
        try (var module =
            module(factory, source, "player", ExpressionSet.MAIN, "101", ModelResourcePurpose.GUI, false, gui)) {
            assertFalse(Files.exists(store.path(module.identity()).getParent()));
            assertTrue(factory.mayHaveCachedArtifacts(containerId, gui, ModelResourcePurpose.GUI));
            assertFalse(factory.mayHaveCachedArtifacts(containerId, player, ModelResourcePurpose.FULL));
        }
    }

    private Source defaults(String value) throws Exception {
        var animation = animation("idle", "v.counter=v.counter+fn.common();return v.counter;");
        var strings = StringDataProto.newBuilder().addUserFunctions(function(PREFIX + "common", value)).build();
        return new Source(animation, strings);
    }

    private RenderTargetExpressionModule module(RenderTargetExpressionModuleFactory factory, Source source,
        String target, ExpressionSet set, String localValue, ModelResourcePurpose purpose, boolean cacheOnly)
        throws Exception {
        return module(factory, source, target, set, localValue, purpose, cacheOnly, factory.expressionBinding());
    }

    private RenderTargetExpressionModule module(RenderTargetExpressionModuleFactory factory, Source source,
        String target, ExpressionSet set, String localValue, ModelResourcePurpose purpose, boolean cacheOnly,
        ExpressionBinding binding) throws Exception {
        var definition = ModelDataProto.newBuilder().putAnimationFiles("main", file(animation("idle", "fn.common()")))
            .putAnimationFiles(PREFIX + "main", file(source.animation())).build();
        var strings = StringDataProto.newBuilder().addUserFunctions(function("common", localValue));
        strings.addAllUserFunctions(source.strings().userFunctions());
        return factory.createSet(() -> false, new Hash256(new byte[Hash256.SIZE]), target, set, binding, definition,
            strings.build(), SettingsProto.newBuilder().build(), new byte[0], cacheOnly, purpose);
    }

    private static RenderTargetExpressionModuleFactory factory(Path cache) {
        return new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(cache, track(new AtomicSharedCache(cache)))),
            ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics());
    }

    private static EntityExpressionRuntime runtime(RenderTargetExpressionModule module) {
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, module, Map.of());
        return runtime;
    }

    private static float evaluate(EntityExpressionRuntime runtime, RenderTargetExpressionModule module,
        boolean borrowed) {
        var location = new ModelProgramInventory.SemanticLocation(module.identity().targetId(), module.identity().set(),
            "animation/" + (borrowed ? PREFIX : "") + "main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, "");
        return runtime.update(new EntityExpressionRuntime.InvocationInputs(new InvocationInputs() {
            @Override
            public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {}
        }, (commands, provenance) -> () -> {
        }, () -> false), evaluation -> evaluation.evaluate(module.requireFloatProgram(location)));
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
