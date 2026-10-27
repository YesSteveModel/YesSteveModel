package com.elfmcys.ysm.expression.aot;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.api.internal.processor.UnifiedDeclarationProcessor;
import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.model.domain.Hash256;
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
                            @MolangFunction(effect=MolangEffect.CONTEXT)
                            public static float value(MolangContextView<net.minecraft.world.entity.Entity> c) {
                                return 17;
                            }
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
        var log = root.resolve("default-projection.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8", "-cp",
            runtime + File.pathSeparator + output + File.pathSeparator + System.getProperty("java.class.path"),
            getClass().getName(), output.toString(), root.resolve("cache").toString()).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Default projection probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    public static void main(String[] arguments) throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        verifyProjection(arguments[0], arguments[1]);
    }

    public static void verifyProjection(String extension, String cache) throws Exception {
        var qualifications = new AtomicInteger();
        var collector = new MolangExtensions.Collector();
        collector.collectBuiltins();
        collector.collect(new MolangExtensions.Source("fixture", Set.of("projection"),
            List.of(new MolangExtensions.DeclarationRef("projection.Extension", "projection", "")),
            DefaultDeclarationProjectionTest.class.getClassLoader(), Path.of(extension)));
        var admission = collector.freeze(HostAvailability.none(), new MolangExtensions.Qualification() {
            @Override
            public boolean dependency(String id, String version) {
                return false;
            }

            @Override
            public boolean checker(MolangExtensions.Source source, DeclarationSchema schema) {
                qualifications.incrementAndGet();
                return true;
            }
        });
        var host = admission.host();
        var qualified = qualifications.get();
        var builtinOnly = host.builtinOnly();
        assertTrue(host.registry().function(new SymbolKey("projection:value")).isPresent());
        assertTrue(builtinOnly.registry().function(new SymbolKey("projection:value")).isEmpty());

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

    private static RenderTargetExpressionModuleFactory factory(Path root, ExpressionHost host,
        ExpressionEngineMetrics metrics) {
        return new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(root, track(new AtomicSharedCache(root)))), host, metrics);
    }

    private static RenderTargetExpressionModule module(RenderTargetExpressionModuleFactory factory,
        ExpressionBinding binding, ExpressionSet set, boolean cacheOnly) throws Exception {
        var blend = EncodedProgramMapper.toProto(new MolangFrontend().compile("1", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("default-declaration-projection")).program());
        var animation = AnimationProto.newBuilder().setName("idle").setLength(1)
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(blend).build()).build();
        var definition = ModelDataProto.newBuilder()
            .putAnimationFiles("fallback/" + "a".repeat(64) + "/player/" + set.id() + "/main",
                AnimationFileProto.newBuilder().addAnimations(animation).build())
            .build();
        return factory.createSet(() -> false, new Hash256(new byte[Hash256.SIZE]), "player", set, binding, definition,
            StringDataProto.newBuilder().build(), null, new byte[0], cacheOnly, ModelResourcePurpose.FULL);
    }
}
