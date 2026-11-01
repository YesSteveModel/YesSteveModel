package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationReferenceProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BlendTransitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.InstructionKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StateProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.TransitionProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigLabelProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButtonProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.BuiltinType;
import dev.semaweave.EntryResult;
import dev.semaweave.ProgramKey;
import dev.semaweave.ProgramShape;
import dev.semaweave.TopLevelEntrySignature;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.ArtifactCodecs;
import dev.semaweave.compilation.ArtifactIdentityMismatchException;
import dev.semaweave.compilation.CompilationUnit;
import dev.semaweave.compilation.CompiledArtifactIdentity;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.DefaultExpressionAotCompiler;
import dev.semaweave.compilation.DiagnosticLocation;
import dev.semaweave.compilation.EngineMetrics;
import dev.semaweave.compilation.ExpectedArtifactIdentity;
import dev.semaweave.compilation.ProgramSource;
import dev.semaweave.compilation.ReplacementResult;
import dev.semaweave.compilation.SemanticFailureReason;
import dev.semaweave.compilation.TaskContext;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ReadOnlyBytes;
import us.hebi.quickbuf.ProtoSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Separate-process oracle for a complete target cold/write/warm-load lifecycle. */
public final class ExpressionArtifactWarmProcessFixture {
    private static final String TARGET = "player";
    private static final TestEntity SUBJECT;
    static {
        try {
            SharedConstants.tryDetectVersion();
            var gate = Bootstrap.class.getDeclaredField("isBootstrapped");
            gate.setAccessible(true);
            boolean previous = gate.getBoolean(null);
            gate.setBoolean(null, true);
            try {
                BuiltInRegistries.REGISTRY.size();
                Class.forName(TestEntity.class.getName(), true,
                    ExpressionArtifactWarmProcessFixture.class.getClassLoader());
                var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                var unsafe = (Unsafe)unsafeField.get(null);
                SUBJECT = (TestEntity)unsafe.allocateInstance(TestEntity.class);
                var position = Entity.class.getDeclaredField("position");
                position.setAccessible(true);
                position.set(SUBJECT, Vec3.ZERO);
            } finally {
                gate.setBoolean(null, previous);
            }
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
    private static final InvocationInputs<Entity> INPUTS = new InvocationInputs<>() {
        @Override
        public Entity entity() {
            return SUBJECT;
        }

        @Override
        public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {}
    };

    private ExpressionArtifactWarmProcessFixture() {}

    public static void main(String[] arguments) throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected cache root and source");
        }
        var expectedSource = ExpressionArtifactStore.Source.valueOf(arguments[1]);
        var cacheRoot = Path.of(arguments[0]);
        var metrics = new ExpressionEngineMetrics();
        var host = ExpressionHost.create(HostAvailability.all());
        var store = new ExpressionArtifactStore(cacheRoot, new AtomicSharedCache(cacheRoot));
        var factory = new RenderTargetExpressionModuleFactory(store, host, metrics);
        var input = expectedSource == ExpressionArtifactStore.Source.COLD ? createAndPersistInput(cacheRoot)
            : readInput(cacheRoot);
        if (expectedSource == ExpressionArtifactStore.Source.COLD) {
            createAndPersistIdentityArtifact(cacheRoot, host);
        } else {
            verifyOldIdentityRejection(cacheRoot, host);
        }

        var createStarted = System.nanoTime();
        try (var module = factory.create(() -> false, input.model(), TARGET, "main", input.profile(), false,
            input.definition(), input.strings(), input.settings(), input.configBytes())) {
            var createNanos = System.nanoTime() - createStarted;
            require(module.source() == expectedSource, "expected " + expectedSource + " but loaded " + module.source());
            require(module.programs().keySet().equals(expectedRoutes()),
                "complete target route set mismatch: " + module.programs().keySet());
            verifyStageBoundary(metrics, expectedSource);
            var observation = executeEveryRoute(module);
            verifyObservation(cacheRoot, expectedSource, observation.canonical());
            printEvidence(expectedSource, createNanos, observation, module, metrics);
        }
        store.awaitPersistence();
    }

    private static void verifyStageBoundary(ExpressionEngineMetrics metrics, ExpressionArtifactStore.Source source) {
        var snapshot = metrics.snapshot();
        if (source == ExpressionArtifactStore.Source.COLD) {
            require(snapshot.stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS) == 1, "cold semantic analysis count");
            require(snapshot.stageCalls(EngineMetrics.Stage.SOURCE_GENERATION) == 1, "cold source generation count");
            require(snapshot.stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION) == 1,
                "cold backend compilation count");
            require(snapshot.portableProgramDecodes() == 1, "cold inventory decode count");
            return;
        }
        require(snapshot.stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS) == 0, "warm semantic analysis must not run");
        require(snapshot.stageCalls(EngineMetrics.Stage.SOURCE_GENERATION) == 0, "warm source generation must not run");
        require(snapshot.stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION) == 0,
            "warm backend compilation must not run");
        require(snapshot.portableProgramDecodes() == 0, "warm inventory decode must not run");
        require(snapshot.stageCalls(EngineMetrics.Stage.MODULE_VERIFICATION) > 0, "warm module verification must run");
    }

    private static ExecutionObservation executeEveryRoute(RenderTargetExpressionModule module) {
        var runtime = new EntityExpressionRuntime();
        var values = new ArrayList<String>();
        var effects = new ArrayList<String>();
        var owner = com.elfmcys.ysm.expression.host.TestComponentHolders.owner(SUBJECT);
        var controller = new com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController<>(owner, "warm", 0,
            event -> com.elfmcys.ysm.geckolib3.core.PlayState.CONTINUE) {
            public void setAnimation(String name, com.elfmcys.ysm.geckolib3.core.builder.LoopType loop) {
                effects.add("controller:" + name + ":" + loop);
            }
        };
        var handlers = Map.of("player_init",
            List.of(module
                .requireEventProgram(route("function/@player_init", ProgramRole.EVENT_PLAYER_INIT, "player_init:0"))),
            "player_update",
            List.of(module.requireEventProgram(
                route("function/@player_update", ProgramRole.EVENT_PLAYER_UPDATE, "player_update:0"))),
            "sync", List.of(module.requireEventProgram(route("function/@sync", ProgramRole.EVENT_SYNC, "sync:0"))),
            "defer", List.of(module.requireEventProgram(route("function/@defer", ProgramRole.EVENT_DEFER, "defer:0"))));
        var components = module.prepareComponents(owner, null);
        runtime.replace(module, handlers, components);
        var inputs = new EntityExpressionRuntime.InvocationInputs(INPUTS, (commands, provenance) -> {
            if (!commands.isEmpty())
                require(effects.isEmpty(), "effects escaped before the round committed");
            return () -> commands.forEach(PreparedEffect::commit);
        }, () -> false);
        long evaluationNanos;
        try {
            var evaluationStarted = System.nanoTime();
            runtime.update(inputs, evaluation -> {
                float doubled = evaluation.evaluate(module
                    .requireFloatProgram(route("animation/main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, "")));
                require(doubled == 8.0f, "animation and user-function result");
                values.add("double=" + doubled);
                float component = evaluation.evaluate(module.requireFloatProgram(
                    route("animation/main/idle/bone/root/position/0/pre/0", ProgramRole.ANIMATION_SAMPLE, "")));
                require(component == 2.5f, "animation component result");
                values.add("component=" + component);
                float cleared = evaluation.evaluate(module.requireFloatProgram(
                    route("animation/main/object_clear/blend-weight", ProgramRole.ANIMATION_SAMPLE, "")));
                float present = evaluation.evaluate(module.requireFloatProgram(
                    route("animation/main/object_present/blend-weight", ProgramRole.ANIMATION_SAMPLE, "")));
                float invalid = evaluation.evaluate(module.requireFloatProgram(
                    route("animation/main/invalid_sentinel/blend-weight", ProgramRole.ANIMATION_SAMPLE, "")));
                require(cleared == 11.0f, "cleared object replacement result");
                require(present == 12.0f, "present object result");
                require(invalid == 0.0f, "invalid sentinel fallback result");
                values.add("object-clear=" + cleared);
                values.add("object-present=" + present);
                values.add("invalid-sentinel=" + invalid);
                evaluation.setControllerContext(new com.elfmcys.ysm.expression.host.ControllerExecutionState(false,
                    controller));
                evaluation.execute(module.requireVoidProgram(
                    route("animation/main/idle/instruction/0", ProgramRole.ANIMATION_INSTRUCTION, "")));
                require(effects.isEmpty(), "prepared controller effects became visible inside the body");
                require(evaluation.evaluate(module.requireBooleanProgram(
                    route("controller/main/main/state/default/animation", ProgramRole.CONTROLLER_ANIMATION, "idle:0"))),
                    "controller animation result");
                require(!evaluation
                    .evaluate(module.requireBooleanProgram(route("controller/main/main/state/default/transition",
                        ProgramRole.CONTROLLER_TRANSITION, "next:0"))),
                    "controller transition result");
                evaluation.execute(module.requireVoidProgram(
                    route("controller/main/main/state/default/on-entry", ProgramRole.CONTROLLER_ON_ENTRY, "")));
                evaluation.execute(module.requireVoidProgram(
                    route("controller/main/main/state/default/on-exit", ProgramRole.CONTROLLER_ON_EXIT, "")));
                evaluation.dispatchPlayerInit();
                evaluation.dispatchPlayerUpdate(true);
                evaluation.dispatchSync(EventArguments.sync(1));
                evaluation.dispatchDeferred(EventArguments.deferredFloats(4));
                require(
                    evaluation.codedController(module
                        .requireFloatProgram(route("function/@player_ctrl_main", ProgramRole.EVENT_CODED_CONTROLLER,
                            "player_ctrl_main:0"))) == com.elfmcys.ysm.geckolib3.core.PlayState.CONTINUE,
                    "coded-controller result");
                return null;
            });
            evaluationNanos = System.nanoTime() - evaluationStarted;
            var config = new ConfigActionRoute("mode", 0);
            require(runtime.readConfigFloat(inputs, runtime.generation(), config) == 3.5f, "config read result");
            values.add("config=3.5");
            runtime.writeConfigFloat(inputs, runtime.generation(), config, 4.0f);
            runtime.executeConfigLabel(inputs, runtime.generation(), config, "next", 0);
        } finally {
            runtime.close();
        }
        require(effects.equals(List.of("controller:first:LOOP", "controller:second:HOLD_ON_LAST_FRAME")),
            "committed effect payload/order " + effects);
        var replacements = module.compilationReport().replacements();
        require(replacements.size() == 1, "expected one invalid-sentinel replacement: " + replacements);
        var replacement = replacements.get(0);
        require(replacement.reason() == SemanticFailureReason.INVALID_MEMBER, "invalid sentinel reason");
        require(replacement.result() == ReplacementResult.F32_ZERO, "invalid sentinel result");
        require(
            module.programLocations().get(replacement.programKey())
                .equals(route("animation/main/invalid_sentinel/blend-weight", ProgramRole.ANIMATION_SAMPLE, "")),
            "invalid sentinel replacement location");
        String replacementObservation = replacement.reason() + "|" + replacement.result() + "|" + replacement.nodePath()
            + "|" + replacement.message();
        var canonical = String.join("\n", "identity=" + module.identity().cacheKey(), "values=" + values,
            "effects=" + effects, "replacement=" + replacementObservation) + "\n";
        return new ExecutionObservation(canonical, evaluationNanos);
    }

    private static void printEvidence(ExpressionArtifactStore.Source source, long createNanos,
        ExecutionObservation observation, RenderTargetExpressionModule module, ExpressionEngineMetrics engineMetrics) {
        var compilation = module.compilationReport().metrics();
        var snapshot = engineMetrics.snapshot();
        var stageNanos = snapshot.stageNanos();
        var stages = new ArrayList<String>();
        for (var stage : EngineMetrics.Stage.values()) {
            stages.add(stage.name() + ':' + stageNanos[stage.ordinal()]);
        }
        System.out
            .println("EXPRESSION_EVIDENCE" + " source=" + source + " create_nanos=" + createNanos + " evaluation_nanos="
                + observation.evaluationNanos() + " programs=" + compilation.programs() + " generated_classes="
                + compilation.generatedClasses() + " generated_class_bytes=" + compilation.generatedClassBytes()
                + " artifact_bytes=" + compilation.artifactBytes() + " stage_nanos=" + String.join(",", stages));
    }

    private record ExecutionObservation(String canonical, long evaluationNanos) {
    }

    private static void verifyObservation(Path root, ExpressionArtifactStore.Source source, String observation)
        throws Exception {
        var path = root.resolve("fixture").resolve("observation.txt");
        if (source == ExpressionArtifactStore.Source.COLD) {
            Files.writeString(path, observation);
            return;
        }
        require(Files.readString(path).equals(observation), "cold and warm observations differ");
    }

    private static Set<ModelProgramInventory.SemanticLocation> expectedRoutes() {
        return Set.of(route("animation/main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""),
            route("animation/main/idle/bone/root/position/0/pre/0", ProgramRole.ANIMATION_SAMPLE, ""),
            route("animation/main/object_clear/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""),
            route("animation/main/object_present/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""),
            route("animation/main/invalid_sentinel/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""),
            route("animation/main/idle/instruction/0", ProgramRole.ANIMATION_INSTRUCTION, ""),
            route("controller/main/main/state/default/animation", ProgramRole.CONTROLLER_ANIMATION, "idle:0"),
            route("controller/main/main/state/default/transition", ProgramRole.CONTROLLER_TRANSITION, "next:0"),
            route("controller/main/main/state/default/on-entry", ProgramRole.CONTROLLER_ON_ENTRY, ""),
            route("controller/main/main/state/default/on-exit", ProgramRole.CONTROLLER_ON_EXIT, ""),
            route("function/double", ProgramRole.USER_FUNCTION, "callable"),
            route("function/clear_object", ProgramRole.USER_FUNCTION, "callable"),
            route("function/present_object", ProgramRole.USER_FUNCTION, "callable"),
            route("function/@player_init", ProgramRole.EVENT_PLAYER_INIT, "player_init:0"),
            route("function/@player_update", ProgramRole.EVENT_PLAYER_UPDATE, "player_update:0"),
            route("function/@sync", ProgramRole.EVENT_SYNC, "sync:0"),
            route("function/@defer", ProgramRole.EVENT_DEFER, "defer:0"),
            route("function/@player_ctrl_main", ProgramRole.EVENT_CODED_CONTROLLER, "player_ctrl_main:0"),
            route("config/mode/0", ProgramRole.CONFIG_READ_F32, "read"),
            route("config/mode/0", ProgramRole.CONFIG_WRITE_F32, "write"),
            route("config/mode/0/label", ProgramRole.CONFIG_LABEL_ACTION, "next:0"));
    }

    private static ModelProgramInventory.SemanticLocation route(String location, ProgramRole role,
        String discriminator) {
        return new ModelProgramInventory.SemanticLocation(TARGET, location, role, discriminator);
    }

    private static Input createAndPersistInput(Path root) throws Exception {
        var animation = AnimationProto.newBuilder().setName("idle").setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE)
            .setBlendWeight(value("fn.double(4)"))
            .addBoneAnimations(BoneAnimationProto.newBuilder().setBoneName("root")
                .addPosition(BoneKeyFrameProto.newBuilder().setStartTick(0)
                    .setEasingType(com.elfmcys.ysm.proto.mixel.asset.model.data.EasingType.NONE).addPre(value("2.5"))
                    .build())
                .build())
            .addInstructionKeyframes(InstructionKeyFrameProto.newBuilder().setStartTick(0)
                .setPrograms(program("ctrl.set_animation('first',10);ctrl.set_animation('second',12);ysm.sync(0,1)",
                    MolangFrontend.SourceMode.INSTRUCTION)).build())
            .build();
        var objectClear = animation("object_clear", "((fn.clear_object()??'replacement')=='replacement')?11:0");
        var objectPresent = animation("object_present", "((fn.present_object()??'replacement')=='value')?12:0");
        var invalidSentinel = animation("invalid_sentinel", "v.object.missing");
        var state = StateProto.newBuilder().setName("default")
            .setBlendTransition(BlendTransitionProto.newBuilder().build())
            .addAnimations(AnimationReferenceProto.newBuilder().setName("idle").setCondition(program("true")).build())
            .addTransitions(TransitionProto.newBuilder().setDst("next").setCondition(program("false")).build())
            .setOnEntry(program("v.entered=1", MolangFrontend.SourceMode.INSTRUCTION))
            .setOnExit(program("v.entered=0", MolangFrontend.SourceMode.INSTRUCTION)).build();
        var controller =
            AnimationControllerProto.newBuilder().setName("main").setDefaultState("default").addStates(state).build();
        var model = ModelDataProto.newBuilder()
            .putAnimationFiles("main",
                AnimationFileProto.newBuilder().addAnimations(animation).addAnimations(objectClear)
                    .addAnimations(objectPresent).addAnimations(invalidSentinel).build())
            .putAnimationControllers("main",
                AnimationControllerFileProto.newBuilder().addControllers(controller).build())
            .build();
        var strings = StringDataProto.newBuilder().addUserFunctions(function("double", "return args[0]*2"))
            .addUserFunctions(function("clear_object", "v.object='value';v.object=0;return v.object"))
            .addUserFunctions(function("present_object", "v.object='value';return v.object"))
            .addUserFunctions(function("@player_init", "v.initialized=1"))
            .addUserFunctions(function("@player_update", "v.updated=args[0]"))
            .addUserFunctions(function("@sync", "v.synced=args[0]"))
            .addUserFunctions(function("@defer", "v.deferred=1"))
            .addUserFunctions(function("@player_ctrl_main", "return 2")).build();
        var form = ConfigFormsProto.newBuilder().setType("radio").setTitle("").setDescription("")
            .setReadProgram(program("3.5"))
            .setWriteProgram(program("v.config=t.value", MolangFrontend.SourceMode.CONFIG_WRITE)).setStep(0).setMin(0)
            .setMax(0).addLabels(ConfigLabelProto.newBuilder().setName("next")
                .setActionProgram(program("v.config=5", MolangFrontend.SourceMode.INSTRUCTION)).build())
            .build();
        var settings = SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
            .setDisablePreviewRotation(false).addExtraAnimationButtons(ExtraAnimationButtonProto.newBuilder()
                .setId("mode").setName("").setSound("").addConfigForms(form).build())
            .build();
        var input = new Input(new ModelFileIdentity(hash(1), hash(2)), new BakeProfile("warm-process"), model, strings,
            settings, ProtoUtil.serializeToArray(settings));
        var fixture = root.resolve("fixture");
        Files.createDirectories(fixture);
        Files.write(fixture.resolve("model.pb"), ProtoUtil.serializeToArray(model));
        Files.write(fixture.resolve("strings.pb"), ProtoUtil.serializeToArray(strings));
        Files.write(fixture.resolve("settings.pb"), input.configBytes());
        return input;
    }

    private static AnimationProto animation(String name, String expression) throws Exception {
        return AnimationProto.newBuilder().setName(name).setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE).setBlendWeight(value(expression))
            .build();
    }

    private static void createAndPersistIdentityArtifact(Path root, ExpressionHost host) throws Exception {
        var backend = new JaninoJavaCompilerBackend();
        var unit = identityUnit(host);
        var artifact = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE, unit,
            host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, host.expressionBinding().bindingPlan(),
            ExpressionIdentityHash.SHA_256);
        var encoded = ArtifactCodecs.v1().encode(artifact, ExpressionIdentityHash.SHA_256);
        Files.write(root.resolve("fixture").resolve("identity-artifact.swa"), encoded.toByteArray());
    }

    private static void verifyOldIdentityRejection(Path root, ExpressionHost host) throws Exception {
        var backend = new JaninoJavaCompilerBackend();
        var current = ExpectedArtifactIdentity.create(identityUnit(host), host.hostAbi(), host.registry(),
            CompilerOptions.DEFAULT, host.expressionBinding().bindingPlan(), backend.descriptor(), ExpressionIdentityHash.SHA_256);
        var identity = current.identity();
        var legacyIdentity = new CompiledArtifactIdentity(identity.hashAlgorithm(), identity.input(),
            "portable-program-v1-semantics-v6", identity.runtimeAbi(), identity.codegenAbi(), identity.javaFeature(),
            identity.classfileMajor(), identity.sourceGeneratorId(), identity.sourceGeneratorVersion(),
            identity.backend(), identity.artifactFingerprint());
        var legacy = new ExpectedArtifactIdentity(legacyIdentity, current.compilerLimits(), current.registry());
        var encoded =
            ReadOnlyBytes.copyOf(Files.readAllBytes(root.resolve("fixture").resolve("identity-artifact.swa")));
        try {
            ArtifactCodecs.v1().decodeAndVerify(encoded, legacy, ExpressionIdentityHash.SHA_256);
            throw new AssertionError("legacy semantic identity was accepted");
        } catch (ArtifactIdentityMismatchException expected) {
            require(expected.reason() == ArtifactIdentityMismatchException.Reason.PROGRAM_SEMANTIC_ABI,
                "legacy semantic identity rejection reason");
        }
        for (String dimension : List.of("effect", "binding", "dependencies")) {
            var changed = com.elfmcys.ysm.expression.host.ArtifactContractVariants.changed(host, dimension);
            var expected = ExpectedArtifactIdentity.create(identityUnit(changed), changed.hostAbi(), changed.registry(),
                CompilerOptions.DEFAULT, changed.expressionBinding().bindingPlan(), backend.descriptor(),
                ExpressionIdentityHash.SHA_256);
            try {
                ArtifactCodecs.v1().decodeAndVerify(encoded, expected, ExpressionIdentityHash.SHA_256);
                throw new AssertionError("old artifact accepted after " + dimension + " changed");
            } catch (ArtifactIdentityMismatchException rejected) {
                System.out.println("DECLARATION_IDENTITY_REJECTION dimension=" + dimension + " reason=" + rejected.reason());
            }
            var rebuilt = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE,
                identityUnit(changed), changed.hostAbi(), changed.registry(), CompilerOptions.DEFAULT,
                changed.expressionBinding().bindingPlan(), ExpressionIdentityHash.SHA_256);
            ArtifactCodecs.v1().decodeAndVerify(ArtifactCodecs.v1().encode(rebuilt, ExpressionIdentityHash.SHA_256),
                expected, ExpressionIdentityHash.SHA_256);
        }
    }

    private static CompilationUnit identityUnit(ExpressionHost host) throws Exception {
        var codec = PortableProgramCodecs.v1();
        var encoded = new MolangFrontend().compile("math.abs(-2)", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("warm-process/identity")).program();
        var source = new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {1})), encoded,
            new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
            Optional.empty(), new DiagnosticLocation("warm-process/identity"));
        return CompilationUnit.builder(host.compilationPolicy(), codec, ExpressionIdentityHash.SHA_256)
            .addProgram(source).build();
    }

    private static Input readInput(Path root) throws Exception {
        var fixture = root.resolve("fixture");
        var modelBytes = Files.readAllBytes(fixture.resolve("model.pb"));
        var stringsBytes = Files.readAllBytes(fixture.resolve("strings.pb"));
        var settingsBytes = Files.readAllBytes(fixture.resolve("settings.pb"));
        return new Input(new ModelFileIdentity(hash(1), hash(2)), new BakeProfile("warm-process"),
            ModelDataProto.parseFrom(ProtoSource.newInstance(modelBytes)),
            StringDataProto.parseFrom(ProtoSource.newInstance(stringsBytes)),
            SettingsProto.parseFrom(ProtoSource.newInstance(settingsBytes)), settingsBytes);
    }

    private static ExpressionValueProto value(String source) throws Exception {
        return ExpressionValueProto.newBuilder().setProgram(program(source)).build();
    }

    private static UserFunctionProto function(String name, String source) throws Exception {
        return UserFunctionProto.newBuilder().setName(name)
            .setBody(program(source, MolangFrontend.SourceMode.USER_FUNCTION)).build();
    }

    private static ProgramProto program(String source) throws Exception {
        return program(source, MolangFrontend.SourceMode.EXPRESSION);
    }

    private static ProgramProto program(String source, MolangFrontend.SourceMode mode) throws Exception {
        return EncodedProgramMapper.toProto(new MolangFrontend()
            .compile(source, mode, new MolangFrontend.SourceLocation("warm-process/" + source)).program());
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte)marker);
        return new Hash256(bytes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Input(ModelFileIdentity model, BakeProfile profile, ModelDataProto definition,
        StringDataProto strings, SettingsProto settings, byte[] configBytes) {
    }

    private static final class TestEntity extends Entity {
        private TestEntity() {
            super(EntityType.ARMOR_STAND, null);
        }

        @Override
        protected void defineSynchedData() {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
