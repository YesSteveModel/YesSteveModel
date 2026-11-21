package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.api.internal.processor.UnifiedDeclarationProcessor;
import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import dev.semaweave.SymbolKey;
import dev.semaweave.compilation.EngineMetrics;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultDeclarationProjectionTest {
    @Test
    void oneAdmissionClosesDefaultHostsBindingsAndBothArtifactSets(@TempDir Path root) throws Exception {
        var output = Files.createDirectories(root.resolve("extension"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var source = new SimpleJavaFileObject(URI.create("string:///projection/Extension.java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignore) {
                    return """
                        package projection;
                        import com.elfmcys.ysm.api.annotation.*;
                        import com.elfmcys.ysm.api.molang.*;
                        @YsmEntityComponent("projection")
                        public class Extension {
                            @MolangFunction
                            public static float value(float argument) { return 17; }
                            public static int calls;
                            @MolangFunction("side_effect")
                            public static float sideEffect() { calls++; return 1; }
                        }
                        """;
                }
            };
            var task = compiler.getTask(null, files, diagnostics, List.of("--release", "17", "-classpath",
                System.getProperty("java.class.path"), "-d", output.toString()), null, List.of(source));
            task.setProcessors(List.of(new UnifiedDeclarationProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        var runtime = AdmissionRuntimeFixture.classes(root.resolve("runtime"));
        var classpath = java.util.stream.Stream
            .of(System.getProperty("java.class.path"), System.getProperty("ysm.test.projectionClasspath", ""))
            .flatMap(value -> java.util.Arrays.stream(value.split(File.pathSeparator)))
            .filter(value -> !value.isBlank()).distinct().toList();
        for (boolean optionalPresent : List.of(false, true)) {
            var paths = classpath.stream()
                .filter(path -> optionalPresent || java.util.Arrays.stream(OptionalCapability.values())
                    .noneMatch(c -> Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "").contains(c.modId().replace("_", ""))))
                .toList();
            System.out.println("PROJECTION optionalPresent=" + optionalPresent + " omitted="
                + classpath.stream().filter(path -> !paths.contains(path)).toList());
            var log = root.resolve("default-projection-" + optionalPresent + ".log");
            var arguments = root.resolve("default-projection-" + optionalPresent + ".args");
            Files.writeString(arguments,
                java.util.stream.Stream
                    .of("-Dfile.encoding=UTF-8", "-cp",
                        runtime + File.pathSeparator + output + File.pathSeparator
                            + String.join(File.pathSeparator, paths),
                        getClass().getName(), output.toString(), root.resolve("cache-" + optionalPresent).toString(),
                        Boolean.toString(optionalPresent))
                    .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
            var process =
                new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Default projection probe timed out");
                assertEquals(0, process.exitValue(), Files.readString(log));
                System.out.println(Files.readString(log));
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        var initializer = Class.forName("com.elfmcys.ysm.expression.host.ComponentScopeTest")
            .getDeclaredMethod("initializeMinecraftEntityClasses");
        initializer.setAccessible(true);
        var dist = net.minecraftforge.fml.loading.FMLEnvironment.class.getField("dist");
        dist.set(null, net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER);
        initializer.invoke(null);
        dist.set(null, net.minecraftforge.api.distmarker.Dist.CLIENT);
        verifyProjection(arguments[0], arguments[1], Boolean.parseBoolean(arguments[2]));
    }

    public static void verifyProjection(String extension, String cache, boolean optionalPresent) throws Exception {
        var qualifications = new AtomicInteger();
        var collector = new MolangExtensions.Collector();
        collector.collectBuiltins();
        collector.collect(new MolangExtensions.Source("fixture", Set.of("projection"),
            List.of(new MolangExtensions.DeclarationRef("projection.Extension", "projection", "")),
            DefaultDeclarationProjectionTest.class.getClassLoader(), Path.of(extension)));
        var admission = collector.freeze(optionalPresent ? HostAvailability.all() : HostAvailability.none(),
            new MolangExtensions.Qualification() {
                @Override
                public boolean dependency(String id, String version) {
                    return optionalPresent;
                }

                @Override
                public boolean checker(MolangExtensions.Source source, DeclarationSchema schema) {
                    qualifications.incrementAndGet();
                    return true;
                }
            });
        var loader = DefaultDeclarationProjectionTest.class.getClassLoader();
        for (String optionalClass : List.of("com.alrex.parcool.common.capability.Parkourability",
            "top.theillusivec4.curios.api.CuriosApi")) {
            if (optionalPresent)
                assertNotNull(Class.forName(optionalClass, false, loader));
            else
                assertThrows(ClassNotFoundException.class, () -> Class.forName(optionalClass, false, loader));
        }
        var host = admission.host();
        var qualified = qualifications.get();
        var builtinOnly = host.builtinOnly();
        assertTrue(host.registry().function(new SymbolKey("projection:value")).isPresent());
        assertTrue(builtinOnly.registry().function(new SymbolKey("projection:value")).isEmpty());
        assertTrue(builtinOnly.registry().function(new SymbolKey("query:ground_speed")).isPresent());
        assertTrue(builtinOnly.declarations().stream().allMatch(d -> d.schema().dependencies().isEmpty()));
        for (String name : List.of("ctrl:parcool_state", "ysm:has_any_curios", "ysm:has_any_curios_with_all_tags",
            "ysm:has_any_curios_with_any_tag", "ysm:dump_curios")) {
            var key = new SymbolKey(name);
            assertEquals(optionalPresent, host.registry().function(key).isPresent(),
                () -> name + ": " + admission.diagnostics());
            assertTrue(builtinOnly.registry().function(key).isEmpty(), name);
        }
        System.out.println("PROJECTED optionalPresent=" + optionalPresent + " retained="
            + builtinOnly.declarations().stream().flatMap(d -> d.members().stream()).count() + " excluded="
            + host.declarations().stream().filter(d -> !builtinOnly.declarations().contains(d))
                .flatMap(d -> d.members().stream()).map(m -> m.schema().symbol()).toList());

        var root = Path.of(cache);
        var factory = factory(root, host, new ExpressionEngineMetrics());
        var defaults = factory.forDefaultModel(true);
        var subject = net.minecraft.world.entity.LivingEntity.class;
        var ordinaryBinding = factory.expressionBinding(false, subject);
        var defaultBinding = factory.expressionBinding(true, subject);
        assertThrows(IllegalArgumentException.class,
            () -> module(defaults, ordinaryBinding, ExpressionSet.MAIN, false));
        assertThrows(IllegalArgumentException.class, () -> module(factory, defaultBinding, ExpressionSet.MAIN, false));
        var identities = new ArrayList<ExpressionArtifactIdentity>();
        for (var set : ExpressionSet.values()) {
            try (var ordinary = module(factory, ordinaryBinding, set, false);
                var intrinsic = module(defaults, defaultBinding, set, false)) {
                assertSame(host, ordinary.host());
                assertSame(builtinOnly, intrinsic.host());
                assertEquals(ExpressionArtifactStore.Source.COLD, intrinsic.source());
                assertNotEquals(ordinary.identity().cacheKey(), intrinsic.identity().cacheKey());
                identities.add(intrinsic.identity());
            }
        }
        com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        var metrics = new ExpressionEngineMetrics();
        var warm = factory(root, host, metrics).forDefaultModel(true);
        for (var set : ExpressionSet.values()) {
            try (var intrinsic = module(warm, defaultBinding, set, true)) {
                assertSame(builtinOnly, intrinsic.host());
                assertEquals(ExpressionArtifactStore.Source.DISK, intrinsic.source());
                assertEquals(identities.get(set.ordinal()).cacheKey(), intrinsic.identity().cacheKey());
            }
        }
        assertEquals(0, metrics.snapshot().portableProgramDecodes());
        assertEquals(0, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        assertEquals(qualified, qualifications.get());
        verifyMissingValues(factory, defaults);
        verifyRealDefault(host, root.resolve("real-default"));
        rejectUntrustedBuiltinSource();
    }

    private static void rejectUntrustedBuiltinSource() {
        var collector = new MolangExtensions.Collector();
        collector.collect(BuiltinDeclarations.source());
        var admission = collector.freeze(HostAvailability.none(), new MolangExtensions.Qualification() {
            @Override
            public boolean dependency(String id, String version) {
                throw new AssertionError("Untrusted builtin metadata must be rejected before qualification");
            }

            @Override
            public boolean checker(MolangExtensions.Source source, DeclarationSchema schema) {
                throw new AssertionError("Untrusted builtin metadata must be rejected before qualification");
            }
        });
        assertFalse(admission.diagnostics().isEmpty());
        assertTrue(admission.host().declarations().isEmpty());
        assertTrue(admission.host().builtinOnly().declarations().isEmpty());
    }

    private static void verifyMissingValues(RenderTargetExpressionModuleFactory ordinary,
        RenderTargetExpressionModuleFactory intrinsic) throws Exception {
        var extension = Class.forName("projection.Extension");
        var calls = extension.getField("calls");
        for (var set : ExpressionSet.values())
            for (var purpose : ModelResourcePurpose.values())
                for (boolean isDefault : List.of(false, true)) {
                    var factory = isDefault ? intrinsic : ordinary;
                    var subject = isDefault ? net.minecraft.world.entity.LivingEntity.class
                        : purpose == ModelResourcePurpose.GUI
                            ? com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class
                            : net.minecraft.client.player.AbstractClientPlayer.class;
                    var module = module(factory, factory.expressionBinding(subject), set, false, purpose);
                    try (var animations = TestExpressionModules.store(module, Map.of());
                        var runtime = new EntityExpressionRuntime()) {
                        assertEquals(purpose.isGui(), animations.interpreted());
                        if (purpose.isGui()) {
                            assertNull(animations.diagnostics().compilationReport());
                            assertEquals(0, animations.boundProgramCount());
                        } else {
                            assertTrue(animations.diagnostics().compilationReport().replacements().isEmpty());
                        }
                        var entity = install(runtime, animations, subject);
                        calls.setInt(null, 0);
                        var location = new com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation(
                            "player", set,
                            "animation/fallback/" + "a".repeat(64) + "/player/" + set.id() + "/main/idle/blend-weight",
                            com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_SAMPLE, "");
                        var inputs = inputs(entity);
                        float result = runtime.update(inputs,
                            evaluation -> evaluation.evaluate(module.requireFloatProgram(location)));
                        assertEquals(isDefault ? 7f : 17f, result);
                        assertEquals(isDefault ? 0 : 1, calls.getInt(null));
                        if (purpose.isGui()) {
                            assertEquals(1, animations.boundProgramCount());
                            continue;
                        }
                        calls.setInt(null, 0);
                        try (var code = runtime.compileTransient(runtime.generation(),
                            "return projection.value(projection.side_effect()) ?? 7;",
                            TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                            "projection", () -> false)) {
                            assertEquals(isDefault ? 7f : 17f, runtime.executeTransient(inputs, code));
                            assertEquals(isDefault ? 0 : 1, calls.getInt(null));
                        }
                    }
                }
    }

    private static net.minecraft.world.entity.Entity install(EntityExpressionRuntime runtime, AnimationStore animations,
        Class<?> rootType) throws Exception {
        var concrete = rootType == net.minecraft.world.entity.LivingEntity.class
            ? net.minecraft.world.entity.decoration.ArmorStand.class
            : rootType == net.minecraft.client.player.AbstractClientPlayer.class ? ProjectionPlayer.class : rootType;
        var subject = com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(concrete);
        var linkage = TestComponentHolders.linkageScope();
        var scope = (ComponentScope)java.lang.reflect.Proxy.newProxyInstance(ComponentScope.class.getClassLoader(),
            new Class<?>[] {ComponentScope.class},
            (proxy, method, args) -> method.getName().equals("subject") ? subject : method.invoke(linkage, args));
        runtime.replace(animations, scope);
        return (net.minecraft.world.entity.Entity)subject;
    }

    private static final class ProjectionPlayer extends net.minecraft.client.player.RemotePlayer
        implements PlayerQueryAccess {
        private PlayerQueryProjection projection;

        private ProjectionPlayer() {
            super(null, null);
        }

        public PlayerQueryProjection ysm$playerQueryProjection() {
            return projection == null ? PlayerQueryProjection.MISSING : projection;
        }

        public void ysm$playerQueryProjection(PlayerQueryProjection value) {
            projection = value;
        }
    }

    private static EntityExpressionRuntime.InvocationInputs inputs(net.minecraft.world.entity.Entity subject) {
        return new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<net.minecraft.world.entity.Entity>() {
            @Override
            public net.minecraft.world.entity.Entity entity() {
                return subject;
            }
        });
    }

    private static void verifyRealDefault(ExpressionHost host, Path root) throws Exception {
        Files.createDirectories(root);
        var resource = DefaultDeclarationProjectionTest.class.getResource("/assets/ysm/builtin/default/ysm.json");
        try (var directory = new com.elfmcys.ysm.format.vfs.Directory(Path.of(resource.toURI()).getParent())) {
            var memory = com.elfmcys.ysm.format.parser.ModelParser.parseBuiltinDefaultMemory(directory);
            var location = new com.elfmcys.ysm.model.catalog.source.CatalogLocation(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.BUILTIN,
                new com.elfmcys.ysm.model.domain.ModelPath("default"));
            var container =
                com.elfmcys.ysm.model.storage.ManagedContainer.openResidentDefault(memory.bytes(), location);
            var view = container.view();
            var assets = view.getFileView().getAssetView();
            var target = view.requireRenderTarget("player");
            var prefix = com.elfmcys.ysm.format.schema.file.AssetFileConstant.BLOB_CHUNK_PREFIX;
            var blob = container.chunks().readPayload(assets.getChunkInfo(prefix + target.descriptor().blobId()),
                com.elfmcys.ysm.buffer.BufferType.ARRAY);
            var definition = ModelDataProto
                .parseFrom(com.elfmcys.ysm.util.ProtoUtil.source((com.elfmcys.ysm.buffer.ArrayBuffer)blob));
            var common = container.chunks().readPayload(
                assets.getChunkInfo(prefix + view.getManifest().commonAssets().stringsBlobId()),
                com.elfmcys.ysm.buffer.BufferType.ARRAY);
            var strings = StringDataProto
                .parseFrom(com.elfmcys.ysm.util.ProtoUtil.source((com.elfmcys.ysm.buffer.ArrayBuffer)common));
            for (var set : ExpressionSet.values()) {
                var selected = set.select(definition);
                for (boolean warm : List.of(false, true)) {
                    com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
                    var metrics = new ExpressionEngineMetrics();
                    var intrinsic = factory(root, host, metrics).forDefaultModel(true);
                    long started = System.nanoTime();
                    var module = intrinsic.createSet(() -> false, assets.getContainerId(), "player", set,
                        intrinsic.expressionBinding(net.minecraft.world.entity.LivingEntity.class), selected, strings,
                        view.getManifest().info().settings(), new byte[0], warm, ModelResourcePurpose.FULL,
                        dev.semaweave.compilation.CompilerOptions.DEFAULT);
                    try (
                        var animations = TestExpressionModules.store(module, Map.of(),
                            module.animationIndex(selected.animationFiles().keySet()));
                        var runtime = new EntityExpressionRuntime()) {
                        assertTrue(module.compilationReport().replacements().isEmpty());
                        assertEquals(warm ? ExpressionArtifactStore.Source.DISK : ExpressionArtifactStore.Source.COLD,
                            module.source());
                        if (warm)
                            assertEquals(0, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
                        System.out.println("REAL_DEFAULT model=" + memory.modelHash() + " set=" + set + " source="
                            + module.source() + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000.0
                            + " artifact=" + module.identity().cacheKey());
                        var inputs =
                            inputs(install(runtime, animations, net.minecraft.world.entity.LivingEntity.class));
                        if (set == ExpressionSet.MAIN) {
                            pose(runtime, inputs, animations.get("idle"), "LeftArm", 1.5f, 2, -1);
                            pose(runtime, inputs, animations.get("walk"), "LeftLeg", 0.5f, 0, 22.49507f);
                            pose(runtime, inputs, animations.get("run"), "LeftLeg", 0.25f, 0, 30);
                            pose(runtime, inputs, animations.get("jump"), "LeftLeg", 0.4167f, 1, -1);
                            pose(runtime, inputs, animations.get("swim"), "LeftArm", 0.5f, 0, 31.36f);
                            pose(runtime, inputs, animations.get("swing_hand"), "RightArm", 0.1667f, 0, -40.5f);
                            pose(runtime, inputs, animations.get("attacked"), "Root", 0.1667f, 1, 11.25f);
                        } else {
                            assertNotNull(animations.get("parallel0"));
                            assertTrue(animations.get("parallel0").boneAnimations.isEmpty());
                        }
                    }
                }
            }
            java.lang.ref.Reference.reachabilityFence(blob);
            java.lang.ref.Reference.reachabilityFence(common);
            java.lang.ref.Reference.reachabilityFence(container);
        }
    }

    private static void pose(EntityExpressionRuntime runtime, EntityExpressionRuntime.InvocationInputs inputs,
        com.elfmcys.ysm.geckolib3.core.builder.Animation animation, String boneName, float seconds, int axis,
        float degrees) {
        assertNotNull(animation);
        var bone = animation.boneAnimations.stream().filter(value -> value.boneName.equals(boneName)).findFirst()
            .orElseThrow();
        float tick = seconds * 20;
        var frame = bone.rotationKeyFrames.stream().filter(value -> Math.abs(value.getEndTick() - tick) < 0.003f)
            .findFirst().orElseThrow();
        float value = runtime.observe(inputs, evaluation -> frame.getLerpPoint(evaluation, 1).get(axis));
        assertEquals(degrees * (axis == 2 ? 1 : -1) * (float)Math.PI / 180, value, 0.00001f,
            animation.name + "/" + boneName);
    }

    private static RenderTargetExpressionModuleFactory factory(Path root, ExpressionHost host,
        ExpressionEngineMetrics metrics) {
        var expressionCache = track(new AtomicSharedCache(root));
        return new RenderTargetExpressionModuleFactory(track(new ExpressionArtifactStore(root, expressionCache)),
            new AnimationInputStore(root, expressionCache), host, metrics);
    }

    private static RenderTargetExpressionModule module(RenderTargetExpressionModuleFactory factory,
        ExpressionBinding binding, ExpressionSet set, boolean cacheOnly) throws Exception {
        return module(factory, binding, set, cacheOnly, ModelResourcePurpose.FULL);
    }

    private static RenderTargetExpressionModule module(RenderTargetExpressionModuleFactory factory,
        ExpressionBinding binding, ExpressionSet set, boolean cacheOnly, ModelResourcePurpose purpose)
        throws Exception {
        var blend = EncodedProgramMapper.toProto(new MolangFrontend()
            .compile("projection.value(projection.side_effect()) ?? 7", MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("default-declaration-projection"))
            .program());
        var animation = AnimationProto.newBuilder().setName("idle").setLength(1)
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(blend).build()).build();
        var definition = ModelDataProto.newBuilder()
            .putAnimationFiles("fallback/" + "a".repeat(64) + "/player/" + set.id() + "/main",
                AnimationFileProto.newBuilder().addAnimations(animation).build())
            .build();
        return factory.createSet(() -> false, new Hash256(new byte[Hash256.SIZE]), "player", set, binding, definition,
            StringDataProto.newBuilder().build(), null, new byte[0], cacheOnly, purpose,
            dev.semaweave.compilation.CompilerOptions.DEFAULT);
    }
}
