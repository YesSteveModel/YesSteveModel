package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.*;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.*;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.compilation.CompilerOptions;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Finite input chains share a frozen installation; transient timing includes frontend and backend. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
public class MolangSemanticBenchmark {
    @State(Scope.Thread)
    public static class Workload {
        @Param({"cheap", "transcendental", "controller"})
        public String kind;
        @Param({"false", "true"})
        public boolean divZeroFallback;
        private String source;
        private Path root;
        private AtomicSharedCache cache;
        private AnimationStore store;
        private EntityExpressionRuntime runtime;
        private FloatExpression.Program program;
        private net.minecraft.world.entity.Entity subject;
        private final EntityExpressionRuntime.InvocationInputs inputs =
            new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<net.minecraft.world.entity.Entity>() {
                public net.minecraft.world.entity.Entity entity() {
                    return subject;
                }
            });

        @Setup(Level.Trial)
        public void setup() throws Exception {
            var fixture = Class.forName("com.elfmcys.ysm.expression.aot.ExpressionArtifactWarmProcessFixture");
            var field = fixture.getDeclaredField("SUBJECT");
            field.setAccessible(true);
            subject = (net.minecraft.world.entity.Entity)field.get(null);
            source = switch (kind) {
                case "cheap" -> "math.lerp(math.clamp(math.max(math.min(math.abs(-2),3),1),0,4),4,0.5)/2";
                case "transcendental" -> "math.sqrt(math.pow(math.exp(math.sin(30)+math.cos(60)),2))/2";
                case "controller" -> "v.stage=2;return v.stage==2?10:(v.stage==3?12:0)";
                default -> throw new IllegalArgumentException("unknown chain " + kind);
            };
            var encoded = new MolangFrontend().compile(source, MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("benchmark/" + kind)).program();
            var animation = AnimationProto.newBuilder().setName("chain").setLength(0).setLoop(LoopType.PLAY_ONCE)
                .setBlendWeight(
                    ExpressionValueProto.newBuilder().setProgram(EncodedProgramMapper.toProto(encoded)).build())
                .build();
            var model = ModelDataProto.newBuilder()
                .putAnimationFiles("main", AnimationFileProto.newBuilder().addAnimations(animation).build()).build();
            var host = ExpressionHost.create(HostAvailability.none());
            root = Files.createTempDirectory("ysm-semantic-jmh-");
            cache = new AtomicSharedCache(root);
            var factory = new RenderTargetExpressionModuleFactory(new ExpressionArtifactStore(root, cache),
                new AnimationInputStore(root, cache), host, new ExpressionEngineMetrics());
            store = factory.createStore(() -> false, new Hash256(new byte[Hash256.SIZE]), "player", ExpressionSet.MAIN,
                host.expressionBinding(), model, StringDataProto.newBuilder().build(),
                SettingsProto.newBuilder().build(), new byte[0], false, ModelResourcePurpose.FULL,
                new CompilerOptions(divZeroFallback));
            try (var preparation = store.prepare()) {
                program = preparation.requireFloatProgram(new ModelProgramInventory.SemanticLocation("player",
                    "animation/main/chain/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""));
            }
            runtime = new EntityExpressionRuntime();
            runtime.replace(store, BuiltinDeclarations.createComponents(TestComponentHolders.owner(subject), host));
            float expected = switch (kind) {
                case "cheap" -> 1.5f;
                case "transcendental" -> (float)Math.sqrt(Math.pow((float)Math.exp(1.0f), 2.0f)) / 2.0f;
                default -> 10.0f;
            };
            float actual = execute();
            if (Math.abs(actual - expected) > Math.ulp(expected) * 2)
                throw new AssertionError("chain oracle: " + actual + " != " + expected);
        }

        float execute() {
            return runtime.observe(inputs, evaluation -> evaluation.evaluate(program));
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            if (runtime != null)
                runtime.close();
            if (store != null)
                store.close();
            if (cache != null)
                cache.derivedWork().close();
            if (root != null) {
                try (var paths = Files.walk(root)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                        Files.delete(path);
                }
            }
        }
    }

    @Benchmark
    public float execute(Workload state) {
        return state.execute();
    }

    @Benchmark
    public int coldTransient(Workload state) throws Exception {
        try (var session = state.runtime.compileTransient(state.runtime.generation(), state.source,
            TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
            "benchmark/" + state.kind, () -> false)) {
            return session.hashCode();
        }
    }
}
