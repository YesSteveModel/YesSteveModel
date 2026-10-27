package com.elfmcys.ysm.expression.aot;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.compilation.EngineMetrics;
import dev.semaweave.ProgramKey;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.BackendDescriptor;
import dev.semaweave.compilation.CompilationUnit;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.DiagnosticLocation;
import dev.semaweave.compilation.ExpectedArtifactIdentity;
import dev.semaweave.compilation.ProgramSource;
import dev.semaweave.program.EncodedProgram;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ReadOnlyBytes;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionArtifactPipelineTest {
    @Test
    void blockedPersistenceCannotDelayTheOriginalRuntimeCandidate(@TempDir Path temp) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var shared = track(new AtomicSharedCache(temp));
        ExpressionArtifactStore.ArtifactMaterializer blocked = (key, target, validator, writer, gate) -> shared
            .replaceValidated("baked-animation", key, target, validator, file -> {
                entered.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IOException("Timed out waiting for the test release");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                writer.write(file);
            }, gate);
        var store = track(new ExpressionArtifactStore(temp, blocked, 2));
        var metrics = new ExpressionEngineMetrics();
        var input = input();
        try {
            var factory = factory(store, metrics);
            try (var cold = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), null, input.settings(), input.configBytes())) {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(ExpressionArtifactStore.Source.COLD, cold.source());
                assertFalse(Files.exists(store.path(cold.identity())));
                assertNotNull(cold.requireProgram(new ModelProgramInventory.SemanticLocation("player",
                    "animation/main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, "")));
                assertEquals(0, metrics.snapshot().stageCalls(EngineMetrics.Stage.MODULE_VERIFICATION));
            }
            try (var memory = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), null, input.settings(), input.configBytes())) {
                assertEquals(ExpressionArtifactStore.Source.MEMORY, memory.source());
                assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
            }
        } finally {
            release.countDown();
            store.awaitPersistence();
        }
        try (var disk = factory(track(new ExpressionArtifactStore(temp, shared)), new ExpressionEngineMetrics()).create(
            () -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
            input.settings(), input.configBytes())) {
            assertEquals(ExpressionArtifactStore.Source.DISK, disk.source());
        }
    }

    private static final InvocationInputs EMPTY_INPUTS = new InvocationInputs() {
        @Override
        public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {}
    };

    @Test
    void abandonedRuntimeAndWatchReleaseInstanceGenerationAndQuota(@TempDir Path temp) throws Throwable {
        var metrics = new ExpressionEngineMetrics();
        var input = input("1");
        int baseline = TransientExpressionSession.liveLeaseCount();
        try (var module = factory(track(new ExpressionArtifactStore(temp, track(new AtomicSharedCache(temp)))), metrics)
            .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
                input.settings(), input.configBytes())) {
            var references = abandonRuntimeAndWatch(module);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && (references.stream().anyMatch(ref -> ref.get() != null)
                || metrics.snapshot().active(EngineMetrics.Owner.INSTANCES) != 0
                || TransientExpressionSession.liveLeaseCount() > baseline)) {
                System.gc();
                Thread.sleep(10);
            }
            assertTrue(references.stream().allMatch(ref -> ref.get() == null));
            assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.INSTANCES));
            assertEquals(baseline, TransientExpressionSession.liveLeaseCount());
            assertEquals(1, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
        }
        assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
    }

    private static List<java.lang.ref.WeakReference<?>> abandonRuntimeAndWatch(RenderTargetExpressionModule module)
        throws Throwable {
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, module, Map.of());
        var watch = runtime.compileTransient(runtime.generation(), "return 1;",
            TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH, "gc-watch",
            () -> false);
        assertEquals(1f, runtime.executeTransient(inputs(), watch));
        var closed = runtime.compileTransient(runtime.generation(), "return 2;",
            TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH, "closed-watch",
            () -> false);
        closed.close();
        closed.close();
        return List.of(new java.lang.ref.WeakReference<>(runtime), new java.lang.ref.WeakReference<>(watch),
            new java.lang.ref.WeakReference<>(closed));
    }

    @Test
    void publicStoreSurfaceAcceptsNeitherPathsNorExecutableBytes() {
        for (var method : ExpressionArtifactStore.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            for (var parameter : method.getParameterTypes()) {
                assertFalse(parameter == Path.class || parameter == byte[].class
                    || parameter.getName().equals("dev.semaweave.program.ReadOnlyBytes")
                    || parameter.getName().equals("dev.semaweave.compilation.CompiledArtifact"));
            }
        }
    }

    @Test
    void exactIdentitySeparatesEveryYsmAndCompilerInput() throws Exception {
        var host = ExpressionHost.create(HostAvailability.none());
        var backend = new JaninoJavaCompilerBackend().descriptor();
        var primary = new MolangFrontend().compile("1.25", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("identity/primary")).program();
        var otherPrimary = new MolangFrontend().compile("2.5", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("identity/other-primary")).program();
        var fallback = new MolangFrontend().compile("0.0", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("identity/fallback")).program();
        var otherFallback = new MolangFrontend().compile("-1.0", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("identity/other-fallback")).program();
        var baseExpected = expected(host, primary, fallback, CompilerOptions.DEFAULT, backend);
        var model = new ModelFileIdentity(hash(1), hash(2));
        var base = ExpressionArtifactIdentity.create(baseExpected, model, "player", "main",
            new BakeProfile("profile-a"), false, new byte[] {1});

        var identities = new HashSet<String>();
        identities.add(base.cacheKey());
        identities.add(
            ExpressionArtifactIdentity.create(expected(host, otherPrimary, fallback, CompilerOptions.DEFAULT, backend),
                model, "player", "main", new BakeProfile("profile-a"), false, new byte[] {1}).cacheKey());
        identities.add(
            ExpressionArtifactIdentity.create(expected(host, primary, otherFallback, CompilerOptions.DEFAULT, backend),
                model, "player", "main", new BakeProfile("profile-a"), false, new byte[] {1}).cacheKey());
        identities
            .add(
                ExpressionArtifactIdentity
                    .create(
                        expected(ExpressionHost.create(HostAvailability.all()), primary, fallback,
                            CompilerOptions.DEFAULT, backend),
                        model, "player", "main", new BakeProfile("profile-a"), false, new byte[] {1})
                    .cacheKey());
        identities.add(ExpressionArtifactIdentity.create(
            expected(host, primary, fallback, CompilerOptions.DEFAULT,
                new BackendDescriptor(backend.stableId(), "different-version", backend.abiVersion(),
                    new TreeMap<>(Map.of("mode", "test")))),
            model, "player", "main", new BakeProfile("profile-a"), false, new byte[] {1}).cacheKey());
        identities.add(ExpressionArtifactIdentity.create(baseExpected, new ModelFileIdentity(hash(3), hash(2)),
            "player", "main", new BakeProfile("profile-a"), false, new byte[] {1}).cacheKey());
        identities.add(ExpressionArtifactIdentity.create(baseExpected, new ModelFileIdentity(hash(1), hash(3)),
            "player", "main", new BakeProfile("profile-a"), false, new byte[] {1}).cacheKey());
        identities.add(ExpressionArtifactIdentity
            .create(baseExpected, model, "npc", "main", new BakeProfile("profile-a"), false, new byte[] {1})
            .cacheKey());
        identities.add(ExpressionArtifactIdentity
            .create(baseExpected, model, "player", "alt", new BakeProfile("profile-a"), false, new byte[] {1})
            .cacheKey());
        identities.add(ExpressionArtifactIdentity
            .create(baseExpected, model, "player", "main", new BakeProfile("profile-b"), false, new byte[] {1})
            .cacheKey());
        identities.add(ExpressionArtifactIdentity
            .create(baseExpected, model, "player", "main", new BakeProfile("profile-a"), true, new byte[] {1})
            .cacheKey());
        identities.add(ExpressionArtifactIdentity
            .create(baseExpected, model, "player", "main", new BakeProfile("profile-a"), false, new byte[] {2})
            .cacheKey());
        identities.add(ExpressionArtifactIdentity.create(baseExpected, model, "player", "main",
            new BakeProfile("profile-a"), host.expressionBinding(), false, new byte[] {1}).cacheKey());

        assertEquals(9, identities.size());
        assertEquals(base, ExpressionArtifactIdentity.create(baseExpected, new ModelFileIdentity(hash(99), hash(2)),
            "player", "other-texture", new BakeProfile("other-bake"), true, new byte[] {1}));
        assertEquals(base, ExpressionArtifactIdentity.create(baseExpected, model, "player", "main",
            new BakeProfile("profile-a"), false, new byte[] {1}));
        assertEquals(17, base.expected().identity().javaFeature());
        assertEquals(61, base.expected().identity().classfileMajor());
        assertEquals(ExpressionIdentityHash.SHA_256.descriptor(), base.expected().identity().hashAlgorithm());
        assertNotEquals(base.expected().identity().input().registryFingerprint(),
            expected(ExpressionHost.create(HostAvailability.all()), primary, fallback, CompilerOptions.DEFAULT, backend)
                .identity().input().registryFingerprint());
    }

    @Test
    void revisionTwoLookupIncludesEventCatalogAndProductionLinkage() throws Exception {
        var input = input();
        var host = ExpressionHost.create(HostAvailability.all());
        var backend = new JaninoJavaCompilerBackend().descriptor();
        var linkage = HostLinkage.production(host);
        var catalog = MolangEventCatalog.production();
        var base = lookup(input, host, catalog, linkage, backend);

        var routes = new java.util.ArrayList<>(catalog.routes());
        var route = routes.get(0);
        routes.set(0,
            new MolangEventCatalog.Route(route.routeId(), route.role(), route.payload() + "!", route.result(),
                route.maximumArguments(), route.handlerSelection(), route.ordering(), route.fallback(), route.effect(),
                route.diagnostic(), route.publicReachability()));
        var changedCatalog = lookup(input, host, new MolangEventCatalog(routes), linkage, backend);
        var changedLinkage = lookup(input, host, catalog, linkage.withAbiVersion(linkage.abiVersion() + 1), backend);

        assertNotEquals(base.fingerprint(), changedCatalog.fingerprint());
        assertNotEquals(base.eventCatalogFingerprint(), changedCatalog.eventCatalogFingerprint());
        assertNotEquals(base.fingerprint(), changedLinkage.fingerprint());
        assertNotEquals(base.productionLinkageFingerprint(), changedLinkage.productionLinkageFingerprint());
    }

    @Test
    void missingProductionLinkageRejectsCandidateBeforeActivationAndKeepsOldTarget(@TempDir Path temp)
        throws Exception {
        var paths = temp.resolve("cache");
        var store = track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths))));
        var input = input();
        var host = ExpressionHost.create(HostAvailability.all());
        var goodMetrics = new ExpressionEngineMetrics();
        var good = new RenderTargetExpressionModuleFactory(store, host, goodMetrics).create(() -> false, input.model(),
            "player", "main", input.profile(), false, input.definition(), null, input.settings(), input.configBytes());
        var badMetrics = new ExpressionEngineMetrics();
        var bad = new RenderTargetExpressionModuleFactory(store, host, MolangEventCatalog.production(),
            HostLinkage.production(host).without(HostSymbol.GLOBAL_O), badMetrics);

        var first = assertThrows(AssetLoadException.class, () -> bad.create(() -> false, input.model(), "player",
            "main", input.profile(), false, input.definition(), null, input.settings(), input.configBytes()));
        var second = assertThrows(AssetLoadException.class, () -> bad.create(() -> false, input.model(), "player",
            "main", input.profile(), false, input.definition(), null, input.settings(), input.configBytes()));
        assertEquals(AssetLoadException.Reason.ACCESS, first.reason());
        assertEquals(AssetLoadException.Reason.ACCESS, second.reason());
        assertTrue(first.getCause() instanceof IllegalArgumentException);
        assertTrue(second.getCause() instanceof IllegalArgumentException);
        assertFalse(second.getMessage().contains("previously failed"));
        assertEquals(0, badMetrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        try (var retained = good.retain()) {
            assertTrue(retained != null);
        }
        good.close();
    }

    @Test
    void coldMemoryAndDiskPathsBindTheSameCatalogWithoutWarmCompilation(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        var store = track(new ExpressionArtifactStore(paths, shared, 2));
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(store, metrics);
        var exact = factory.expressionBinding();
        var input = input();

        var cold = factory.create(() -> false, input.model(), "player", "main", input.profile(), exact, false,
            input.definition(), null, input.settings(), input.configBytes());
        var location = new ModelProgramInventory.SemanticLocation("player", "animation/main/idle/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
        var coldRef = cold.requireProgram(location);
        assertEquals(ExpressionArtifactStore.Source.COLD, cold.source());
        assertFalse(cold.identity().expected().bindingPlan().isIdentity());
        assertEquals(1, cold.programs().size());
        var afterCold = metrics.snapshot();
        assertEquals(1, afterCold.stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS));
        assertEquals(1, afterCold.stageCalls(EngineMetrics.Stage.SOURCE_GENERATION));
        assertEquals(1, afterCold.stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        assertEquals(1, afterCold.portableProgramDecodes());
        cold.close();
        assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.RESOURCES));

        var memory = factory.create(() -> false, input.model(), "player", "main", input.profile(), exact, false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.MEMORY, memory.source());
        assertEquals(coldRef.sourceId(), memory.requireProgram(location).sourceId());
        assertEquals(coldRef.entryId(), memory.requireProgram(location).entryId());
        assertTrue(coldRef != memory.requireProgram(location));
        var afterMemory = metrics.snapshot();
        assertEquals(afterCold.stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS),
            afterMemory.stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS));
        assertEquals(afterCold.stageCalls(EngineMetrics.Stage.SOURCE_GENERATION),
            afterMemory.stageCalls(EngineMetrics.Stage.SOURCE_GENERATION));
        assertEquals(afterCold.stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION),
            afterMemory.stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        assertEquals(afterCold.portableProgramDecodes(), afterMemory.portableProgramDecodes());
        memory.close();

        com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        var diskMetrics = new ExpressionEngineMetrics();
        var diskFactory = factory(track(new ExpressionArtifactStore(paths, shared)), diskMetrics);
        var diskExact = diskFactory.expressionBinding();
        var disk = diskFactory.create(() -> false, input.model(), "player", "main", input.profile(), diskExact, false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.DISK, disk.source());
        assertEquals(coldRef.sourceId(), disk.requireProgram(location).sourceId());
        assertEquals(coldRef.entryId(), disk.requireProgram(location).entryId());
        assertTrue(coldRef != disk.requireProgram(location));
        assertEquals(0, diskMetrics.snapshot().stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS));
        assertEquals(0, diskMetrics.snapshot().stageCalls(EngineMetrics.Stage.SOURCE_GENERATION));
        assertEquals(0, diskMetrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        assertEquals(0, diskMetrics.snapshot().portableProgramDecodes());
        assertTrue(diskMetrics.snapshot().stageCalls(EngineMetrics.Stage.MODULE_VERIFICATION) > 0);
        disk.close();
    }

    @Test
    void verifiedCandidateSurvivesPersistenceFailureBeforeWriter(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        Files.write(paths, new byte[] {1});
        var metrics = new ExpressionEngineMetrics();
        var store = track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths))));
        var factory = factory(store, metrics);
        var input = input();

        var cold = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.COLD, cold.source());
        assertEquals(1, cold.programs().size());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        cold.close();

        var memory = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.MEMORY, memory.source());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        memory.close();
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(paths));
    }

    @Test
    void verifiedCandidateSurvivesPersistenceFailureAfterWriterEntry(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        var writerEntries = new AtomicInteger();
        ExpressionArtifactStore.ArtifactMaterializer interrupted = (key, target, validator, writer, gate) -> shared
            .materialize("expression-artifact", key, target, validator, temporary -> {
                writerEntries.incrementAndGet();
                writer.write(temporary);
                throw new IOException("injected persistence interruption");
            });
        var metrics = new ExpressionEngineMetrics();
        var store = track(new ExpressionArtifactStore(paths, interrupted, 2));
        var factory = factory(store, metrics);
        var input = input();

        var cold = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.COLD, cold.source());
        store.awaitPersistence();
        assertEquals(1, writerEntries.get());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        assertFalse(Files.exists(store.path(cold.identity())));
        cold.close();

        var memory = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.MEMORY, memory.source());
        store.awaitPersistence();
        assertEquals(1, writerEntries.get());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        memory.close();
    }

    @Test
    void temporaryCorruptionAndCommitFailurePreserveOnlyTheLocalCandidate(@TempDir Path temp) throws Exception {
        for (boolean corrupt : List.of(true, false)) {
            var paths = temp.resolve(corrupt ? "corrupt-temp" : "commit-failure");
            var shared = track(new AtomicSharedCache(paths));
            ExpressionArtifactStore.ArtifactMaterializer failing = (key, target, validator, writer, gate) -> {
                shared.replaceValidated("injected", key, target, validator, candidate -> {
                    writer.write(candidate);
                    if (corrupt) {
                        byte[] bytes = candidate.readAllBytes();
                        bytes[bytes.length - 1] ^= 1;
                        candidate.position(0).writeBytes(bytes);
                        candidate.position(0);
                        assertFalse(validator.validate(candidate));
                    } else {
                        candidate.position(0);
                        assertTrue(validator.validate(candidate));
                    }
                    throw new IOException(corrupt ? "injected invalid temporary" : "injected commit failure");
                }, AtomicSharedCache.AtomicReplacement::run);
            };
            var store = track(new ExpressionArtifactStore(paths, failing, 2));
            var metrics = new ExpressionEngineMetrics();
            var factory = factory(store, metrics);
            var input = input();
            var cold = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), null, input.settings(), input.configBytes());
            assertEquals(ExpressionArtifactStore.Source.COLD, cold.source());
            assertFalse(Files.exists(store.path(cold.identity())));
            cold.close();
            var memory = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), null, input.settings(), input.configBytes());
            assertEquals(ExpressionArtifactStore.Source.MEMORY, memory.source());
            assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
            memory.close();
            var subsequentMetrics = new ExpressionEngineMetrics();
            var subsequent = factory(track(new ExpressionArtifactStore(paths, shared)), subsequentMetrics).create(
                () -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
                input.settings(), input.configBytes());
            assertEquals(ExpressionArtifactStore.Source.COLD, subsequent.source());
            assertEquals(1, subsequentMetrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
            subsequent.close();
        }
    }

    @Test
    void verifiedCandidateSurvivesTargetValidatorReadFailure(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var validatorEntries = new AtomicInteger();
        ExpressionArtifactStore.ArtifactMaterializer interrupted = (key, target, validator, writer, gate) -> {
            validatorEntries.incrementAndGet();
            try (var channel = java.nio.channels.FileChannel.open(target, java.nio.file.StandardOpenOption.READ)) {
                validator.validate(com.elfmcys.ysm.model.storage.CacheFile.borrow(channel));
            }
        };
        var metrics = new ExpressionEngineMetrics();
        var store = track(new ExpressionArtifactStore(paths, interrupted, 2));
        var factory = factory(store, metrics);
        var input = input();

        var cold = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.COLD, cold.source());
        store.awaitPersistence();
        assertEquals(1, validatorEntries.get());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        cold.close();

        var memory = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.MEMORY, memory.source());
        store.awaitPersistence();
        assertEquals(1, validatorEntries.get());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        memory.close();
    }

    @Test
    void verifiedCandidateSurvivesValidatedTargetDisappearingBeforeFinalRead(@TempDir Path temp) throws Exception {
        var input = input();
        var seedPaths = temp.resolve("seed");
        var seedStore = track(new ExpressionArtifactStore(seedPaths, track(new AtomicSharedCache(seedPaths))));
        var seed = factory(seedStore, new ExpressionEngineMetrics()).create(() -> false, input.model(), "player",
            "main", input.profile(), false, input.definition(), null, input.settings(), input.configBytes());
        seedStore.awaitPersistence();
        var envelope = Files.readAllBytes(seedStore.path(seed.identity()));
        seed.close();

        var paths = temp.resolve("cache");
        ExpressionArtifactStore.ArtifactMaterializer disappearingHit = (key, target, validator, writer, gate) -> {
            Files.createDirectories(target.getParent());
            Files.write(target, envelope);
            try (var channel = java.nio.channels.FileChannel.open(target, java.nio.file.StandardOpenOption.READ)) {
                assertTrue(validator.validate(com.elfmcys.ysm.model.storage.CacheFile.borrow(channel)));
            }
            Files.delete(target);
        };
        var metrics = new ExpressionEngineMetrics();
        var store = track(new ExpressionArtifactStore(paths, disappearingHit, 2));
        var factory = factory(store, metrics);

        var cold = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.COLD, cold.source());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        cold.close();

        var memory = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
            input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.MEMORY, memory.source());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        memory.close();
    }

    @Test
    void producerFailureCannotBeReclassifiedAsPersistenceSuccess(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var materializations = new AtomicInteger();
        ExpressionArtifactStore.ArtifactMaterializer materializer =
            (key, target, validator, writer, gate) -> materializations.incrementAndGet();
        var store = track(new ExpressionArtifactStore(paths, materializer, 2));
        var input = input();
        var host = ExpressionHost.create(HostAvailability.all());
        var lookup = lookup(input, host, MolangEventCatalog.production(), HostLinkage.production(host),
            new JaninoJavaCompilerBackend().descriptor());

        var failure = assertThrows(AssetLoadException.class, () -> store.loadOrCompile(lookup, () -> {
            throw AssetLoadException.content("injected producer failure");
        }));
        assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
        assertEquals(0, materializations.get());
    }

    @Test
    void roleSpecificEventEntriesCompileThroughTheProductionCatalog(@TempDir Path temp) throws Exception {
        var input = input();
        var strings = StringDataProto.newBuilder().addUserFunctions(eventFunction("@player_init", "v.init=1;"))
            .addUserFunctions(eventFunction("@player_update", "v.update=args[0];"))
            .addUserFunctions(eventFunction("@sync", "v.sync=args[99];"))
            .addUserFunctions(eventFunction("@defer", "v.defer=args[0];"))
            .addUserFunctions(eventFunction("@player_ctrl_main", "return 2;")).build();
        var paths = temp.resolve("cache");
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
            new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(), false,
                ModelDataProto.newBuilder().build(), strings, input.settings(), input.configBytes());

        try (module) {
            assertEquals(5, module.programs().size());
            assertEquals(MolangEventCatalog.production().fingerprint(), module.identity().eventCatalogFingerprint());
            assertEquals(ProgramRole.EVENT_PLAYER_UPDATE.shape(), module
                .requireProgram("function/@player_update", ProgramRole.EVENT_PLAYER_UPDATE, "player_update:0").shape());
            assertEquals(ProgramRole.EVENT_SYNC.shape(),
                module.requireProgram("function/@sync", ProgramRole.EVENT_SYNC, "sync:0").shape());
            assertEquals(ProgramRole.EVENT_DEFER.shape(),
                module.requireProgram("function/@defer", ProgramRole.EVENT_DEFER, "defer:0").shape());
            assertEquals(ProgramRole.EVENT_CODED_CONTROLLER.shape(), module
                .requireProgram("function/@player_ctrl_main", ProgramRole.EVENT_CODED_CONTROLLER, "player_ctrl_main:0")
                .shape());
        }
    }

    @Test
    void sharedSourcesKeepLocalAndDefaultCallableOriginsAcrossColdAndDisk(@TempDir Path temp) throws Exception {
        var input = input();
        var prefix = "fallback/" + "a".repeat(64) + "/player/main/";
        var definition = ModelDataProto.newBuilder()
            .putAnimationFiles("main", AnimationFileProto.newBuilder()
                .addAnimations(animation("idle", "fn.common()"))
                .addAnimations(animation("copy", "fn.common()")).build())
            .putAnimationFiles(prefix + "main", AnimationFileProto.newBuilder()
                .addAnimations(animation("idle", "fn.common()")).build()).build();
        var strings = StringDataProto.newBuilder().addUserFunctions(eventFunction("common", "return 41;"))
            .addUserFunctions(eventFunction(prefix + "common", "return 11;")).build();
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        for (var expectedSource : List.of(ExpressionArtifactStore.Source.COLD, ExpressionArtifactStore.Source.DISK)) {
            var store = track(new ExpressionArtifactStore(paths, shared, 1));
            try (var module = factory(store, new ExpressionEngineMetrics()).create(() -> false,
                input.model(), "player", "main", input.profile(), false, definition, strings,
                input.settings(), input.configBytes()); var runtime = new EntityExpressionRuntime()) {
                assertEquals(expectedSource, module.source());
                var local = new ModelProgramInventory.SemanticLocation("player", "animation/main/idle/blend-weight",
                    ProgramRole.ANIMATION_SAMPLE, "");
                var copy = new ModelProgramInventory.SemanticLocation("player", "animation/main/copy/blend-weight",
                    ProgramRole.ANIMATION_SAMPLE, "");
                var fallback = new ModelProgramInventory.SemanticLocation("player",
                    "animation/" + prefix + "main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, "");
                assertNotEquals(module.requireProgram(local).sourceId(), module.requireProgram(copy).sourceId());
                assertEquals(module.requireProgram(local).entryId(), module.requireProgram(copy).entryId());
                assertNotEquals(module.requireProgram(local).entryId(), module.requireProgram(fallback).entryId());
                TestExpressionModules.replace(runtime, module, Map.of());
                float localValue = runtime.observe(inputs(), evaluation -> evaluation.evaluate(module.requireFloatProgram(local)));
                float copiedValue = runtime.observe(inputs(), evaluation -> evaluation.evaluate(module.requireFloatProgram(copy)));
                float fallbackValue = runtime.observe(inputs(), evaluation -> evaluation.evaluate(module.requireFloatProgram(fallback)));
                assertEquals(41f, localValue);
                assertEquals(41f, copiedValue);
                assertEquals(11f, fallbackValue);
                store.awaitPersistence();
            }
            com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        }
    }

    @Test
    void transientUsesCurrentRootAndSharedFunctionsUnderBothActionCeilings(@TempDir Path temp) throws Exception {
        var input = input("fn.reader()");
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(eventFunction("@player_init", "fn.writer(); return fn.reader()+1;"))
            .addUserFunctions(eventFunction("reader", "return v.sample;"))
            .addUserFunctions(eventFunction("writer", "v.sample=7;return 0;")).build();
        var paths = temp.resolve("cache");
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
            new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), strings, input.settings(), input.configBytes());
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, module, Map.of());
        var baseline = TransientExpressionSession.liveLeaseCount();

        try (var write = runtime.compileTransient(runtime.generation(), "v.sample=7;",
            TransientExpressionSession.Privilege.LOCAL_EXECUTE, TransientExpressionSession.Purpose.ONE_SHOT,
            "test:write", () -> false)) {
            runtime.executeTransient(inputs(), write);
        }
        try (
            var observation = runtime.compileTransient(runtime.generation(), "return v.sample;",
                TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                "test:current-root", () -> false);
            var shared = runtime.compileTransient(runtime.generation(), "return fn.reader();",
                TransientExpressionSession.Privilege.LOCAL_DEBUG, TransientExpressionSession.Purpose.ONE_SHOT,
                "test:shared-read", () -> false)) {
            assertEquals(7f, runtime.executeTransient(inputs(), observation));
            assertEquals(7f, runtime.executeTransient(inputs(), shared));
            try (var write = runtime.compileTransient(runtime.generation(), "v.sample=9;",
                TransientExpressionSession.Privilege.LOCAL_EXECUTE, TransientExpressionSession.Purpose.ONE_SHOT,
                "test:next-write", () -> false)) {
                runtime.executeTransient(inputs(), write);
            }
            assertEquals(9f, runtime.executeTransient(inputs(), observation));
            assertEquals(9f, runtime.executeTransient(inputs(), shared));
            try (
                var debugWrite = runtime.compileTransient(runtime.generation(), "v.sample=1;return v.sample;",
                    TransientExpressionSession.Privilege.LOCAL_DEBUG, TransientExpressionSession.Purpose.ONE_SHOT,
                    "test:debug-write", () -> false);
                var indirect = runtime.compileTransient(runtime.generation(), "fn.writer();return v.sample;",
                    TransientExpressionSession.Privilege.LOCAL_DEBUG, TransientExpressionSession.Purpose.ONE_SHOT,
                    "test:indirect-write", () -> false)) {
                assertEquals(1f, runtime.executeTransient(inputs(), debugWrite));
                assertEquals(7f, runtime.executeTransient(inputs(), indirect));
            }
        }
        for (String discarded : List.of("missing.call(v.sample=1); return v.sample;",
            "math.abs(v.sample=1,2); return v.sample;", "ysm.sync(v.sample=1); return v.sample;")) {
            try (var watch = runtime.compileTransient(runtime.generation(), discarded,
                TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                "test:discarded-write", () -> false)) {
                assertEquals(7f, runtime.executeTransient(inputs(), watch));
            }
        }
        try (var watch = runtime.compileTransient(runtime.generation(), "v.sample ? fn.writer() : 0;return v.sample;",
            TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
            "test:conditional-write", () -> false)) {
            assertEquals(7f, runtime.executeTransient(inputs(), watch));
        }
        assertEquals(baseline, TransientExpressionSession.liveLeaseCount());
        runtime.close();
        module.close();
    }

    @Test
    void transientCancellationAndGenerationMismatchReleaseEveryLease(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        var input = input();
        var module = factory(track(new ExpressionArtifactStore(paths, shared)), new ExpressionEngineMetrics()).create(
            () -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
            input.settings(), input.configBytes());
        var replacement = factory(track(new ExpressionArtifactStore(paths, shared)), new ExpressionEngineMetrics())
            .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
                input.settings(), input.configBytes());
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, module, Map.of());
        var baseline = TransientExpressionSession.liveLeaseCount();

        assertThrows(java.util.concurrent.CancellationException.class,
            () -> runtime.compileTransient(runtime.generation(), "1", TransientExpressionSession.Privilege.LOCAL_DEBUG,
                TransientExpressionSession.Purpose.ONE_SHOT, "test:cancelled", () -> true));
        var stale =
            runtime.compileTransient(runtime.generation(), "1", TransientExpressionSession.Privilege.LOCAL_DEBUG,
                TransientExpressionSession.Purpose.ONE_SHOT, "test:stale", () -> false);
        TestExpressionModules.replace(runtime, replacement, Map.of());
        assertThrows(IllegalStateException.class, () -> runtime.executeTransient(inputs(), stale));
        stale.close();

        assertEquals(baseline, TransientExpressionSession.liveLeaseCount());
        runtime.close();
        module.close();
        replacement.close();
    }

    @Test
    void preparedTransientAccessIsReusedAndExplicitCloseReleasesBothHolds(@TempDir Path temp) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var input = input("1");
        int baseline = TransientExpressionSession.liveLeaseCount();
        try (var module = factory(track(new ExpressionArtifactStore(temp, track(new AtomicSharedCache(temp)))), metrics)
            .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
                input.settings(), input.configBytes());
            var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            long installationHolds = metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES);
            var session = runtime.compileTransient(runtime.generation(), "return 3;",
                TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                "test:prepared-watch", () -> false);
            try {
                var field = TransientExpressionSession.class.getDeclaredField("access");
                field.setAccessible(true);
                var access = field.get(session);
                long preparedHolds = metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES);
                assertEquals(1, metrics.snapshot().active(EngineMetrics.Owner.TRANSIENT_ACCESS_LEASES));
                for (int round = 0; round < 3; round++) {
                    runtime.observe(inputs(), evaluation -> {
                        for (int expression = 0; expression < 16; expression++) {
                            assertEquals(3f, evaluation.executeTransient(session));
                            org.junit.jupiter.api.Assertions.assertSame(access, field.get(session));
                        }
                        return null;
                    });
                    assertEquals(preparedHolds, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
                    assertEquals(1, metrics.snapshot().active(EngineMetrics.Owner.TRANSIENT_ACCESS_LEASES));
                }
                runtime.invalidate();
                assertThrows(IllegalStateException.class, () -> runtime.executeTransient(inputs(), session));
            } finally {
                session.close();
                session.close();
            }
            assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.TRANSIENT_ACCESS_LEASES));
            assertEquals(installationHolds, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
            assertEquals(baseline, TransientExpressionSession.liveLeaseCount());
        }
        assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
    }

    @Test
    void transientPreparationRejectsRetiredStateAndReleasesTheCompileHold(@TempDir Path temp) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var input = input("1");
        int baseline = TransientExpressionSession.liveLeaseCount();
        var factory = factory(track(new ExpressionArtifactStore(temp, track(new AtomicSharedCache(temp)))), metrics);
        try (
            var module = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), null, input.settings(), input.configBytes());
            var replacement = factory.create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), null, input.settings(), input.configBytes());
            var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            long holds = metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES);
            var replaced = new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(IllegalStateException.class,
                () -> runtime.compileTransient(runtime.generation(), "return 1;",
                    TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                    "test:retired-during-compile", () -> {
                        if (replaced.compareAndSet(false, true)) {
                            assertEquals(holds + 1, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
                            TestExpressionModules.replace(runtime, replacement, Map.of());
                        }
                        return false;
                    }));
            assertTrue(replaced.get());
            assertEquals(holds, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
            assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.TRANSIENT_ACCESS_LEASES));
            assertEquals(baseline, TransientExpressionSession.liveLeaseCount());
            try (var fresh = runtime.compileTransient(runtime.generation(), "return 2;",
                TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                "test:fresh-after-retire", () -> false)) {
                assertEquals(2f, runtime.executeTransient(inputs(), fresh));
            }
        }
        assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
    }

    @Test
    void transientPreparationFailuresAndActiveRoundCannotLeakQuotaOrAccess(@TempDir Path temp) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var input = input("1");
        int baseline = TransientExpressionSession.liveLeaseCount();
        try (var module = factory(track(new ExpressionArtifactStore(temp, track(new AtomicSharedCache(temp)))), metrics)
            .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
                input.settings(), input.configBytes());
            var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            long holds = metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES);
            assertThrows(java.util.concurrent.CancellationException.class,
                () -> runtime.compileTransient(runtime.generation(), "1",
                    TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                    "test:cancel-prepare", () -> true));
            assertThrows(com.elfmcys.ysm.expression.frontend.MolangFrontendException.class,
                () -> runtime.compileTransient(runtime.generation(), "(",
                    TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                    "test:syntax-prepare", () -> false));
            assertThrows(IllegalStateException.class, () -> runtime.observe(inputs(), evaluation -> {
                runtime.compileTransient(runtime.generation(), "1", TransientExpressionSession.Privilege.LOCAL_WATCH,
                    TransientExpressionSession.Purpose.WATCH, "test:active-prepare", () -> false);
                return null;
            }));
            assertEquals(holds, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
            assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.TRANSIENT_ACCESS_LEASES));
            assertEquals(baseline, TransientExpressionSession.liveLeaseCount());
        }
        assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
    }

    @Test
    void productionTransientQueueAndWatchReadOneCurrentOwner(@TempDir Path temp) throws Exception {
        for (boolean warm : List.of(false, true))
            productionTransientQueueAndWatch(temp.resolve(Boolean.toString(warm)), warm);
    }

    @Test
    void originalWeddingControllerWithoutOptionalProviderKeepsAuthoredBranches(@TempDir Path temp) throws Exception {
        weddingControllerWithoutOptionalProvider(temp, List.of(false, true));
    }

    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        new ExpressionArtifactPipelineTest().weddingControllerWithoutOptionalProvider(Path.of(args[0]),
            List.of(args[1].equals("DISK")));
    }

    private void weddingControllerWithoutOptionalProvider(Path temp, List<Boolean> modes) throws Exception {
        var initializer = Class.forName("com.elfmcys.ysm.expression.host.ComponentScopeTest")
            .getDeclaredMethod("initializeMinecraftEntityClasses");
        initializer.setAccessible(true);
        initializer.invoke(null);
        var unsafe = com.elfmcys.ysm.util.UnsafeUtil.getUnsafe();
        var subject = (SwordSubject)unsafe.allocateInstance(SwordSubject.class);
        var position = net.minecraft.world.entity.Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        position.set(subject, net.minecraft.world.phys.Vec3.ZERO);
        var item = new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties());
        var holder = item.builtInRegistryHolder();
        var bindTags = holder.getClass().getDeclaredMethod("bindTags", java.util.Collection.class);
        bindTags.setAccessible(true);
        bindTags.invoke(holder, List.of(net.minecraft.tags.ItemTags.SWORDS));
        net.minecraftforge.registries.ForgeRegistries.ITEMS
            .register(new net.minecraft.resources.ResourceLocation("test", "wedding_sword"), item);
        subject.sword = new net.minecraft.world.item.ItemStack(item);
        var source = Files.readString(Path.of(
            "src/main/resources/assets/ysm/builtin/wine_fox/18_wedding/functions/简单万用BC动画控制器@player_ctrl_swing.molang"));
        var input = input();
        var strings =
            StringDataProto.newBuilder().addUserFunctions(eventFunction("@player_ctrl_swing", source)).build();
        var host = ExpressionHost.create(HostAvailability.none());
        var paths = temp.resolve("cache");
        for (boolean warm : modes) {
            var metrics = new ExpressionEngineMetrics();
            var module = new RenderTargetExpressionModuleFactory(
                track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))), host, metrics)
                .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(),
                    strings, input.settings(), input.configBytes());
            assertEquals(warm ? ExpressionArtifactStore.Source.DISK : ExpressionArtifactStore.Source.COLD,
                module.source());
            assertTrue(module.compilationReport().replacements().isEmpty());
            if (warm) {
                for (var stage : List.of(EngineMetrics.Stage.SEMANTIC_ANALYSIS, EngineMetrics.Stage.SOURCE_GENERATION,
                    EngineMetrics.Stage.BACKEND_COMPILATION)) {
                    assertEquals(0, metrics.snapshot().stageCalls(stage));
                }
                assertEquals(0, metrics.snapshot().portableProgramDecodes());
            }
            var runtime = new EntityExpressionRuntime();
            var components = BuiltinDeclarations
                .createComponents(com.elfmcys.ysm.expression.host.TestComponentHolders.owner(subject), host);
            runtime.replace(module, Map.of(), components);
            var controller = new com.elfmcys.ysm.expression.host.ControllerExecutionState(false);
            var effects = new java.util.ArrayList<EffectCommand>();
            var inputs = new MinecraftRuntimeInputs<Entity>() {
                public net.minecraft.world.entity.Entity entity() {
                    return subject;
                }

                public com.elfmcys.ysm.client.entity.AnimatableEntity<?> animatable() {
                    return null;
                }

                public com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<?> animationEvent() {
                    return null;
                }

                public net.minecraft.util.RandomSource randomSource() {
                    return null;
                }

                public com.elfmcys.ysm.expression.host.ControllerExecutionState controllerState() {
                    return controller;
                }

                public com.elfmcys.ysm.expression.host.AnimationExecutionState animationState() {
                    return null;
                }

                public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {}
            };
            var invocation = new EntityExpressionRuntime.InvocationInputs(inputs,
                (commands, provenance) -> () -> effects.addAll(commands), () -> false);
            var location = new ModelProgramInventory.SemanticLocation("player", "function/@player_ctrl_swing",
                ProgramRole.EVENT_CODED_CONTROLLER, "player_ctrl_swing:0");
            var program = module.requireFloatProgram(location);
            try {
                for (int stage : List.of(2, 3, 1)) {
                    try (var session = runtime.compileTransient(runtime.generation(), "v.attackStage=" + stage,
                        TransientExpressionSession.Privilege.LOCAL_DEBUG, TransientExpressionSession.Purpose.WATCH,
                        "wedding-stage", () -> false)) {
                        runtime.executeTransient(invocation, session);
                    }
                    effects.clear();
                    subject.swinging = true;
                    float result = runtime.update(invocation, evaluation -> evaluation.evaluate(program));
                    assertEquals(2, result);
                    String animation = stage == 2 ? "Attackdown4" : stage == 3 ? "Attackdown5" : "Attackdown3";
                    String sound = stage == 2 ? "swingswordtwo" : stage == 3 ? "swingswordspin" : "swingsword";
                    assertEquals(3, effects.size());
                    assertEquals(
                        new EffectCommand.Controller(HostSymbol.CTRL_SET_BEGINNING_TRANSITION_LENGTH, "", .05f, 0, 0),
                        effects.get(0));
                    assertEquals(new EffectCommand.Controller(HostSymbol.CTRL_SET_ANIMATION, animation, 0, 0, 0),
                        effects.get(1));
                    var actualSound = (EffectCommand.Sound)effects.get(2);
                    assertEquals(ModelSoundId.string(sound), actualSound.soundId());
                    assertEquals(sound, actualSound.name());
                    assertEquals(0, actualSound.mode());
                    assertEquals(1, actualSound.volume());
                    assertEquals(1, actualSound.pitch());
                    assertFalse(actualSound.global());
                    System.out.println("WEDDING_ORACLE " + module.source() + " stage=" + stage + " result=" + result
                        + " effects=" + effects);
                }
                subject.swinging = false;
                for (boolean finished : List.of(false, true)) {
                    controller.setAllAnimationsFinished(finished);
                    effects.clear();
                    float result = runtime.update(invocation, evaluation -> evaluation.evaluate(program));
                    assertEquals(0, result);
                    assertEquals(finished ? 1 : 0, effects.size());
                    if (finished)
                        assertEquals(HostSymbol.CTRL_INDICATE_RELOAD,
                            ((EffectCommand.Controller)effects.get(0)).action());
                    System.out.println("WEDDING_ORACLE " + module.source() + " swing=false finished=" + finished
                        + " effects=" + effects);
                }
            } finally {
                runtime.close();
                module.close();
                components.invalidate();
            }
            com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        }
    }

    private static final class SwordSubject extends net.minecraft.world.entity.decoration.ArmorStand {
        private net.minecraft.world.item.ItemStack sword;

        private SwordSubject() {
            super(net.minecraft.world.entity.EntityType.ARMOR_STAND, null);
        }

        @Override
        public boolean isSleeping() {
            return false;
        }

        @Override
        public net.minecraft.world.item.ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) {
            return sword;
        }
    }

    private void productionTransientQueueAndWatch(Path temp, boolean warm) throws Exception {
        var initializer = Class.forName("com.elfmcys.ysm.expression.host.ComponentScopeTest")
            .getDeclaredMethod("initializeMinecraftEntityClasses");
        initializer.setAccessible(true);
        initializer.invoke(null);
        var subject = (net.minecraft.world.entity.Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
            .allocateInstance(net.minecraft.world.entity.decoration.ArmorStand.class);
        var input = input("fn.reader()+fn.actor()");
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(eventFunction("@player_init", "fn.writer(); return fn.reader()+fn.actor();"))
            .addUserFunctions(eventFunction("reader", "return v.sample;"))
            .addUserFunctions(eventFunction("writer", "v.sample=1;return 0;"))
            .addUserFunctions(eventFunction("actor", "ysm.sync(9);return 5;")).build();
        var paths = temp.resolve("cache");
        if (warm)
            factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
                new ExpressionEngineMetrics())
                .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(),
                    strings, input.settings(), input.configBytes())
                .close();
        com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
            new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), strings, input.settings(), input.configBytes());
        assertEquals(warm ? ExpressionArtifactStore.Source.DISK : ExpressionArtifactStore.Source.COLD, module.source());
        var runtime = new EntityExpressionRuntime();
        runtime.replace(module, Map.of(),
            module.prepareComponents(com.elfmcys.ysm.expression.host.TestComponentHolders.owner(subject), null));
        var model = (QueueOwner)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(QueueOwner.class);
        var processor = new com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor<>(model);
        var bindingType = Class.forName("com.elfmcys.ysm.client.entity.EntityModelBinding");
        var bindingConstructor = bindingType.getDeclaredConstructor();
        bindingConstructor.setAccessible(true);
        var binding = bindingConstructor.newInstance();
        var assignModel = bindingType.getDeclaredMethod("updateModelHash", com.elfmcys.ysm.model.domain.Hash256.class);
        assignModel.setAccessible(true);
        assignModel.invoke(binding, new Object[] {null});
        var holder = QueueOwner.queueHolder(new com.elfmcys.ysm.model.resource.client.ResourceLease() {
            @Override
            public com.elfmcys.ysm.model.resource.client.ModelResourcePurpose purpose() {
                return com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.FULL;
            }

            private final com.elfmcys.ysm.model.resource.client.ModelRenderTarget target =
                (com.elfmcys.ysm.model.resource.client.ModelRenderTarget)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
                    .allocateInstance(com.elfmcys.ysm.model.resource.client.ModelRenderTarget.class);

            public com.elfmcys.ysm.model.resource.client.AcquireResult poll() {
                return new com.elfmcys.ysm.model.resource.client.AcquireResult.Ready(target);
            }

            public boolean isCurrent(com.elfmcys.ysm.model.resource.client.ResourceRequest request) {
                return true;
            }

            public void close() {}

            public void cancelPending() {}
        });
        var holderProcessor = holder.getClass().getDeclaredField("processor");
        holderProcessor.setAccessible(true);
        holderProcessor.set(holder, processor);
        var current = bindingType.getDeclaredField("resourceHolder");
        current.setAccessible(true);
        current.set(binding, holder);
        for (var field : Map.of("expressionRuntime", runtime, "entity", subject, "modelBinding", binding).entrySet()) {
            var member = com.elfmcys.ysm.client.entity.AnimatableEntity.class.getDeclaredField(field.getKey());
            member.setAccessible(true);
            member.set(model, field.getValue());
        }
        var lifecycleType = Class.forName("com.elfmcys.ysm.client.entity.EntityExecutionLifecycle");
        var lifecycleConstructor = lifecycleType.getDeclaredConstructor(EntityExpressionRuntime.class, Runnable.class,
            java.util.function.Consumer.class);
        lifecycleConstructor.setAccessible(true);
        var lifecycle = lifecycleConstructor.newInstance(runtime, (Runnable)() -> {
        }, (java.util.function.Consumer<Throwable>)failure -> {
            throw new AssertionError(failure);
        });
        var lifecycleField = com.elfmcys.ysm.client.entity.AnimatableEntity.class.getDeclaredField("lifecycle");
        lifecycleField.setAccessible(true);
        lifecycleField.set(model, lifecycle);
        var effects = new java.util.ArrayList<EffectCommand>();
        var productionInputs = new InvocationInputs<Entity>() {
            public net.minecraft.world.entity.Entity entity() {
                return subject;
            }

            public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {}
        };
        var invocation = new EntityExpressionRuntime.InvocationInputs(productionInputs,
            (commands, provenance) -> () -> effects.addAll(commands), () -> false);
        var watches = new com.elfmcys.ysm.geckolib3.core.processor.DebugInfo();
        processor.setDebugInfo(watches);
        try {
            assertEquals("UNSET", queued(model, processor, invocation, "return q.position(3);",
                TransientExpressionSession.Privilege.LOCAL_DEBUG));
            assertEquals("0.0",
                queued(model, processor, invocation, "return 0;", TransientExpressionSession.Privilege.LOCAL_DEBUG));
            assertEquals("false", queued(model, processor, invocation, "return false;",
                TransientExpressionSession.Privilege.LOCAL_DEBUG));
            assertEquals("7.0", queued(model, processor, invocation, "v.sample=7;return v.sample;",
                TransientExpressionSession.Privilege.LOCAL_EXECUTE));
            watches.add(com.elfmcys.ysm.geckolib3.core.processor.DebugInfo.Phase.PRE_ANIMATION, "current",
                "return fn.reader();");
            watches.add(com.elfmcys.ysm.geckolib3.core.processor.DebugInfo.Phase.PRE_ANIMATION, "direct",
                "return v.sample;");
            awaitWatch(model, processor, invocation, watches, "7.0");
            assertEquals("9.0", queued(model, processor, invocation, "v.sample=9;return v.sample;",
                TransientExpressionSession.Privilege.LOCAL_EXECUTE));
            awaitWatch(model, processor, invocation, watches, "9.0");
            assertEquals("1.0", queued(model, processor, invocation, "fn.writer();return v.sample;",
                TransientExpressionSession.Privilege.LOCAL_DEBUG));
            awaitWatch(model, processor, invocation, watches, "1.0");
            assertEquals("6.0", queued(model, processor, invocation, "return fn.actor()+1;",
                TransientExpressionSession.Privilege.LOCAL_DEBUG));
            assertTrue(effects.isEmpty());
            assertEquals("6.0", queued(model, processor, invocation, "return fn.actor()+1;",
                TransientExpressionSession.Privilege.LOCAL_EXECUTE));
            assertEquals(1, effects.size());
        } finally {
            ((AutoCloseable)binding).close();
            runtime.close();
            module.close();
        }
    }

    private static String queued(QueueOwner model,
        com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor<?> processor,
        EntityExpressionRuntime.InvocationInputs inputs, String source, TransientExpressionSession.Privilege privilege)
        throws Exception {
        var result = new java.util.concurrent.CompletableFuture<String>();
        model.executeTransient(source, privilege, true, result::complete);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!result.isDone() && System.nanoTime() < deadline) {
            model.getExpressionRuntime().animationPass(inputs, false, evaluation -> {
                processor.processTransientTasks(evaluation, true);
                return null;
            }, delta -> {
            });
            Thread.sleep(10);
        }
        return result.get(1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void awaitWatch(QueueOwner model,
        com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor<?> processor,
        EntityExpressionRuntime.InvocationInputs inputs, com.elfmcys.ysm.geckolib3.core.processor.DebugInfo watches,
        String expected) throws Exception {
        var values = new java.util.HashMap<String, String>();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        do {
            model.getExpressionRuntime().animationPass(inputs, false, evaluation -> {
                processor.processTransientTasks(evaluation, true);
                return null;
            }, delta -> {
            });
            watches.enumerate(values::put);
            if (expected.equals(values.get("current")) && expected.equals(values.get("direct")))
                return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, values.get("current"));
        assertEquals(expected, values.get("direct"));
    }

    private static final class QueueOwner
        extends com.elfmcys.ysm.client.entity.AnimatableEntity<net.minecraft.world.entity.Entity> {
        private QueueOwner() {
            super(null);
        }

        public net.minecraft.resources.ResourceLocation getTextureLocation() {
            return null;
        }

        static Object queueHolder(com.elfmcys.ysm.model.resource.client.ResourceLease lease) throws Exception {
            var type = Class.forName("com.elfmcys.ysm.client.entity.AnimatableEntity$ResourceHolder");
            var constructor = type.getDeclaredConstructor(com.elfmcys.ysm.model.resource.client.ResourceLease.class);
            constructor.setAccessible(true);
            return constructor.newInstance(lease);
        }

        public float getWidthScale() {
            return 1;
        }

        public float getHeightScale() {
            return 1;
        }

        public com.elfmcys.ysm.geckolib3.core.builder.Animation getAnimation(String name) {
            return null;
        }

        protected void prepareAnimationControllers(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {}

        protected com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel
            resolveGeoModel(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target, String texture) {
            throw new AssertionError("No asset supplied");
        }
    }

    @Test
    void corruptEnvelopeIsRebuiltWithoutQuarantine(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        var firstStore = track(new ExpressionArtifactStore(paths, shared));
        var input = input();
        var first = factory(firstStore, new ExpressionEngineMetrics()).create(() -> false, input.model(), "player",
            "main", input.profile(), false, input.definition(), null, input.settings(), input.configBytes());
        var identity = first.identity();
        first.close();
        var artifactPath = firstStore.path(identity);
        firstStore.awaitPersistence();
        var tampered = Files.readAllBytes(artifactPath);
        tampered[0] ^= 0x7f;
        Files.write(artifactPath, tampered);

        var metrics = new ExpressionEngineMetrics();
        var rebuilt = factory(track(new ExpressionArtifactStore(paths, shared)), metrics).create(() -> false,
            input.model(), "player", "main", input.profile(), false, input.definition(), null, input.settings(),
            input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.COLD, rebuilt.source());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        rebuilt.close();

        com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        try (var files = Files.list(artifactPath.getParent())) {
            assertEquals(0,
                files.filter(path -> path.getFileName().toString().startsWith(artifactPath.getFileName() + ".corrupt-"))
                    .count());
        }
        assertTrue(artifactPath.normalize().startsWith(paths));
        assertFalse(artifactPath.toString().contains(".."));
    }

    @Test
    void unknownContainerHeaderIsRejectedBeforeArtifactDecode(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        var store = track(new ExpressionArtifactStore(paths, shared));
        var input = input();
        var first = factory(store, new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main",
            input.profile(), false, input.definition(), null, input.settings(), input.configBytes());
        var artifactPath = store.path(first.identity());
        first.close();
        store.awaitPersistence();
        var bytes = Files.readAllBytes(artifactPath);
        ByteBuffer.wrap(bytes).putInt(8, 99);
        Files.write(artifactPath, bytes);

        var metrics = new ExpressionEngineMetrics();
        var rebuilt = factory(track(new ExpressionArtifactStore(paths, shared)), metrics).create(() -> false,
            input.model(), "player", "main", input.profile(), false, input.definition(), null, input.settings(),
            input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.COLD, rebuilt.source());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        rebuilt.close();
    }

    @Test
    void payloadDamageWithoutRecomputedHashIsRejectedBeforeTrustedRestoration(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        var store = track(new ExpressionArtifactStore(paths, shared));
        var input = input();
        var first = factory(store, new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main",
            input.profile(), false, input.definition(), null, input.settings(), input.configBytes());
        var artifactPath = store.path(first.identity());
        first.close();
        store.awaitPersistence();
        var envelope = Files.readAllBytes(artifactPath);
        try (var channel = java.nio.channels.FileChannel.open(artifactPath)) {
            var chunk = com.elfmcys.ysm.format.container.AssetContainerReader.read(channel)
                .getChunkInfo(com.elfmcys.ysm.format.schema.baked.asset.BakedAssetConstant.EXPRESSION_CHUNK_NAME);
            envelope[chunk.offset() + chunk.size() / 2] ^= 0x01;
        }
        Files.write(artifactPath, envelope);

        var metrics = new ExpressionEngineMetrics();
        var rebuilt = factory(track(new ExpressionArtifactStore(paths, shared)), metrics).create(() -> false,
            input.model(), "player", "main", input.profile(), false, input.definition(), null, input.settings(),
            input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.COLD, rebuilt.source());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        rebuilt.close();
    }

    @Test
    void staleExactIdentityCannotPopulateAnotherLookup(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        var store = track(new ExpressionArtifactStore(paths, shared));
        var input = input();
        var player = factory(store, new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main",
            input.profile(), false, input.definition(), null, input.settings(), input.configBytes());
        store.awaitPersistence();
        var playerEnvelope = Files.readAllBytes(store.path(player.identity()));
        player.close();
        var npc = factory(store, new ExpressionEngineMetrics()).create(() -> false, input.model(), "npc", "main",
            input.profile(), false, input.definition(), null, input.settings(), input.configBytes());
        var npcPath = store.path(npc.identity());
        npc.close();
        store.awaitPersistence();
        Files.write(npcPath, playerEnvelope);

        var metrics = new ExpressionEngineMetrics();
        var rebuilt =
            factory(track(new ExpressionArtifactStore(paths, shared)), metrics).create(() -> false, input.model(),
                "npc", "main", input.profile(), false, input.definition(), null, input.settings(), input.configBytes());
        assertEquals(ExpressionArtifactStore.Source.COLD, rebuilt.source());
        assertEquals("npc", rebuilt.identity().targetId());
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        rebuilt.close();
    }

    @Test
    void targetCloseSealsNewRetainsWhileOwnedLeaseCanFinish(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var metrics = new ExpressionEngineMetrics();
        var input = input();
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))), metrics)
            .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
                input.settings(), input.configBytes());
        var lease = module.retain();
        module.close();
        assertThrows(IllegalStateException.class, module::retain);
        assertThrows(IllegalStateException.class,
            () -> module.newInstance((dev.semaweave.RuntimeLinkage<MolangContext>)null));
        assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.RESOURCES));
        assertEquals(1, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
        lease.close();
        assertEquals(0, metrics.snapshot().active(EngineMetrics.Owner.RESOURCE_LEASES));
    }

    @Test
    void missingOrWrongShapeSemanticBindingIsRejected(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var input = input();
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
            new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), null, input.settings(), input.configBytes());
        try (module) {
            assertThrows(IllegalArgumentException.class,
                () -> module.requireProgram("animation/main/missing/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""));
            assertThrows(IllegalArgumentException.class,
                () -> module.requireProgram("animation/main/idle/blend-weight", ProgramRole.ANIMATION_INSTRUCTION, ""));
        }
    }

    @Test
    void configLabelRoutesBindAndExecuteInDeclaredOrder(@TempDir Path temp) throws Exception {
        var frontend = new MolangFrontend();
        var form = com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto.newBuilder().setType("radio").setTitle("")
            .setDescription("").setStep(1).setMin(0).setMax(1)
            .setReadProgram(EncodedProgramMapper.toProto(frontend.compile("v.mode",
                MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("test/config/read")).program()))
            .setWriteProgram(
                EncodedProgramMapper.toProto(frontend.compile("v.mode=t.value", MolangFrontend.SourceMode.CONFIG_WRITE,
                    new MolangFrontend.SourceLocation("test/config/write")).program()));
        var names = List.of("z-default", "a-alternate");
        for (int index = 0; index < names.size(); index++) {
            form.addLabels(
                com.elfmcys.ysm.proto.mixel.manifest.info.ConfigLabelProto.newBuilder().setName(names.get(index))
                    .setActionProgram(EncodedProgramMapper
                        .toProto(frontend.compile("v.mode=" + index, MolangFrontend.SourceMode.INSTRUCTION,
                            new MolangFrontend.SourceLocation("test/config/label/" + index)).program()))
                    .build());
        }
        var settings =
            input().settings().toBuilder()
                .addExtraAnimationButtons(com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButtonProto
                    .newBuilder().setId("expression").setName("").setSound("").addConfigForms(form.build()).build())
                .build();
        var input = input();
        var paths = temp.resolve("cache");
        try (
            var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
                new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(),
                    false, input.definition(), null, settings, ProtoUtil.serializeToArray(settings));
            var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            var route = new com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute("expression", 0);
            assertEquals(0f, runtime.readConfigFloat(inputs(), runtime.generation(), route));
            for (int index : List.of(1, 0, 1)) {
                module.requireProgram(route.labelLocation(), ProgramRole.CONFIG_LABEL_ACTION,
                    route.labelDiscriminator(names.get(index), index));
                runtime.executeConfigLabel(inputs(), runtime.generation(), route, names.get(index), index);
                assertEquals((float)index, runtime.readConfigFloat(inputs(), runtime.generation(), route));
            }
        }
    }

    @Test
    void unsetEqualityRestoresInitialModelVisibilityAndWatchSemantics(@TempDir Path temp) throws Exception {
        var input = input("(v.hh==0)+(v.hhh==0)+(v.yj==0)+(r.b==0)+(null==0)+(''==null)+(null=='')+(null==null)");
        var paths = temp.resolve("cache");
        try (
            var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
                new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(),
                    false, input.definition(), null, input.settings(), input.configBytes());
            var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of(), RoamingRole.PRODUCER);
            var location = new ModelProgramInventory.SemanticLocation("player", "animation/main/idle/blend-weight",
                ProgramRole.ANIMATION_SAMPLE, "");
            float initial =
                runtime.update(inputs(), evaluation -> evaluation.evaluate(module.requireFloatProgram(location)));
            assertEquals(8f, initial);
            for (var source : List.of("v.hh==0", "v.hh==null", "v.hh==''", "''==v.hh", "v.hh!=1", "null==''",
                "''==null", "null==0")) {
                try (var watch = runtime.compileTransient(runtime.generation(), source,
                    TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                    "test:unset-equality", () -> false)) {
                    assertEquals(true, runtime.executeTransient(inputs(), watch), source);
                }
            }
            for (var source : List.of("v.hh??99", "r.b??99")) {
                try (var watch = runtime.compileTransient(runtime.generation(), source,
                    TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                    "test:unset-coalesce", () -> false)) {
                    assertEquals(99f, runtime.executeTransient(inputs(), watch), source);
                }
            }
            var presentDefaults = Map.<String, Object>of("0??99", 0f, "false??true", false, "''??'fallback'", "");
            for (var entry : presentDefaults.entrySet()) {
                try (var watch = runtime.compileTransient(runtime.generation(), entry.getKey(),
                    TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                    "test:present-coalesce", () -> false)) {
                    assertEquals(entry.getValue(), runtime.executeTransient(inputs(), watch), entry.getKey());
                }
            }
        }
    }

    @Test
    void legacyMolangDivisionByZeroRemainsZero(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var input = input("1 / 0");
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
            new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(), false,
                input.definition(), null, input.settings(), input.configBytes());
        var location = new ModelProgramInventory.SemanticLocation("player", "animation/main/idle/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, module, Map.of());

        try {
            float actual =
                runtime.update(inputs(), evaluation -> evaluation.evaluate(module.requireFloatProgram(location)));
            assertEquals(0.0f, actual);
        } finally {
            runtime.close();
            module.close();
        }
    }

    @Test
    void generatedRoamingStateCrossesTheHostFullAndDeltaBoundary(@TempDir Path temp) throws Exception {
        var input = input();
        var strings =
            StringDataProto.newBuilder().addUserFunctions(eventFunction("@player_update", "r.a=5;r.b=0;")).build();
        var paths = temp.resolve("cache");
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
            new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(), false,
                ModelDataProto.newBuilder().build(), strings, input.settings(), input.configBytes());
        var location = new ModelProgramInventory.SemanticLocation("player", "function/@player_update",
            ProgramRole.EVENT_PLAYER_UPDATE, "player_update:0");
        var handlers = Map.of("player_update", List.of(module.requireEventProgram(location)));
        var producer = new EntityExpressionRuntime();
        var consumer = new EntityExpressionRuntime();

        try {
            TestExpressionModules.replace(producer, module, handlers, RoamingRole.PRODUCER);
            producer.initializeRoaming(new RoamingPayload(new TreeMap<>(Map.of("a", 1.0f))));
            assertFalse(producer.hasRoamingDelta());

            producer.update(inputs(), evaluation -> {
                evaluation.dispatchPlayerUpdate(true);
                return null;
            });
            assertTrue(producer.hasRoamingDelta());
            var delta = producer.takeRoamingDelta();
            assertEquals(Map.of("a", 5.0f, "b", 0.0f), delta.values());
            assertFalse(producer.hasRoamingDelta());
            var full = producer.takeRoamingFull();
            assertEquals(Map.of("a", 5.0f, "b", 0.0f), full.values());

            TestExpressionModules.replace(consumer, module, handlers, RoamingRole.CONSUMER);
            consumer.initializeRoaming(full);
            assertEquals(5.0f, consumer.roamingState().orElseThrow().read("a"));
            assertEquals(0.0f, consumer.roamingState().orElseThrow().read("b"));
            consumer.applyRoamingPatch(new RoamingPayload(new TreeMap<>(Map.of("a", 8.0f))));
            assertEquals(8.0f, consumer.roamingState().orElseThrow().read("a"));
            assertEquals(0.0f, consumer.roamingState().orElseThrow().read("b"));
            consumer.update(inputs(), evaluation -> {
                evaluation.dispatchPlayerUpdate(true);
                return null;
            });
            assertEquals(5.0f, consumer.roamingState().orElseThrow().read("a"));
            assertEquals(0.0f, consumer.roamingState().orElseThrow().read("b"));
            consumer.applyRoamingPatch(new RoamingPayload(new TreeMap<>(Map.of("a", 8.0f))));
            assertEquals(8.0f, consumer.roamingState().orElseThrow().read("a"));

            assertThrows(IllegalArgumentException.class, () -> consumer
                .applyRoamingPatch(new RoamingPayload(new TreeMap<>(Map.of("a", 9.0f, "unknown", 1.0f)))));
            assertEquals(8.0f, consumer.roamingState().orElseThrow().read("a"));
        } finally {
            producer.close();
            consumer.close();
            module.close();
        }
    }

    @Test
    void generatedRoamingLayoutIncludesTargetDeclaredNames(@TempDir Path temp) throws Exception {
        var input = input();
        var strings =
            StringDataProto.newBuilder().addUserFunctions(eventFunction("@player_update", "r.custom_state=5;")).build();
        var paths = temp.resolve("cache");
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
            new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(), false,
                ModelDataProto.newBuilder().build(), strings, input.settings(), input.configBytes());
        var location = new ModelProgramInventory.SemanticLocation("player", "function/@player_update",
            ProgramRole.EVENT_PLAYER_UPDATE, "player_update:0");
        var runtime = new EntityExpressionRuntime();

        try {
            TestExpressionModules.replace(runtime, module,
                Map.of("player_update", List.of(module.requireEventProgram(location))), RoamingRole.PRODUCER);
            runtime.initializeRoaming(new RoamingPayload(new TreeMap<>()));
            runtime.update(inputs(), evaluation -> {
                evaluation.dispatchPlayerUpdate(true);
                return null;
            });

            assertEquals(Map.of("custom_state", 5.0f), runtime.takeRoamingDelta().values());
        } finally {
            runtime.close();
            module.close();
        }
    }

    @Test
    void roamingLayoutOutsideWireCapacityRejectsTheWholeTarget(@TempDir Path temp) throws Exception {
        var input = input();
        var tooMany = new StringBuilder();
        for (int index = 0; index <= 64; index++) {
            tooMany.append("r.field_").append(index).append("=1;");
        }
        var tooManyStrings =
            StringDataProto.newBuilder().addUserFunctions(eventFunction("@player_update", tooMany.toString())).build();
        var tooManyPaths = temp.resolve("too-many");
        assertThrows(AssetLoadException.class,
            () -> factory(track(new ExpressionArtifactStore(tooManyPaths, track(new AtomicSharedCache(tooManyPaths)))),
                new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(),
                    false, ModelDataProto.newBuilder().build(), tooManyStrings, input.settings(), input.configBytes()));

        var longName = "r." + "x".repeat(33) + "=1;";
        var longNameStrings =
            StringDataProto.newBuilder().addUserFunctions(eventFunction("@player_update", longName)).build();
        var longNamePaths = temp.resolve("long-name");
        assertThrows(AssetLoadException.class,
            () -> factory(
                track(new ExpressionArtifactStore(longNamePaths, track(new AtomicSharedCache(longNamePaths)))),
                new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(),
                    false, ModelDataProto.newBuilder().build(), longNameStrings, input.settings(),
                    input.configBytes()));
    }

    @Test
    void generatedRoamingFullPreservesTargetDeclaredNames(@TempDir Path temp) throws Exception {
        var input = input();
        var strings =
            StringDataProto.newBuilder().addUserFunctions(eventFunction("@player_update", "r.custom_state=5;")).build();
        var paths = temp.resolve("cache");
        var module = factory(track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths)))),
            new ExpressionEngineMetrics()).create(() -> false, input.model(), "player", "main", input.profile(), false,
                ModelDataProto.newBuilder().build(), strings, input.settings(), input.configBytes());
        var location = new ModelProgramInventory.SemanticLocation("player", "function/@player_update",
            ProgramRole.EVENT_PLAYER_UPDATE, "player_update:0");
        var producer = new EntityExpressionRuntime();
        var consumer = new EntityExpressionRuntime();

        try {
            TestExpressionModules.replace(producer, module,
                Map.of("player_update", List.of(module.requireEventProgram(location))), RoamingRole.PRODUCER);
            producer.initializeRoaming(new RoamingPayload(new TreeMap<>()));
            producer.update(inputs(), evaluation -> {
                evaluation.dispatchPlayerUpdate(true);
                return null;
            });

            var full = producer.takeRoamingFull();
            assertEquals(Map.of("custom_state", 5.0f), full.values());
            assertFalse(producer.hasRoamingDelta());

            TestExpressionModules.replace(consumer, module, Map.of(), RoamingRole.CONSUMER);
            consumer.initializeRoaming(full);
            assertEquals(5.0f, consumer.roamingState().orElseThrow().read("custom_state"));
        } finally {
            producer.close();
            consumer.close();
            module.close();
        }
    }

    @Test
    void semanticFailureFallsBackOnlyTheAffectedProgram(@TempDir Path temp) throws Exception {
        var paths = temp.resolve("cache");
        var shared = track(new AtomicSharedCache(paths));
        var input = input("q.anim_time=1");
        var valid = animation("valid", "7");
        var file = input.definition().animationFiles().get("main").toBuilder().addAnimations(valid).build();
        var definition = input.definition().toBuilder().putAnimationFiles("main", file).build();
        var module = factory(track(new ExpressionArtifactStore(paths, shared)), new ExpressionEngineMetrics()).create(
            () -> false, input.model(), "player", "main", input.profile(), false, definition, null, input.settings(),
            input.configBytes());
        var failedLocation = new ModelProgramInventory.SemanticLocation("player", "animation/main/idle/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
        var validLocation = new ModelProgramInventory.SemanticLocation("player", "animation/main/valid/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, module, Map.of());

        try {
            var replacement = module.compilationReport().replacements().get(0);
            assertEquals(failedLocation, module.programLocations().get(replacement.programKey()));
            var values = runtime.update(inputs(),
                evaluation -> new float[] {evaluation.evaluate(module.requireFloatProgram(failedLocation)),
                    evaluation.evaluate(module.requireFloatProgram(validLocation))});
            assertEquals(0.0f, values[0]);
            assertEquals(7.0f, values[1]);
        } finally {
            runtime.close();
            module.close();
        }

        com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        var warmMetrics = new ExpressionEngineMetrics();
        var warm =
            factory(track(new ExpressionArtifactStore(paths, shared)), warmMetrics).create(() -> false, input.model(),
                "player", "main", input.profile(), false, definition, null, input.settings(), input.configBytes());
        try {
            assertEquals(ExpressionArtifactStore.Source.DISK, warm.source());
            assertEquals(module.compilationReport().replacements(), warm.compilationReport().replacements());
            var replacement = warm.compilationReport().replacements().get(0);
            assertEquals(failedLocation, warm.programLocations().get(replacement.programKey()));
            assertEquals(0, warmMetrics.snapshot().stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS));
            assertEquals(0, warmMetrics.snapshot().stageCalls(EngineMetrics.Stage.SOURCE_GENERATION));
            assertEquals(0, warmMetrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        } finally {
            warm.close();
        }
    }

    @Test
    void hostAnalysisPolicyRejectsAsBudgetAndNewPolicyCanUseTheSameSourcesAndStore(@TempDir Path paths) throws Exception {
        var store = track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths))));
        var input = input("1+2");
        var rejectedHost = ExpressionHost.create(HostAvailability.none(), ExpressionPolicy.create(
            ExpressionPolicy.COMPILER_LIMITS.withAnalysisLimits(4_097, 1), ExpressionPolicy.EXECUTION_LIMITS));
        var rejected = new RenderTargetExpressionModuleFactory(store, rejectedHost, new ExpressionEngineMetrics());
        var failure = assertThrows(AssetLoadException.class, () -> rejected.create(() -> false, input.model(), "player",
            "main", input.profile(), false, input.definition(), null, input.settings(), input.configBytes()));
        assertTrue(failure.getMessage().contains("budget rejected"));
        var limit = assertInstanceOf(dev.semaweave.compilation.ModuleLimitException.class, failure.getCause());
        assertEquals(dev.semaweave.compilation.LimitKind.TYPE_ANALYSIS_OPERATIONS, limit.limitKind());
        assertEquals(1, limit.maximum());
        assertEquals(dev.semaweave.compilation.ModuleLimitException.Phase.ANALYSIS, limit.phase());
        assertEquals(java.util.List.of(new dev.semaweave.ProgramId(0)), limit.programIds());
        assertEquals(2, limit.actual());
        assertTrue(limit.nodePath().isEmpty());
        assertInstanceOf(dev.semaweave.compilation.ModuleLimitException.class, limit.getCause());
        var acceptedHost = ExpressionHost.create(HostAvailability.none(), ExpressionPolicy.create(
            ExpressionPolicy.COMPILER_LIMITS.withAnalysisLimits(4_097, 16_000_001), ExpressionPolicy.EXECUTION_LIMITS));
        try (var module = new RenderTargetExpressionModuleFactory(store, acceptedHost, new ExpressionEngineMetrics())
            .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
                input.settings(), input.configBytes())) {
            assertEquals(4_097, module.host().compilationPolicy().compilerLimits().maxFunctionVersions());
            assertEquals(16_000_001, module.host().compilationPolicy().compilerLimits().maxAnalysisOperations());
        }
    }

    @Test
    void persistentAndTransientConsumeTheHostNodeBudgetAndWarmPolicyRemainsIndependent(@TempDir Path paths)
        throws Exception {
        var input = input("1");
        var limits = withLimit("maxDecodedNodes", 4);
        var host = ExpressionHost.create(HostAvailability.none(), ExpressionPolicy.create(limits,
            new dev.semaweave.ExecutionLimits(2, 3, 4, 5, 6)));
        var store = track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths))));
        try (var module = new RenderTargetExpressionModuleFactory(store, host, new ExpressionEngineMetrics())
            .create(() -> false, input.model(), "player", "main", input.profile(), false, input.definition(), null,
                input.settings(), input.configBytes())) {
            var rejected = assertThrows(dev.semaweave.compilation.ModuleLimitException.class,
                () -> TransientExpressionSession.compile(module, 1, "1+2+3+4", TransientExpressionSession.Privilege.LOCAL_WATCH,
                    TransientExpressionSession.Purpose.WATCH, "test/host-budget", () -> false));
            assertEquals(dev.semaweave.compilation.LimitKind.DECODED_NODES, rejected.limitKind());
            assertEquals(4, rejected.maximum());
            try (var accepted = TransientExpressionSession.compile(module, 1, "1", TransientExpressionSession.Privilege.LOCAL_WATCH,
                TransientExpressionSession.Purpose.WATCH, "test/host-budget", () -> false)) {
                assertEquals(host.compilationPolicy(), module.host().compilationPolicy());
            }
        }
        store.awaitPersistence();
        var warmMetrics = new ExpressionEngineMetrics();
        {
            var warmStore = track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths))));
            try (var warm = new RenderTargetExpressionModuleFactory(warmStore, host, warmMetrics).create(() -> false,
                input.model(), "player", "main", input.profile(), false, input.definition(), null, input.settings(),
                input.configBytes())) {
                assertEquals(ExpressionArtifactStore.Source.DISK, warm.source());
                assertEquals(0, warmMetrics.snapshot().stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS));
            }
        }
        var tooSmall = ExpressionHost.create(HostAvailability.none(), ExpressionPolicy.create(withLimit("maxDecodedNodes", 1),
            ExpressionPolicy.EXECUTION_LIMITS));
        var largerInput = input("1+2");
        var failure = assertThrows(AssetLoadException.class,
            () -> new RenderTargetExpressionModuleFactory(store, tooSmall, new ExpressionEngineMetrics()).create(() -> false,
                largerInput.model(), "player", "main", largerInput.profile(), false, largerInput.definition(), null,
                largerInput.settings(), largerInput.configBytes()));
        assertEquals(dev.semaweave.compilation.LimitKind.DECODED_NODES,
            assertInstanceOf(dev.semaweave.compilation.ModuleLimitException.class, failure.getCause()).limitKind());
    }

    @Test
    void coldWarmAndTransientExecutionUseTheHostLoopBudgetAndResetTheNextEntry(@TempDir Path paths) throws Exception {
        var input = input("loop(2,1);return 7;");
        var good = animation("good", "loop(1,1);return 7;");
        var file = input.definition().animationFiles().get("main").toBuilder().addAnimations(good).build();
        var definition = input.definition().toBuilder().putAnimationFiles("main", file).build();
        var host = ExpressionHost.create(HostAvailability.none(), ExpressionPolicy.create(ExpressionPolicy.COMPILER_LIMITS,
            new dev.semaweave.ExecutionLimits(1, 2, 3, 4, 5)));
        var store = track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths))));
        var badLocation = new ModelProgramInventory.SemanticLocation("player", "animation/main/idle/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
        var goodLocation = new ModelProgramInventory.SemanticLocation("player", "animation/main/good/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
        for (int pass = 0; pass < 2; pass++) {
            var metrics = new ExpressionEngineMetrics();
            var currentStore = pass == 0 ? store : track(new ExpressionArtifactStore(paths, track(new AtomicSharedCache(paths))));
            try (var module = new RenderTargetExpressionModuleFactory(currentStore, host, metrics).create(() -> false,
                input.model(), "player", "main", input.profile(), false, definition, null, input.settings(), input.configBytes());
                var runtime = new EntityExpressionRuntime()) {
                TestExpressionModules.replace(runtime, module, Map.of());
                runtime.observe(inputs(), evaluation -> {
                    assertEquals(0f, evaluation.evaluate(module.requireFloatProgram(badLocation)));
                    var failure = assertInstanceOf(ExecutionFailure.class, evaluation.failure().orElseThrow());
                    assertEquals(ExecutionFailure.Reason.LOOP_BUDGET, failure.reason());
                    assertEquals(1, failure.maximum().orElseThrow());
                    assertEquals("EXECUTION", failure.phase());
                    return null;
                });
                float next = runtime.observe(inputs(), evaluation -> evaluation.evaluate(module.requireFloatProgram(goodLocation)));
                assertEquals(7f, next);
                try (var transientProgram = runtime.compileTransient(runtime.generation(), "loop(2,1);return 7;",
                    TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                    "test/host-loop-budget", () -> false)) {
                    var failure = assertThrows(ExecutionFailure.class, () -> runtime.executeTransient(inputs(), transientProgram));
                    assertEquals(ExecutionFailure.Reason.LOOP_BUDGET, failure.reason());
                    assertEquals(1, failure.maximum().orElseThrow());
                }
                if (pass == 1) {
                    assertEquals(ExpressionArtifactStore.Source.DISK, module.source());
                    assertEquals(0, metrics.snapshot().stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS));
                }
            }
            currentStore.awaitPersistence();
        }
    }

    private static dev.semaweave.CompilerLimits withLimit(String name, long value) throws Exception {
        var fields = dev.semaweave.CompilerLimits.class.getRecordComponents();
        Object[] values = new Object[fields.length];
        for (int index = 0; index < fields.length; index++) {
            values[index] = fields[index].getAccessor().invoke(ExpressionPolicy.COMPILER_LIMITS);
            if (fields[index].getName().equals(name)) {
                if (fields[index].getType() == int.class) values[index] = Math.toIntExact(value);
                else values[index] = value;
            }
        }
        return dev.semaweave.CompilerLimits.class.getConstructor(Arrays.stream(fields)
            .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(values);
    }

    private static RenderTargetExpressionModuleFactory factory(ExpressionArtifactStore store,
        ExpressionEngineMetrics metrics) {
        return new RenderTargetExpressionModuleFactory(store, ExpressionHost.create(HostAvailability.all()), metrics);
    }

    private static EntityExpressionRuntime.InvocationInputs inputs() {
        return new EntityExpressionRuntime.InvocationInputs(EMPTY_INPUTS, (commands, provenance) -> () -> {
        }, () -> false);
    }

    private static ExpressionArtifactLookup lookup(Input input, ExpressionHost host, MolangEventCatalog eventCatalog,
        HostLinkage productionLinkage, BackendDescriptor backend) {
        return ExpressionArtifactLookup.create(input.model(), "player", "main", input.profile(), false,
            host.expressionBinding(), input.definition(), null, input.settings(), input.configBytes(), host,
            eventCatalog, productionLinkage, CompilerOptions.DEFAULT, backend);
    }

    private static ExpectedArtifactIdentity expected(ExpressionHost host, EncodedProgram primary,
        EncodedProgram fallback, CompilerOptions options, BackendDescriptor backend) throws Exception {
        var source =
            new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf("identity-key".getBytes(StandardCharsets.UTF_8))),
                primary, ProgramRole.ANIMATION_SAMPLE.signature(null, List.of()), Optional.of(fallback),
                new DiagnosticLocation("identity-test"));
        var unit = CompilationUnit
            .builder(host.compilationPolicy(), PortableProgramCodecs.v1(), ExpressionIdentityHash.SHA_256)
            .addProgram(source).build();
        return ExpectedArtifactIdentity.create(unit, host.hostAbi(), host.registry(), options, backend,
            ExpressionIdentityHash.SHA_256);
    }

    private static Input input() throws Exception {
        return input("1.25");
    }

    private static Input input(String source) throws Exception {
        var definition = ModelDataProto.newBuilder()
            .putAnimationFiles("main", AnimationFileProto.newBuilder().addAnimations(animation("idle", source)).build())
            .build();
        var settings = SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
            .setDisablePreviewRotation(false).build();
        return new Input(new ModelFileIdentity(hash(1), hash(2)), new BakeProfile("test-profile"), definition, settings,
            ProtoUtil.serializeToArray(settings));
    }

    private static AnimationProto animation(String name, String source) throws Exception {
        var program = EncodedProgramMapper.toProto(new MolangFrontend().compile(source,
            MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("test/blend-weight")).program());
        return AnimationProto.newBuilder().setName(name).setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE)
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(program).build()).build();
    }

    private static UserFunctionProto eventFunction(String name, String source) throws Exception {
        var encoded = EncodedProgramMapper.toProto(new MolangFrontend()
            .compile(source, MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("test/" + name))
            .program());
        return UserFunctionProto.newBuilder().setName(name).setBody(encoded).build();
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte)marker);
        return new Hash256(bytes);
    }

    private record Input(ModelFileIdentity model, BakeProfile profile, ModelDataProto definition,
        SettingsProto settings, byte[] configBytes) {
    }
}
