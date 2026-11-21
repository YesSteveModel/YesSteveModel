package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.EngineMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AnimationCacheIndependenceTest {
    private static final Hash256 CONTAINER = new Hash256(new byte[Hash256.SIZE]);
    private static final SettingsProto SETTINGS = SettingsProto.newBuilder().build();

    @Test
    void independentProcessesRecoverOnlyTheMissingClassOfFile(@TempDir Path root) throws Exception {
        child(root, "seed");
        var input = Path.of(Files.readString(root.resolve("input.path")));
        var aot = Path.of(Files.readString(root.resolve("aot.path")));
        var inputBytes = Files.readAllBytes(input);
        Files.delete(aot);
        child(root, "inputs-only");
        assertFalse(Files.exists(aot), "Input-only acquisition must not recreate AOT");
        child(root, "restore-aot");
        assertArrayEquals(inputBytes, Files.readAllBytes(input));
        assertEquals(input, Path.of(Files.readString(root.resolve("input.path"))));
        var replacementAot = Path.of(Files.readString(root.resolve("aot.path")));
        assertNotEquals(aot, replacementAot);
        var aotBytes = Files.readAllBytes(replacementAot);
        Files.delete(input);
        child(root, "restore-input");
        assertEquals(replacementAot, Path.of(Files.readString(root.resolve("aot.path"))));
        assertArrayEquals(aotBytes, Files.readAllBytes(replacementAot));
        assertNotEquals(input, Path.of(Files.readString(root.resolve("input.path"))));
        child(root, "warm");
    }

    @Test
    void nativeIndexReclaimsEitherLayoutWithoutRevokingTheOther(@TempDir Path root) throws Exception {
        try (var cache = new AtomicSharedCache(root)) {
            var inputs = new AnimationInputStore(root, cache);
            var artifacts = new ExpressionArtifactStore(root, cache);
            var host = ExpressionHost.create(HostAvailability.none());
            var factory =
                new RenderTargetExpressionModuleFactory(artifacts, inputs, host, new ExpressionEngineMetrics());
            try (var module = create(factory, definition("8"), false)) {
                inputs.awaitPersistence();
                var inputKey = AnimationInputStore.indexKey(request(definition("8")).identity());
                try (var running = com.elfmcys.ysm.natives.NativeCancellation.create();
                    var bake = com.elfmcys.ysm.natives.storage.NativeBakeIndex.open(cache.layoutRoot(), running)) {
                    var rows = bake.pageObjects(java.util.OptionalLong.empty(), running).rows();
                    assertEquals(2, rows.size());
                    var input = rows.stream().filter(value -> value.key().equals(inputKey)).findFirst().orElseThrow();
                    var aot = rows.stream()
                        .filter(value -> value.key().exact().equals(module.diagnostics().identity().lookupKey()))
                        .findFirst().orElseThrow();
                    assertNotEquals(input.key().partition(), aot.key().partition());
                    assertNotEquals(input.file().location(), aot.file().location());
                    assertEquals(input.file().location(),
                        bake.lookupExact(input.key(), running).rows().get(0).location());
                    assertEquals(aot.file().location(), bake.lookupExact(aot.key(), running).rows().get(0).location());
                    assertTrue(bake.revoke(input.file().location(), running).committed());
                    assertTrue(bake.forget(input.file().location(), running).committed());
                    assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Outcome.MISSING,
                        bake.lookupExact(input.key(), running).outcome());
                    assertEquals(aot.file().location(), bake.lookupExact(aot.key(), running).rows().get(0).location());
                    assertTrue(bake.publish(input, running).committed());
                    assertTrue(bake.revoke(aot.file().location(), running).committed());
                    assertTrue(bake.forget(aot.file().location(), running).committed());
                    assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Outcome.MISSING,
                        bake.lookupExact(aot.key(), running).outcome());
                    assertEquals(input.file().location(),
                        bake.lookupExact(input.key(), running).rows().get(0).location());
                }
            }
        }
    }

    @Test
    void fullRejectsAnotherSourcesInputBeforePublishingACandidate(@TempDir Path root) throws Exception {
        try (var cache = new AtomicSharedCache(root)) {
            var inputs = new AnimationInputStore(root, cache);
            var artifacts = new ExpressionArtifactStore(root, cache);
            var host = ExpressionHost.create(HostAvailability.none());
            var metrics = new ExpressionEngineMetrics();
            var factory = new RenderTargetExpressionModuleFactory(artifacts, inputs, host, metrics);
            try (var ignored = create(factory, definition("8"), false)) {
                inputs.awaitPersistence();
            }
            var expected = request(definition("8"));
            var different = inputs.acquire(request(definition("9")), false);
            inputs.awaitPersistence();
            Files.write(inputs.path(expected.identity()), Files.readAllBytes(inputs.path(different.identity())));
            inputs.clearMemory();
            artifacts.clearMemory();
            var before = metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION);
            assertThrows(com.elfmcys.ysm.format.AssetLoadException.class, () -> create(factory, definition("8"), true));
            assertEquals(before, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
            assertEquals(AnimationInputStore.Source.DISK, inputs.acquire(request(definition("9")), true).source());
        }
    }

    @Test
    void fullResourceAndAnimationInputSurviveEachOthersCacheEviction(@TempDir Path root) throws Exception {
        try (var cache = new AtomicSharedCache(root)) {
            var inputs = new AnimationInputStore(root, cache);
            var artifacts = new ExpressionArtifactStore(root, cache);
            var request = request(definition("8"));
            var first = inputs.acquire(request, false);
            var host = ExpressionHost.create(HostAvailability.none());
            var factory =
                new RenderTargetExpressionModuleFactory(artifacts, inputs, host, new ExpressionEngineMetrics());
            try (var module = create(factory, definition("8"), false)) {
                assertSame(first.animations(), inputs.acquire(request, true).animations());
                inputs.awaitPersistence();
                artifacts.clearMemory();
                Files.delete(artifacts.path(module.diagnostics().identity()));
                assertNotNull(first.animations().source().load("main", "idle"));
                inputs.clearMemory();
                Files.delete(inputs.path(request.identity()));
                try (var runtime = new EntityExpressionRuntime()) {
                    runtime.install(
                        runtime.prepare(module, com.elfmcys.ysm.expression.host.TestComponentHolders.linkageScope(),
                            dev.semaweave.RoamingRole.PRODUCER),
                        () -> {
                        });
                    final FloatExpression.Program program;
                    try (var preparation = module.prepare()) {
                        program = preparation.requireFloatProgram(
                            new com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation("player",
                                "animation/main/idle/blend-weight",
                                com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_SAMPLE, ""));
                    }
                    float actual = runtime.update(new EntityExpressionRuntime.InvocationInputs(
                        new com.elfmcys.ysm.expression.host.InvocationInputs<net.minecraft.world.entity.Entity>() {}),
                        evaluation -> evaluation.evaluate(program));
                    assertEquals(8f, actual);
                }
            }
            assertNotNull(first.animations().source().load("main", "idle"));
        }
    }

    private static void child(Path root, String mode) throws Exception {
        var args = root.resolve(mode + ".args");
        var log = root.resolve(mode + ".log");
        Files.write(args,
            List.of("-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName(), root.toString(), mode)
                .stream().map(value -> "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\"").toList());
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + args)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), () -> "Child timed out: " + mode);
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(log);
                } catch (java.io.IOException failure) {
                    return failure.toString();
                }
            });
        } finally {
            if (process.isAlive())
                process.destroyForcibly().waitFor();
        }
    }

    private static AnimationInputStore.Request request(ModelDataProto definition) throws Exception {
        return AnimationInputStore.prepare(CONTAINER, "player", ExpressionSet.MAIN, definition, null, SETTINGS,
            CompilerOptions.DEFAULT);
    }

    private static com.elfmcys.ysm.model.resource.client.AnimationStore create(
        RenderTargetExpressionModuleFactory factory, ModelDataProto definition, boolean cacheOnly) throws Exception {
        return factory.createStore(() -> false, CONTAINER, "player", ExpressionSet.MAIN, factory.expressionBinding(),
            definition, null, SETTINGS, new byte[0], cacheOnly);
    }

    private static ModelDataProto definition(String source) throws Exception {
        var program = EncodedProgramMapper.toProto(new MolangFrontend().compile(source,
            MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("independent-input")).program());
        return ModelDataProto.newBuilder()
            .putAnimationFiles("main",
                AnimationFileProto.newBuilder()
                    .addAnimations(AnimationProto.newBuilder().setName("idle")
                        .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(program).build()).build())
                    .build())
            .build();
    }

    public static final class ProcessFixture {
        public static void main(String[] arguments) throws Exception {
            com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
            var root = Path.of(arguments[0]);
            var mode = arguments[1];
            try (var cache = new AtomicSharedCache(root)) {
                var inputs = new AnimationInputStore(root, cache);
                var artifacts = new ExpressionArtifactStore(root, cache);
                var definition = definition("8");
                var request = request(definition);
                var existing = inputs.acquire(request, true);
                if (mode.equals("inputs-only")) {
                    assertNotNull(existing);
                    assertEquals(AnimationInputStore.Source.DISK, existing.source());
                    var animation = existing.animations().source().load("main", "idle");
                    var program = existing.animations().loadProgram(animation.blendWeight().orElseThrow().programId());
                    assertEquals(request.programs().values().iterator().next(), program);
                    assertEquals(0, cache.derivedWork().snapshot().succeeded());
                    return;
                }
                var missingInput = mode.equals("seed") || mode.equals("restore-input");
                assertEquals(missingInput, existing == null);
                var host = ExpressionHost.create(HostAvailability.none());
                var metrics = new ExpressionEngineMetrics();
                var factory = new RenderTargetExpressionModuleFactory(artifacts, inputs, host, metrics);
                try (var module = create(factory, definition, false)) {
                    var coldAot = mode.equals("seed") || mode.equals("restore-aot");
                    assertEquals(coldAot ? ExpressionArtifactStore.Source.COLD : ExpressionArtifactStore.Source.DISK,
                        module.source());
                    assertEquals(coldAot ? 1 : 0,
                        metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
                    inputs.awaitPersistence();
                    Files.writeString(root.resolve("input.path"), inputs.path(request.identity()).toString());
                    Files.writeString(root.resolve("aot.path"),
                        artifacts.path(module.diagnostics().identity()).toString());
                }
            }
        }
    }
}
