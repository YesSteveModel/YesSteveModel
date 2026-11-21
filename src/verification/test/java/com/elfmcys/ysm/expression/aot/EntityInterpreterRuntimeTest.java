package com.elfmcys.ysm.expression.aot;

import static com.elfmcys.ysm.expression.TestRoamingPayloads.named;
import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;
import static org.junit.jupiter.api.Assertions.*;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.expression.host.ComponentScope;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StateProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.TransitionProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import dev.semaweave.RoamingRole;
import dev.semaweave.compilation.CompilerOptions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EntityInterpreterRuntimeTest {
    @BeforeAll
    static void initializeMinecraftSubject() throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion();
        var gate = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        gate.setAccessible(true);
        boolean previous = gate.getBoolean(null);
        gate.setBoolean(null, true);
        try {
            net.minecraft.core.registries.BuiltInRegistries.REGISTRY.size();
            Class.forName(ArmorStand.class.getName(), true, EntityInterpreterRuntimeTest.class.getClassLoader());
        } finally {
            gate.setBoolean(null, previous);
        }
    }

    @Test
    void preparationLogsOnlyNewProgramsAcrossRepeatedInstances(@TempDir Path root) throws Exception {
        var messages = new ArrayList<String>();
        var capture = new AbstractAppender("interpreter-rebind-test", null, null, true) {
            @Override
            public void append(LogEvent event) {
                var message = event.getMessage().getFormattedMessage();
                if (message.startsWith("[resource] Molang interpreter"))
                    messages.add(message);
            }
        };
        var logger = (Logger)LogManager.getLogger("ysm");
        var previousLevel = logger.getLevel();
        var previousEnabled = ResourceDiagnostics.isEnabled();
        logger.setLevel(Level.INFO);
        capture.start();
        logger.addAppender(capture);
        try (var fixture = new Fixture(root, "fn.called()",
            StringDataProto.newBuilder().addUserFunctions(function("called", "return 1;")).build())) {
            ResourceDiagnostics.setEnabled(true);
            var program = fixture.module.requireFloatProgram(sample());
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var runtime = fixture.runtime(Map.of())) {
                    runtime.observe(fixture.inputs(), evaluation -> {
                        assertEquals(1F, evaluation.evaluate(program));
                        assertEquals(1F, evaluation.evaluate(program));
                        return null;
                    });
                    assertEquals(1, messages.size());
                    assertTrue(messages.get(0).contains("rebind: target=player programs=2 elapsedMs="));
                    assertEquals(1F,
                        runtime.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
                    assertEquals(1, messages.size(), "A new instance reuses resident programs");
                }
            }
            ResourceDiagnostics.setEnabled(false);
            try (var runtime = fixture.runtime(Map.of())) {
                assertEquals(1F, runtime.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
                ResourceDiagnostics.setEnabled(true);
                assertEquals(1F, runtime.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
                assertEquals(1, messages.size(), "A later instance cannot re-prepare the same resident program");
            }
        } finally {
            ResourceDiagnostics.setEnabled(previousEnabled);
            logger.removeAppender(capture);
            logger.setLevel(previousLevel);
            capture.stop();
        }
    }

    @Test
    void repeatedExpressionsInOneRoundInitializeLocalsAndKeepInstanceState(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, "t.saved=1; v.count=(v.count??0)+1; r.used=v.count; return t.saved;",
            StringDataProto.newBuilder().build()); var runtime = fixture.runtime(Map.of())) {
            var program = fixture.module.requireFloatProgram(sample());
            runtime.observe(fixture.inputs(), evaluation -> {
                assertEquals(1F, evaluation.evaluate(program));
                assertEquals(1F, evaluation.evaluate(program));
                return null;
            });
            assertEquals(Map.of("used", 2F), named(runtime.roamingState().orElseThrow()));
            assertEquals(1F, runtime.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertEquals(Map.of("used", 3F), named(runtime.roamingState().orElseThrow()));
        }
    }

    @Test
    void sharedProgramsKeepPrivateVariablesAndConsumeOnlyCalledFunctions(@TempDir Path root) throws Exception {
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(function("called", "v.count=(v.count??0)+1; r.used=v.count; return v.count;"))
            .addUserFunctions(function("unused", "return 99;")).build();
        try (var fixture = new Fixture(root, "fn.called()", strings); var first = fixture.runtime(Map.of());
            var second = fixture.runtime(Map.of())) {
            var module = fixture.module;
            assertEquals(0, module.boundInterpreterProgramCount());
            assertEquals(2, fixture.store.activeUses());
            var program = module.requireFloatProgram(sample());
            assertInstanceOf(ProgramBinding.Interpreted.class, program.binding());
            assertThrows(IllegalStateException.class, program::reference);
            assertEquals(1F, first.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertEquals(2, module.boundInterpreterProgramCount());
            assertEquals(2F, first.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertEquals(1F, second.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertEquals(Map.of("used", 2F), named(first.roamingState().orElseThrow()));
            assertEquals(Map.of("used", 1F), named(second.roamingState().orElseThrow()));
            assertThrows(IllegalStateException.class,
                () -> first.prepareTransient(first.generation(), "1", TransientExpressionSession.Privilege.LOCAL_WATCH,
                    TransientExpressionSession.Purpose.WATCH, "test:gui"));
        }
    }

    @Test
    void eventArgumentsAndDeferredCaptureUseTheSameSourceRound(@TempDir Path root) throws Exception {
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(function("@player_init", "v.value=1; ysm.defer(0,v.value); v.value=3;"))
            .addUserFunctions(function("@defer", "v.value=v.value*10+args[1];"))
            .addUserFunctions(function("@sync", "r.sync=args[0];"))
            .addUserFunctions(function("@player_update", "r.update=args[0];")).build();
        try (var fixture = new Fixture(root, "v.value", strings)) {
            var module = fixture.module;
            var handlers = Map.<String, List<EventProgram>>of("player_init",
                List.of(module.requireEventProgram(event("player_init", ProgramRole.EVENT_PLAYER_INIT))), "defer",
                List.of(module.requireEventProgram(event("defer", ProgramRole.EVENT_DEFER))), "sync",
                List.of(module.requireEventProgram(event("sync", ProgramRole.EVENT_SYNC))), "player_update",
                List.of(module.requireEventProgram(event("player_update", ProgramRole.EVENT_PLAYER_UPDATE))));
            try (var runtime = fixture.runtime(handlers)) {
                runtime.update(fixture.inputs(), evaluation -> {
                    evaluation.dispatchPlayerInit();
                    assertEquals(1, runtime.pendingDeferredCount());
                    return null;
                });
                assertEquals(31F, runtime.<Float>observe(fixture.inputs(),
                    evaluation -> evaluation.evaluate(module.requireFloatProgram(sample()))));
                assertEquals(0, runtime.pendingDeferredCount());
                runtime.action(fixture.inputs(), evaluation -> {
                    evaluation.dispatchSync(EventArguments.sync(9F));
                    evaluation.dispatchPlayerUpdate(true);
                    return null;
                });
                assertEquals(Map.of("sync", 9F, "update", 1F), named(runtime.roamingState().orElseThrow()));
            }
            assertEquals(0, fixture.store.activeUses());
        }
    }

    @Test
    void failureKeepsEarlierWritesAndDropsUndeliveredCaptures(@TempDir Path root) throws Exception {
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(function("@player_init", "r.first=7; ysm.defer(0); v.text='bad'; v.text.field=1;"))
            .addUserFunctions(function("@defer", "r.unexpected=1;")).build();
        try (var fixture = new Fixture(root, "1", strings)) {
            var module = fixture.module;
            var handlers = Map.<String, List<EventProgram>>of("player_init",
                List.of(module.requireEventProgram(event("player_init", ProgramRole.EVENT_PLAYER_INIT))), "defer",
                List.of(module.requireEventProgram(event("defer", ProgramRole.EVENT_DEFER))));
            try (var runtime = fixture.runtime(handlers); var other = fixture.runtime(Map.of())) {
                var failure =
                    assertThrows(ExecutionFailure.class, () -> runtime.update(fixture.inputs(), evaluation -> {
                        evaluation.dispatchPlayerInit();
                        return null;
                    }));
                assertEquals(ExecutionFailure.Reason.INSTANCE_EXECUTION, failure.reason());
                assertNotNull(failure.getCause());
                assertEquals(Map.of("first", 7F), named(runtime.roamingState().orElseThrow()));
                assertEquals(0, runtime.pendingDeferredCount());
                assertEquals(1, module.boundInterpreterProgramCount());
                assertEquals(1F, other.<Float>observe(fixture.inputs(),
                    evaluation -> evaluation.evaluate(module.requireFloatProgram(sample()))));
            }
        }
    }

    @Test
    void calledFunctionFailureKeepsOnlyTopAttributionAndOriginalCause(@TempDir Path root) throws Exception {
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(function("bad", "v.text='bad'; v.text.field=1; return 0;")).build();
        var reports = new ArrayList<org.apache.logging.log4j.core.LogEvent>();
        var capture =
            new org.apache.logging.log4j.core.appender.AbstractAppender("interpreter-failure", null, null, true) {
                @Override
                public void append(org.apache.logging.log4j.core.LogEvent event) {
                    if (event.getMessage().getFormattedMessage().startsWith("Molang execution failure:"))
                        reports.add(event.toImmutable());
                }
            };
        var logger = (org.apache.logging.log4j.core.Logger)com.elfmcys.ysm.YesSteveModel.LOGGER;
        capture.start();
        logger.addAppender(capture);
        try (var fixture = new Fixture(root, "fn.bad()", strings); var runtime = fixture.runtime(Map.of())) {
            var program = fixture.module.requireFloatProgram(sample());
            var failure = assertThrows(ExecutionFailure.class,
                () -> runtime.observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertEquals(sample().location(), failure.provenance().programLocation());
            assertEquals(program.binding().programId(), failure.provenance().programId());
            assertInstanceOf(dev.semaweave.interpreter.InterpreterTypeException.class, failure.getCause());
            assertNull(failure.getCause().getCause());
            assertEquals(1, reports.size());
            assertSame(failure.getCause(), reports.get(0).getThrown());
            assertEquals(failure.provenance(), reports.get(0).getMessage().getParameters()[0]);
        } finally {
            logger.removeAppender(capture);
            capture.stop();
        }
    }

    @Test
    void nonReferenceArrowUsesTheInstanceFailureExitAndKeepsOnlyEarlierEffects(@TempDir Path root) throws Exception {
        try (var fixture =
            new Fixture(root, "r.first=7;v.reference=1;v.reference->query.position(0);r.after=9;return 0;",
                StringDataProto.newBuilder().build());
            var runtime = fixture.runtime(Map.of())) {
            var program = fixture.module.requireFloatProgram(sample());
            var failure = assertThrows(ExecutionFailure.class,
                () -> runtime.observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertEquals(ExecutionFailure.Reason.INSTANCE_EXECUTION, failure.reason());
            assertInstanceOf(dev.semaweave.interpreter.InterpreterTypeException.class, failure.getCause());
            assertEquals(sample().location(), failure.provenance().programLocation());
            assertEquals(Map.of("first", 7F, "after", 0F), named(runtime.roamingState().orElseThrow()));
        }
    }

    @Test
    void rejectedInstallationReturnsItsUseAndKeepsInstalledInstances(@TempDir Path root) throws Exception {
        var strings = StringDataProto.newBuilder().addUserFunctions(function("@defer", "r.used=1;")).build();
        try (var fixture = new Fixture(root, "2", strings); var runtime = fixture.runtime(Map.of());
            var other = fixture.runtime(Map.of())) {
            var module = fixture.module;
            var generation = runtime.generation();
            var wrongHandlers = Map.<String, List<EventProgram>>of("player_init",
                List.of(module.requireEventProgram(event("defer", ProgramRole.EVENT_DEFER))));
            try (var invalid = new Fixture(root.resolve("invalid"), "2", strings)) {
                var rejected = TestExpressionModules.store(invalid.module, wrongHandlers);
                assertThrows(IllegalArgumentException.class,
                    () -> runtime.prepare(rejected, TestComponentHolders.linkageScope(), RoamingRole.PRODUCER));
                rejected.close();
                assertEquals(0, rejected.activeUses());
            }
            assertEquals(2, fixture.store.activeUses());
            assertEquals(generation, runtime.generation());
            assertEquals(0, module.boundInterpreterProgramCount());

            var program = module.requireFloatProgram(sample());
            assertEquals(2F, runtime.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertEquals(2F, other.<Float>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
        }
    }

    @Test
    void functionDepthEndsOnlyTheCurrentExpressionAndRestoresTheSourceFrame(@TempDir Path root) throws Exception {
        var strings = StringDataProto.newBuilder().addUserFunctions(function("loop", "return fn.loop();"))
            .addUserFunctions(function("@player_update", "r.after=args[0];")).build();
        try (var fixture = new Fixture(root, "fn.loop()", strings)) {
            var module = fixture.module;
            var handlers = Map.<String, List<EventProgram>>of("player_update",
                List.of(module.requireEventProgram(event("player_update", ProgramRole.EVENT_PLAYER_UPDATE))));
            try (var runtime = fixture.runtime(handlers)) {
                runtime.update(fixture.inputs(), evaluation -> {
                    assertEquals(0F, evaluation.evaluate(module.requireFloatProgram(sample())));
                    assertTrue(evaluation.failure().isEmpty());
                    evaluation.dispatchPlayerUpdate(true);
                    return null;
                });
                assertEquals(Map.of("after", 1F), named(runtime.roamingState().orElseThrow()));
            }
        }
    }

    @Test
    void booleanControllerEntryReturnsTrueAndFalseFromPrivateDynamicState(@TempDir Path root) throws Exception {
        var condition =
            EncodedProgramMapper.toProto(new MolangFrontend().compile("v.toggle=!(v.toggle??false); return v.toggle;",
                MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("test/transition")).program());
        var controllers = AnimationControllerFileProto.newBuilder().addControllers(AnimationControllerProto.newBuilder()
            .setName("main").setDefaultState("default")
            .addStates(StateProto.newBuilder().setName("default")
                .addTransitions(TransitionProto.newBuilder().setDst("next").setCondition(condition).build()).build())
            .addStates(StateProto.newBuilder().setName("next").build()).build()).build();
        try (var fixture = new Fixture(root, "0", StringDataProto.newBuilder().build(), controllers);
            var runtime = fixture.runtime(Map.of())) {
            var location = new ModelProgramInventory.SemanticLocation("player", ExpressionSet.MAIN,
                "controller/main/main/state/default/transition", ProgramRole.CONTROLLER_TRANSITION, "next:0");
            var program = fixture.module.requireBooleanProgram(location);
            assertTrue(runtime.<Boolean>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertFalse(runtime.<Boolean>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertTrue(runtime.<Boolean>observe(fixture.inputs(), evaluation -> evaluation.evaluate(program)));
            assertEquals(1, fixture.module.boundInterpreterProgramCount());
        }
    }

    private static ModelProgramInventory.SemanticLocation sample() {
        return new ModelProgramInventory.SemanticLocation("player", ExpressionSet.MAIN,
            "animation/main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, "");
    }

    private static ModelProgramInventory.SemanticLocation event(String name, ProgramRole role) {
        return new ModelProgramInventory.SemanticLocation("player", ExpressionSet.MAIN, "function/@" + name, role,
            name + ":0");
    }

    private static UserFunctionProto function(String name, String source) throws Exception {
        var encoded = EncodedProgramMapper.toProto(new MolangFrontend()
            .compile(source, MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("test/" + name))
            .program());
        return UserFunctionProto.newBuilder().setName(name).setBody(encoded).build();
    }

    private static final class Fixture implements AutoCloseable {
        private final RenderTargetExpressionModule module;
        private AnimationStore store;
        private final Entity subject;
        private final List<ComponentScope> componentScopes = new ArrayList<>();

        Fixture(Path root, String source, StringDataProto strings) throws Exception {
            this(root, source, strings, null);
        }

        Fixture(Path root, String source, StringDataProto strings, AnimationControllerFileProto controllers)
            throws Exception {
            subject = (Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(ArmorStand.class);
            var host = ExpressionHost.create(HostAvailability.none());
            var cache = track(new AtomicSharedCache(root));
            var factory = new RenderTargetExpressionModuleFactory(track(new ExpressionArtifactStore(root, cache)),
                new AnimationInputStore(root, cache), host, new ExpressionEngineMetrics());
            var encoded = EncodedProgramMapper.toProto(new MolangFrontend()
                .compile(source, MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("test/sample"))
                .program());
            var animation = AnimationProto.newBuilder().setName("idle").setLength(0)
                .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(encoded).build()).build();
            var definition = ModelDataProto.newBuilder().putAnimationFiles("main",
                AnimationFileProto.newBuilder().addAnimations(animation).build());
            if (controllers != null)
                definition.putAnimationControllers("main", controllers);
            module = factory.createSet(() -> false, new Hash256(new byte[Hash256.SIZE]), "player", ExpressionSet.MAIN,
                factory.expressionBinding(), definition.build(), strings, SettingsProto.newBuilder().build(),
                new byte[0], false, ModelResourcePurpose.GUI, CompilerOptions.DEFAULT);
            assertTrue(module.interpreted());
        }

        EntityExpressionRuntime.InvocationInputs inputs() {
            return new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<Entity>() {
                @Override
                public Entity entity() {
                    return subject;
                }
            });
        }

        EntityExpressionRuntime runtime(Map<String, List<EventProgram>> handlers) {
            var runtime = new EntityExpressionRuntime();
            if (store == null)
                store = TestExpressionModules.store(module, handlers);
            var components = store.prepareComponents(TestComponentHolders.owner(subject), null);
            try {
                runtime.replace(store, components, RoamingRole.PRODUCER);
                componentScopes.add(components);
                return runtime;
            } catch (Throwable failure) {
                components.invalidate();
                runtime.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            componentScopes.forEach(ComponentScope::invalidate);
            if (store != null)
                store.close();
            else
                module.close();
        }
    }
}
