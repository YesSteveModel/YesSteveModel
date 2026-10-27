package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAnimationSet;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAssetConstant;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAssetView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.InstructionKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.compilation.ArtifactCodecs;
import dev.semaweave.compilation.EngineMetrics;
import dev.semaweave.program.ReadOnlyBytes;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import static org.junit.jupiter.api.Assertions.*;

class AnimationSetArtifactTest {
    @Test
    void localReadViewsSharePhysicalChannelWithoutChangingTheSharedSource(@TempDir Path root) throws Exception {
        var store = store(root);
        try (var module =
            create(factory(store, new ExpressionEngineMetrics()), definition(), ExpressionSet.MAIN, false)) {
            store.awaitPersistence();
            var file = store.path(module.identity());
            var opens = new AtomicInteger();
            var closes = new AtomicInteger();
            var set = countedSet(file, opens, closes);
            try (var backing = set.bind(List.of("main"), module, null).shared();
                var first = AnimationStore.consumerView(backing, ignored -> ModelResourceFailureGate.none());
                var second = AnimationStore.consumerView(backing, ignored -> ModelResourceFailureGate.none())) {
                try (var phase = first.openReadPhase()) {
                    assertEquals(0, opens.get(), "A phase must not preload animations");
                    assertNotNull(phase.animations().get("idle"));
                    assertNotNull(phase.animations().get("other"));
                    assertEquals(1, opens.get());
                    assertEquals(0, closes.get());
                }
                assertEquals(1, closes.get());
                assertSame(first.get("idle"), second.get("idle"));
                assertEquals(1, opens.get(), "Verified shared results outlive the read phase");
            }
            try (var next = set.bind(List.of("main"), module, null); var phase = next.openReadPhase()) {
                assertNotNull(phase.animations().get("idle"));
                assertNotNull(phase.animations().get("other"));
                assertEquals(2, opens.get());
            }
            assertEquals(2, closes.get());
            try (var phase = set.source().openReadPhase()) {
                assertNotNull(phase.load("main", "idle"));
                Files.write(file, new byte[] {1}, StandardOpenOption.APPEND);
                assertThrows(IOException.class, () -> phase.load("main", "other"));
                assertEquals(3, closes.get());
                assertThrows(IOException.class, () -> phase.load("main", "idle"));
                assertEquals(3, opens.get());
            }
            assertThrows(IOException.class, () -> set.source().load("main", "idle"));
            assertEquals(4, opens.get());
            assertEquals(4, closes.get());
        }
    }

    @Test
    void sixteenEntryLruEvictsOnlyTheStoreReference(@TempDir Path root) throws Exception {
        var store = store(root);
        var factory = factory(store, new ExpressionEngineMetrics());
        var definition = definition();
        var settings = SettingsProto.newBuilder().build();
        var modules = new java.util.ArrayList<RenderTargetExpressionModule>();
        try {
            for (int i = 0; i < 17; i++) {
                var hash = new byte[Hash256.SIZE];
                hash[0] = (byte)i;
                modules.add(factory.createSet(() -> false, new Hash256(hash), "player", ExpressionSet.MAIN,
                    factory.expressionBinding(), definition, null, settings, ProtoUtil.serializeToArray(settings),
                    false));
                store.awaitPersistence();
            }
            try (var active = modules.get(0).animations(List.of("main"), null)) {
                assertNotNull(active.get("idle"), "LRU must preserve the active module");
            }
            var hash = new byte[Hash256.SIZE];
            hash[0] = 16;
            try (var retained = factory.createSet(() -> false, new Hash256(hash), "player", ExpressionSet.MAIN,
                factory.expressionBinding(), definition, null, settings, ProtoUtil.serializeToArray(settings), true)) {
                assertEquals(ExpressionArtifactStore.Source.MEMORY, retained.source());
            }
            try (var evicted = create(factory, definition, ExpressionSet.MAIN, true)) {
                assertEquals(ExpressionArtifactStore.Source.DISK, evicted.source());
            }
        } finally {
            modules.forEach(RenderTargetExpressionModule::close);
        }
    }

    @Test
    void oneFileContainsTheCatalogClassesAndEveryAnimationAndLoadsWithoutCompilation(@TempDir Path root)
        throws Exception {
        var definition = definition();
        var store = store(root);
        Path file;
        try (var cold = create(factory(store, new ExpressionEngineMetrics()), definition, ExpressionSet.MAIN, false)) {
            store.awaitPersistence();
            file = store.path(cold.identity());
            assertTrue(file.getFileName().toString().endsWith(".anim.ysm-cache"));
            try (var files = Files.walk(root)) {
                assertEquals(1,
                    files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".ysm-cache") || path.toString().endsWith(".swa"))
                        .count());
            }
            try (var channel = FileChannel.open(file)) {
                var container = AssetContainerReader.read(channel);
                assertEquals("zstd", container.getChunkInfo(BakedAssetConstant.EXPRESSION_CHUNK_NAME).encoding());
                var view = new BakedAssetView(channel);
                var artifact = ArtifactCodecs.v1().decodeAndVerify(
                    ReadOnlyBytes.copyOf(view.readExpression(channel, Integer.MAX_VALUE)), cold.identity().expected(),
                    ExpressionIdentityHash.SHA_256);
                assertFalse(artifact.classes().isEmpty());
                var baked = view.readAnimation(channel, "main", "idle");
                assertTrue(baked.blendWeight().orElseThrow().hasProgramId());
                assertEquals(
                    cold.requireProgram("animation/main/idle/blend-weight",
                        com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_SAMPLE, "").sourceId(),
                    baked.blendWeight().orElseThrow().programId());
                assertTrue(baked.boneAnimations().get(0).rotation().get(0).pre().get(0).hasProgramId());
                assertNotNull(view.readAnimation(channel, "main", "other"));
                assertNotNull(view.readAnimation(channel, "fallback/main", "idle"));
                var descriptor = com.elfmcys.ysm.testutil.ProtobufJavaOracle
                    .parse("ysm.baked.asset.BakedExpressionValue", new byte[0]).getDescriptorForType();
                assertNull(descriptor.findFieldByName("program"));
                assertNotNull(descriptor.findFieldByName("program_id"));
            }
        }

        var before = Files.getLastModifiedTime(file);
        var metrics = new ExpressionEngineMetrics();
        try (var warm = create(factory(store(root), metrics), definition, ExpressionSet.MAIN, true);
            var fallback = warm.animations(List.of("fallback/main"), null);
            var animations = warm.animations(List.of("main"), fallback)) {
            assertEquals(ExpressionArtifactStore.Source.DISK, warm.source());
            assertEquals(AnimationStore.State.UNLOADED, animations.state("idle"));
            var idle = animations.get("idle");
            assertNotNull(idle);
            assertEquals(AnimationStore.State.READY, animations.state("idle"));
            assertNotNull(animations.get("other"));
            assertEquals(0, metrics.snapshot().portableProgramDecodes());
            assertEquals(0, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
            try (var runtime = new EntityExpressionRuntime()) {
                TestExpressionModules.replace(runtime, warm, Map.of());
                float actual = runtime.update(new EntityExpressionRuntime.InvocationInputs(new InvocationInputs() {
                    @Override
                    public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {}
                }, (commands, provenance) -> () -> {
                }, () -> false), evaluation -> {
                    evaluation.execute(idle.customInstructionKeyframes.get(0).getEventData()[0]);
                    assertEquals((float)-Math.PI / 2,
                        idle.boneAnimations.get(0).rotationKeyFrames.get(0).getLerpPoint(evaluation, 1).x, 0.00001f);
                    return evaluation.evaluate(idle.blendWeight);
                });
                assertEquals(7, actual);
            }
        }
        assertEquals(before, Files.getLastModifiedTime(file));
    }

    @Test
    void missingCacheOnlySetDoesNotCompileWriteOrPoisonNormalPreparation(@TempDir Path root) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(store(root), metrics);
        assertThrows(AssetLoadException.class, () -> create(factory, definition(), ExpressionSet.MAIN, true));
        assertEquals(0, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        assertFalse(Files.exists(root.resolve("bake")));
        try (var module = create(factory, definition(), ExpressionSet.MAIN, false)) {
            assertEquals(ExpressionArtifactStore.Source.COLD, module.source());
        }
    }

    @Test
    void animationDamageIsLazyAndFallsBackToTheSameArtifactsDefaultAnimation(@TempDir Path root) throws Exception {
        var store = store(root);
        Path file;
        try (var module =
            create(factory(store, new ExpressionEngineMetrics()), definition(), ExpressionSet.MAIN, false)) {
            file = store.path(module.identity());
        }
        store.awaitPersistence();
        var damaged = Files.readAllBytes(file);
        try (var channel = FileChannel.open(file)) {
            var container = AssetContainerReader.read(channel);
            var manifestChunk = container.getChunkInfo(BakedAssetConstant.MANIFEST_CHUNK_NAME);
            var manifest = com.elfmcys.ysm.proto.baked.asset.BakedAssetManifestProto
                .parseFrom(com.elfmcys.ysm.format.container.InlineChunkReader.readPayloadBytes(channel, manifestChunk));
            for (var entry : manifest.animations()) {
                if (entry.sourceFile().equals("main") && entry.name().equals("idle"))
                    damaged[container.getChunkInfo(entry.chunkName()).offset()] ^= 1;
            }
        }
        Files.write(file, damaged);
        var metrics = new ExpressionEngineMetrics();
        try (var module = create(factory(store(root), metrics), definition(), ExpressionSet.MAIN, true);
            var fallback = module.animations(List.of("fallback/main"), null);
            var animations = module.animations(List.of("main"), fallback)) {
            var recovered = animations.get("idle");
            assertSame(fallback.get("idle"), recovered);
            assertEquals(new FloatExpression.Literal(3), recovered.blendWeight);
            assertEquals(AnimationStore.State.FAILED, animations.state("idle"));
            assertNotNull(animations.get("other"));
            assertEquals(0, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        }
    }

    @Test
    void failedBatchEndsItsStageBeforeSameFileFallbackWithoutPoisoningHealthyNames(@TempDir Path root)
        throws Exception {
        var store = store(root);
        Path file;
        try (var module =
            create(factory(store, new ExpressionEngineMetrics()), definition(), ExpressionSet.MAIN, false)) {
            file = store.path(module.identity());
        }
        store.awaitPersistence();
        var damaged = Files.readAllBytes(file);
        try (var channel = FileChannel.open(file)) {
            var container = AssetContainerReader.read(channel);
            var manifest = com.elfmcys.ysm.proto.baked.asset.BakedAssetManifestProto
                .parseFrom(com.elfmcys.ysm.format.container.InlineChunkReader.readPayloadBytes(channel,
                    container.getChunkInfo(BakedAssetConstant.MANIFEST_CHUNK_NAME)));
            for (var entry : manifest.animations()) {
                if (entry.sourceFile().equals("main") && entry.name().equals("idle"))
                    damaged[container.getChunkInfo(entry.chunkName()).offset()] ^= 1;
            }
        }
        Files.write(file, damaged);
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var failures = new HashMap<String, Throwable>();
        var set = countedSet(file, opens, closes);
        try (
            var module =
                create(factory(store(root), new ExpressionEngineMetrics()), definition(), ExpressionSet.MAIN, true);
            var fallback = set.bind(List.of("fallback/main"), module, null).shared();
            var backing = set.bind(List.of("main"), module, fallback).shared();
            var consumer = AnimationStore.consumerView(backing, name -> new ModelResourceFailureGate() {
                @Override
                public Optional<Throwable> failure() {
                    return Optional.ofNullable(failures.get(name));
                }

                @Override
                public void fail(Throwable cause) {
                    failures.putIfAbsent(name, cause);
                }
            })) {
            try (var batch = consumer.openReadPhase()) {
                var recovered = batch.animations().get("idle");
                assertNotNull(recovered);
                assertSame(fallback.get("idle"), recovered);
                assertEquals(new FloatExpression.Literal(3), recovered.blendWeight);
                assertEquals(2, opens.get(), "Only the failed stage is replaced before fallback");
                assertEquals(1, closes.get(), "The failed channel closes before the replacement stage opens");
                assertNotNull(batch.animations().get("other"));
                assertSame(recovered, batch.animations().get("idle"));
                assertEquals(2, opens.get(), "Healthy names join the new stage; the failed name is not retried");
                assertEquals(Set.of("idle"), failures.keySet());
            }
            assertEquals(2, closes.get());
            try (var independent = consumer.openReadPhase()) {
                assertNotNull(independent.animations().get("other"));
                assertEquals(2, opens.get(), "Verified healthy results survive both stages");
            }
        }
        assertEquals(2, closes.get());
    }

    private static BakedAnimationSet countedSet(Path file, AtomicInteger opens, AtomicInteger closes) throws Exception {
        try (var channel = FileChannel.open(file)) {
            return new BakedAssetView(channel).animationSet(() -> {
                assertEquals(opens.get(), closes.get(), "The old stage must close before a new one opens");
                var physical = FileChannel.open(file);
                opens.incrementAndGet();
                return (SeekableByteChannel)Proxy.newProxyInstance(AnimationSetArtifactTest.class.getClassLoader(),
                    new Class<?>[] {SeekableByteChannel.class}, (proxy, method, args) -> {
                        try {
                            var value = method.invoke(physical, args);
                            if (method.getName().equals("close"))
                                closes.incrementAndGet();
                            return value == physical ? proxy : value;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            });
        }
    }

    @Test
    void closingALazySourceDoesNotWaitForAdmissionAndTheUndeliveredChannelCloses(@TempDir Path root) throws Exception {
        var store = store(root);
        try (var module =
            create(factory(store, new ExpressionEngineMetrics()), definition(), ExpressionSet.MAIN, false)) {
            store.awaitPersistence();
            var file = store.path(module.identity());
            var entered = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var closes = new AtomicInteger();
            final BakedAnimationSet set;
            try (var channel = FileChannel.open(file)) {
                var view = new BakedAssetView(channel);
                set = view.animationSet(() -> {
                    var physical = FileChannel.open(file);
                    entered.countDown();
                    try {
                        assertTrue(resume.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        physical.close();
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                    return (SeekableByteChannel)Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {SeekableByteChannel.class}, (proxy, method, args) -> {
                            try {
                                var result = method.invoke(physical, args);
                                if (method.getName().equals("close"))
                                    closes.incrementAndGet();
                                return result == physical ? proxy : result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                });
            }
            var executor = Executors.newFixedThreadPool(2);
            try (var phase = set.source().openReadPhase()) {
                try {
                    var read = executor.submit(() -> phase.load("main", "idle"));
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    executor.submit(() -> {
                        phase.close();
                        return null;
                    }).get(2, TimeUnit.SECONDS);
                    assertEquals(0, closes.get());
                    assertThrows(ClosedChannelException.class, () -> phase.load("main", "other"));
                    resume.countDown();
                    var failure = assertThrows(ExecutionException.class, () -> read.get(5, TimeUnit.SECONDS));
                    assertInstanceOf(ClosedChannelException.class, failure.getCause());
                    assertEquals(1, closes.get());
                } finally {
                    resume.countDown();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void programIdsRejectInvalidRangeAndWrongRoleAndSetsUseSeparateFiles(@TempDir Path root) throws Exception {
        var store = store(root);
        var factory = factory(store, new ExpressionEngineMetrics());
        try (var main = create(factory, definition(), ExpressionSet.MAIN, false);
            var arm = create(factory, definition(), ExpressionSet.FP_ARM, false)) {
            assertNotEquals(store.path(main.identity()), store.path(arm.identity()));
            var sample = main.requireProgram("animation/main/idle/blend-weight",
                com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_SAMPLE, "").sourceId();
            assertThrows(IllegalArgumentException.class, () -> main.requireVoidProgram(sample));
            assertThrows(IllegalArgumentException.class, () -> main.requireFloatProgram(-1));
            assertThrows(IllegalArgumentException.class, () -> main.requireFloatProgram(65_535));
        }
    }

    private static ExpressionArtifactStore store(Path root) {
        return track(new ExpressionArtifactStore(root, track(new AtomicSharedCache(root))));
    }

    private static RenderTargetExpressionModuleFactory factory(ExpressionArtifactStore store,
        ExpressionEngineMetrics metrics) {
        return new RenderTargetExpressionModuleFactory(store, ExpressionHost.create(HostAvailability.all()), metrics);
    }

    private static RenderTargetExpressionModule create(RenderTargetExpressionModuleFactory factory,
        ModelDataProto definition, ExpressionSet set, boolean cacheOnly) throws Exception {
        var settings = SettingsProto.newBuilder().build();
        return factory.createSet(() -> false, new Hash256(new byte[32]), "player", set, factory.expressionBinding(),
            definition, null, settings, ProtoUtil.serializeToArray(settings), cacheOnly);
    }

    private static ModelDataProto definition() throws Exception {
        var idle = AnimationProto.newBuilder().setName("idle").setLength(10)
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(program("v.value")).build())
            .addInstructionKeyframes(
                InstructionKeyFrameProto.newBuilder().setStartTick(0).setPrograms(program("v.value=7;")).build())
            .addBoneAnimations(BoneAnimationProto.newBuilder().setBoneName("root")
                .addRotation(BoneKeyFrameProto.newBuilder().setStartTick(0)
                    .addPre(ExpressionValueProto.newBuilder().setProgram(program("90")).build()).build())
                .build())
            .build();
        return ModelDataProto.newBuilder()
            .putAnimationFiles("main",
                AnimationFileProto.newBuilder().addAnimations(idle)
                    .addAnimations(AnimationProto.newBuilder().setName("other").build()).build())
            .putAnimationFiles("fallback/main",
                AnimationFileProto.newBuilder().addAnimations(AnimationProto.newBuilder().setName("idle")
                    .setBlendWeight(ExpressionValueProto.newBuilder().setNum(3).build()).build()).build())
            .build();
    }

    private static ProgramProto program(String source) throws Exception {
        return EncodedProgramMapper.toProto(new MolangFrontend().compile(source, MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("test/animation-set")).program());
    }
}
