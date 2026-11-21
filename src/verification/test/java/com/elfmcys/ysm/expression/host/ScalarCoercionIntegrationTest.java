package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.render.BakedAnimationCache;
import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.*;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.*;
import dev.semaweave.compilation.*;
import dev.semaweave.program.ReadOnlyBytes;
import it.unimi.dsi.fastutil.floats.FloatList;
import java.lang.invoke.MethodType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;
import static org.junit.jupiter.api.Assertions.*;

/** The same fixture also runs in isolated old/current JVMs without changing its declarations or source. */
public final class ScalarCoercionIntegrationTest {
    private static final List<String> TRACE = new ArrayList<>();
    private static int sourceCalls;
    private static Entity currentSubject;

    public record Point(float x) {
    }

    public static boolean pointEqual(Point left, Point right) {
        return left.x() == right.x();
    }

    public static float pointRead_x(Point value) {
        return value.x();
    }

    public static boolean numbersEqual(FloatList left, FloatList right) {
        if (left.size() != right.size())
            return false;
        for (int i = 0; i < left.size(); i++)
            if (left.getFloat(i) != right.getFloat(i))
                return false;
        return true;
    }

    public static int numbersLength(FloatList value) {
        return value.size();
    }

    public static float numbersRead(FloatList value, int index) {
        return value.getFloat(index);
    }

    public static FloatList numbersZero() {
        return FloatList.of();
    }

    public static FloatList empty(MolangContext context) {
        sourceCalls++;
        return FloatList.of();
    }

    public static FloatList full(MolangContext context) {
        sourceCalls++;
        return FloatList.of(0);
    }

    public static Point point(MolangContext context) {
        sourceCalls++;
        return new Point(0);
    }

    public static float argument(MolangContext context) {
        TRACE.add("argument");
        return 2;
    }

    public static float fail(MolangContext context) {
        TRACE.add("fail");
        throw new IllegalStateException("argument failure");
    }

    public static float accept(MolangContext context, float number, boolean flag, int count) {
        TRACE.add("fixed:" + number + ":" + flag + ":" + count);
        return number + (flag ? 10 : 0) + count;
    }

    public static int optional(MolangContext context, int argc, int value) {
        TRACE.add("optional:" + argc + ":" + value);
        return value == 16777217 ? 17 : value;
    }

    public static float tail(MolangContext context, int argc, float first, float[] rest) {
        TRACE.add("tail:" + argc + ":" + first + ":" + Arrays.toString(rest));
        return first + rest.length;
    }

    public static float action(MolangContext context, float value) {
        TRACE.add("action:" + value);
        return value;
    }

    public static float restricted(MolangContext context, float value) {
        TRACE.add("restricted:" + value);
        return value;
    }

    @Test
    void frontendScalarsKeepCarriersPresenceAndRecoveryAcrossThreePaths(@TempDir Path root) throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var cases = new LinkedHashMap<String, Float>();
        cases.put("v.mix=true;v.mix=2;return v.mix;", 2f);
        cases.put("t.mix=true;t.mix=2;return t.mix;", 2f);
        cases.put("return scalar.empty() ? 13 : 29;", 29f);
        cases.put("return (scalar.empty() ?? scalar.full()) ? 13 : 29;", 29f);
        cases.put("return scalar.accept(scalar.empty(),scalar.full(),scalar.point());", 11f);
        cases.put("return scalar.optional();", 17f);
        cases.put("return scalar.optional(null);", 0f);
        cases.put("return scalar.optional(-3.9);", -3f);
        cases.put("return scalar.tail('0',scalar.empty(),scalar.point());", 3f);
        cases.put("return scalar.tail('0');", 1f);
        cases.put("return (scalar.full()==2)+0;", 0f);
        cases.put("return (v.missing+0) ?? 8;", 0f);
        var model = model(new ArrayList<>(cases.keySet()));
        var host = host();
        var workers = Executors.newSingleThreadExecutor();
        try {
            List<String> coldTrace = null;
            for (int pass = 0; pass < 2; pass++) {
                var store = store(root);
                var metrics = new ExpressionEngineMetrics();
                var module = create(store, metrics, host, model);
                try (var animations = TestExpressionModules.store(module, Map.of());
                    var runtime = new EntityExpressionRuntime()) {
                    assertEquals(pass == 0 ? ExpressionArtifactStore.Source.COLD : ExpressionArtifactStore.Source.DISK,
                        module.source());
                    assertTrue(module.compilationReport().replacements().isEmpty());
                    assertTrue(module.compilationReport().localRecoveries().isEmpty());
                    runtime.replace(animations, scope(net.minecraft.world.entity.decoration.ArmorStand.class));
                    TRACE.clear();
                    sourceCalls = 0;
                    int index = 0;
                    for (var entry : cases.entrySet()) {
                        var program = module.requireFloatProgram(location(index++));
                        assertEquals(entry.getValue(), runtime.observe(inputs(), e -> e.evaluate(program)),
                            entry.getKey());
                    }
                    assertEquals(8, sourceCalls);
                    assertEquals(List.of("fixed:0.0:true:1", "optional:0:16777217", "optional:1:0", "optional:1:-3",
                        "tail:3:1.0:[0.0, 1.0]", "tail:1:1.0:[]"), TRACE);
                    if (pass == 0)
                        coldTrace = List.copyOf(TRACE);
                    else
                        assertEquals(coldTrace, TRACE);
                    TRACE.clear();
                    sourceCalls = 0;
                    for (var entry : cases.entrySet()) {
                        try (var request = runtime.prepareTransient(runtime.generation(), entry.getKey(),
                            TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                            "scalar/transient")) {
                            try (var session = workers.submit(() -> request.compile(() -> false)).get()) {
                                runtime.attachTransient(request, session);
                                assertEquals(entry.getValue(), runtime.executeTransient(inputs(), session),
                                    entry.getKey());
                            }
                        }
                    }
                    assertEquals(coldTrace, TRACE);
                    assertEquals(8, sourceCalls);
                    assertEquals(pass == 0 ? 1 : 0,
                        metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
                    try (var request = runtime.prepareTransient(runtime.generation(),
                        "v.new_slot=scalar.full();return 1;", TransientExpressionSession.Privilege.LOCAL_WATCH,
                        TransientExpressionSession.Purpose.WATCH, "scalar/widen")) {
                        assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> workers.submit(() -> request.compile(() -> false)).get());
                    }
                    assertEquals(8, sourceCalls);
                }
                store.awaitPersistence();
            }
            var conflict = model(List.of("v.conflict=scalar.full();v.conflict=2;return 9;"));
            var rejected = assertThrows(com.elfmcys.ysm.format.AssetLoadException.class,
                () -> create(store(root.resolve("conflict")), new ExpressionEngineMetrics(), host, conflict));
            assertEquals(SemanticFailureReason.TYPE_MISMATCH,
                assertInstanceOf(SemanticCompilationException.class, rejected.getCause()).reason());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void hostActionAndSubjectGatesRejectBeforeScalarArguments(@TempDir Path root) throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var host = host();
        var model = model(List.of("scalar.action(scalar.argument());", "scalar.restricted(scalar.argument());",
            "scalar.action(scalar.fail());"));
        var module = create(store(root), new ExpressionEngineMetrics(), host, model);
        try (var animations = TestExpressionModules.store(module, Map.of());
            var runtime = new EntityExpressionRuntime()) {
            runtime.replace(animations, scope(net.minecraft.world.entity.projectile.Snowball.class));
            TRACE.clear();
            runtime.observe(inputs(), e -> e.evaluate(module.requireFloatProgram(location(0))));
            assertEquals(List.of(), TRACE);
            runtime.observe(inputs(), e -> e.evaluate(module.requireFloatProgram(location(1))));
            assertEquals(List.of(), TRACE);
            try (var session = runtime.compileTransient(runtime.generation(),
                "return scalar.action(scalar.argument());", TransientExpressionSession.Privilege.LOCAL_EXECUTE,
                TransientExpressionSession.Purpose.ONE_SHOT, "scalar/allowed", () -> false)) {
                assertEquals(2f, runtime.executeTransient(inputs(), session));
                assertEquals(List.of("argument", "action:2.0"), TRACE);
            }
            TRACE.clear();
            try (var session = runtime.compileTransient(runtime.generation(),
                "scalar.action(scalar.fail());scalar.argument();", TransientExpressionSession.Privilege.LOCAL_EXECUTE,
                TransientExpressionSession.Purpose.ONE_SHOT, "scalar/failing-argument", () -> false)) {
                var failure = assertThrows(ExecutionFailure.class, () -> runtime.executeTransient(inputs(), session));
                assertEquals(ExecutionFailure.Reason.INSTANCE_EXECUTION, failure.reason());
                assertEquals("scalar/failing-argument", failure.provenance().programLocation());
                assertEquals("argument failure", failure.getCause().getMessage());
                assertEquals(List.of("fail"), TRACE);
            }
            TRACE.clear();
            try (
                var budget = runtime.compileTransient(runtime.generation(), "loop(1000000,0);scalar.argument();",
                    TransientExpressionSession.Privilege.LOCAL_EXECUTE, TransientExpressionSession.Purpose.ONE_SHOT,
                    "scalar/budget", () -> false);
                var next = runtime.compileTransient(runtime.generation(), "return scalar.argument();",
                    TransientExpressionSession.Privilege.LOCAL_EXECUTE, TransientExpressionSession.Purpose.ONE_SHOT,
                    "scalar/next", () -> false)) {
                assertEquals(2f, runtime.executeTransient(inputs(), budget));
                assertEquals(List.of("argument"), TRACE);
                assertEquals(2f, runtime.executeTransient(inputs(), next));
                assertEquals(List.of("argument", "argument"), TRACE);
            }
        }
    }

    @Test
    void realDeferAndSoundProvidersReceiveTheFinalScalarAndStringPlans(@TempDir Path root) throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var defer = EncodedProgramMapper.toProto(new MolangFrontend().compile("scalar.accept(args[0],args[1],args[2]);",
            MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("scalar/@defer")).program());
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("@defer").setBody(defer).build()).build();
        var host = host();
        var sounds = new ArrayList<ModelSoundId>();
        var manager = new com.elfmcys.ysm.client.sound.instance.SoundInstanceManager() {
            @Override
            public boolean playSound(AnimatableEntity<?> owner, ModelSoundId id, String sound, boolean force,
                java.util.function.Consumer<com.elfmcys.ysm.client.sound.instance.SoundControl> configure) {
                sounds.add(id);
                return true;
            }

            @Override
            public boolean stopPlayingSound(ModelSoundId id) {
                sounds.add(id);
                return true;
            }
        };
        var animation = new AnimationExecutionState();
        var managerField = AnimationExecutionState.class.getDeclaredField("soundManager");
        managerField.setAccessible(true);
        managerField.set(animation, manager);
        var module = create(store(root), new ExpressionEngineMetrics(), host, model(List.of("1")), strings);
        try (
            var animations = new com.elfmcys.ysm.model.resource.client.AnimationStore(module,
                com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.FULL);
            var runtime = new EntityExpressionRuntime()) {
            try (var preparation = animations.prepare()) {
                var handler = preparation.requireEventProgram(new ModelProgramInventory.SemanticLocation("player",
                    "function/@defer", ProgramRole.EVENT_DEFER, "defer:0"));
                preparation.initialize(com.elfmcys.ysm.model.resource.client.AnimationStore.Index.eager(Map.of()), null,
                    Map.of(), Map.of(), Map.of("defer", List.of(handler)));
            }
            runtime.replace(animations, scope(net.minecraft.world.entity.decoration.ArmorStand.class));
            try (var session = runtime.compileTransient(runtime.generation(), """
                ysm.defer('0',scalar.empty(),scalar.point());
                ysm.play_sound('0','voice',false);
                ysm.stop_sound('0');
                ysm.play_sound(scalar.point(),'voice',false);
                ysm.play_sound(null,'voice');
                ysm.stop_sound(null);
                """, TransientExpressionSession.Privilege.LOCAL_EXECUTE, TransientExpressionSession.Purpose.ONE_SHOT,
                "scalar/domain", () -> false)) {
                TRACE.clear();
                sourceCalls = 0;
                runtime.action(inputs(), e -> {
                    e.setAnimationContext(animation);
                    e.executeTransient(session);
                    return null;
                });
                assertEquals(List.of("fixed:1.0:false:1"), TRACE);
                assertEquals(3, sourceCalls);
                assertEquals(List.of(ModelSoundId.string("0"), ModelSoundId.string("0"), ModelSoundId.numeric(1),
                    ModelSoundId.numeric(0), ModelSoundId.string("")), sounds);
            }
        }
    }

    public static ExpressionHost host() {
        var base = ExpressionHost.create(HostAvailability.none());
        var members = new ArrayList<LinkedDeclaration.Member>();
        for (String name : List.of("empty", "full", "point", "argument", "fail", "accept", "optional", "tail", "action",
            "restricted")) {
            var method = Arrays.stream(ScalarCoercionIntegrationTest.class.getMethods())
                .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            var parameters = method.getParameterTypes();
            boolean optional = name.equals("optional"), tail = name.equals("tail");
            var fixed = new ArrayList<DeclarationSchema.Value>();
            for (int i = optional || tail ? 2 : 1; i < parameters.length - (tail ? 1 : 0); i++)
                fixed.add(value(parameters[i]));
            var schema = new DeclarationSchema.Member("scalar", name, name,
                MethodType.methodType(method.getReturnType(), parameters).toMethodDescriptorString(),
                "m" + members.size(),
                (name.equals("restricted") ? net.minecraft.world.entity.decoration.ArmorStand.class : Entity.class)
                    .getName(),
                true, true, name.equals("action") ? "ACTION" : "CONTEXT", "SUBJECT", optional ? 0 : fixed.size(),
                tail ? 255 : fixed.size(), fixed, optional ? List.of("16777217") : List.of(),
                tail ? value(float.class) : null, value(method.getReturnType()), false);
            members.add(new LinkedDeclaration.Member(schema,
                name.equals("restricted") ? net.minecraft.world.entity.decoration.ArmorStand.class : Entity.class,
                LinkedDeclaration.binding(ScalarCoercionIntegrationTest.class, name, method.getReturnType(),
                    parameters)));
        }
        var schema = new DeclarationSchema(ScalarCoercionIntegrationTest.class.getName(),
            ScalarCoercionIntegrationTest.class.getName() + "MolangBindings", "scalar", "", true, false, "", List.of(),
            members.stream().map(LinkedDeclaration.Member::schema).toList());
        var declaration = new LinkedDeclaration(schema, "ysm", ScalarCoercionIntegrationTest.class,
            ScalarCoercionIntegrationTest.class, ScalarCoercionIntegrationTest.class.getClassLoader(), members);
        var declarations = new ArrayList<>(base.declarations());
        declarations.add(declaration);
        return ExpressionHost.createFrozen(HostAvailability.none(), base.compilationPolicy(), declarations,
            declarations);
    }

    private static DeclarationSchema.Value value(Class<?> type) {
        if (type == Point.class)
            return new DeclarationSchema.Value("RECORD", type.getName(), "point", null,
                List.of(new DeclarationSchema.Field("x", value(float.class))));
        if (type == FloatList.class)
            return new DeclarationSchema.Value("FLOAT_LIST", type.getName(), "numbers", value(float.class), List.of());
        return new DeclarationSchema.Value(type == float.class ? "FLOAT" : type == int.class ? "INT" : "BOOLEAN",
            type.getName(), "", null, List.of());
    }

    private static ModelDataProto model(List<String> sources) throws Exception {
        var file = AnimationFileProto.newBuilder();
        for (int i = 0; i < sources.size(); i++) {
            var program = EncodedProgramMapper.toProto(new MolangFrontend().compile(sources.get(i),
                MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("scalar/" + i)).program());
            file.addAnimations(AnimationProto.newBuilder().setName("a" + i).setLength(0).setLoop(LoopType.PLAY_ONCE)
                .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(program).build()).build());
        }
        return ModelDataProto.newBuilder().putAnimationFiles("main", file.build()).build();
    }

    private record Stores(ExpressionArtifactStore artifacts, AnimationInputStore animations) {
        void awaitPersistence() throws InterruptedException {
            artifacts.awaitPersistence();
        }
    }

    private static Stores store(Path root) {
        var cache = track(new AtomicSharedCache(root));
        return new Stores(track(new ExpressionArtifactStore(root, cache)), new AnimationInputStore(root, cache));
    }

    private static RenderTargetExpressionModule create(Stores store, ExpressionEngineMetrics metrics,
        ExpressionHost host, ModelDataProto model) throws Exception {
        return create(store, metrics, host, model, null);
    }

    private static RenderTargetExpressionModule create(Stores store, ExpressionEngineMetrics metrics,
        ExpressionHost host, ModelDataProto model, StringDataProto strings) throws Exception {
        var hash = ModelId.fromHash(new Hash256(new byte[Hash256.SIZE]));
        var settings = SettingsProto.newBuilder().build();
        return new RenderTargetExpressionModuleFactory(store.artifacts(), store.animations(), host, metrics).create(
            () -> false, new ModelFileIdentity(hash, Hash256.parse(hash.toString().substring(5))), "player", "main",
            new BakeProfile("scalar"), false, model, strings, settings, ProtoUtil.serializeToArray(settings));
    }

    private static ModelProgramInventory.SemanticLocation location(int index) {
        return new ModelProgramInventory.SemanticLocation("player", "animation/main/a" + index + "/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
    }

    private static EntityExpressionRuntime.InvocationInputs inputs() {
        return new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<Entity>() {
            public Entity entity() {
                return currentSubject;
            }

        });
    }

    private static ComponentScope scope(Class<? extends Entity> subjectType) throws Exception {
        var subject = (Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(subjectType);
        currentSubject = subject;
        return new ComponentScope() {
            public AnimatableEntity<?> owner() {
                return null;
            }

            public Entity subject() {
                return subject;
            }

            public void setRenderTarget(ModelRenderTarget target, BooleanSupplier current) {}

            public void evaluate(AnimationEvent<?> event, BooleanSupplier current, Runnable body) {
                body.run();
            }

            public void tick(int previous, int next, BooleanSupplier current) {}

            public void update(float previous, float next, float partial, BooleanSupplier current) {}

            public void invalidate() {}

            public <T> Optional<T> exact(Class<T> type) {
                return Optional.empty();
            }

            public ProviderInstanceTable providers(ExpressionHost host, BindingPlan plan) {
                return TestComponentHolders.providers(host, plan);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.util.NativeLibUtil.load();
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var root = Path.of(args[0]);
        Files.createDirectories(root);
        var mode = args[1];
        var host = host();
        var store = store(root.resolve("cache"));
        var metrics = new ExpressionEngineMetrics();
        var model = model(List.of("return scalar.empty() ? 13 : 29;"));
        Files.write(root.resolve(mode + "-input.pb"), ProtoUtil.serializeToArray(model));
        var module = create(store, metrics, host, model);
        try (var animations = TestExpressionModules.store(module, Map.of());
            var runtime = new EntityExpressionRuntime()) {
            assertEquals(
                mode.equals("DISK") ? ExpressionArtifactStore.Source.DISK : ExpressionArtifactStore.Source.COLD,
                module.source());
            runtime.replace(animations, scope(net.minecraft.world.entity.decoration.ArmorStand.class));
            float actual = runtime.observe(inputs(), e -> e.evaluate(module.requireFloatProgram(location(0))));
            assertEquals(mode.equals("OLD") ? 13f : 29f, actual);
            assertTrue(module.compilationReport().localRecoveries().isEmpty());
            assertTrue(module.compilationReport().replacements().isEmpty());
            store.awaitPersistence();
            var pathMethod = ExpressionArtifactStore.class.getDeclaredMethod("path",
                com.elfmcys.ysm.expression.aot.ExpressionArtifactIdentity.class);
            pathMethod.setAccessible(true);
            var envelope = (Path)pathMethod.invoke(store.artifacts(), module.identity());
            var bytes = Files.readAllBytes(envelope);
            Files.write(root.resolve(mode + ".envelope"), bytes);
            try (var channel = Files.newByteChannel(envelope)) {
                var identity = module.identity();
                var lookup = new ExpressionArtifactLookup(identity.lookupFingerprint(), identity.containerId(),
                    identity.targetId(), identity.set(), identity.expected(), identity.expressionBinding(),
                    identity.configFingerprint(), identity.eventCatalogFingerprint(),
                    identity.productionLinkageFingerprint(), identity.purpose());
                var read = Class.forName("com.elfmcys.ysm.expression.aot.AotArtifactEnvelope").getDeclaredMethod("read",
                    java.nio.channels.SeekableByteChannel.class, ExpressionArtifactLookup.class,
                    ExpressionArtifactIdentity.class);
                read.setAccessible(true);
                var artifact = (ReadOnlyBytes)read.invoke(null, channel, lookup, identity);
                Files.write(root.resolve(mode + ".swa"), artifact.toByteArray());
            }
            String rejection = "none";
            if (mode.equals("CURRENT") || mode.equals("STABLE_HOST")) {
                var mismatch = assertThrows(ArtifactIdentityMismatchException.class,
                    () -> ArtifactCodecs.v1().decodeAndVerify(
                        ReadOnlyBytes.copyOf(Files.readAllBytes(root.resolve("OLD.swa"))), module.identity().expected(),
                        ExpressionIdentityHash.SHA_256));
                var old = Files.readString(root.resolve("OLD.txt"));
                if (mode.equals("STABLE_HOST"))
                    assertTrue(old.contains("lookup=" + module.identity().lookupKey()));
                assertFalse(old.contains("identity=" + module.identity().fingerprint()));
                rejection = mismatch.reason().name();
            }
            var evidence = "lookup=" + module.identity().lookupKey() + "\nidentity=" + module.identity().fingerprint()
                + "\nexpected=" + module.identity().expected().identity() + "\nsource=" + module.source() + "\nmarker="
                + actual + "\nrejection=" + rejection + "\ncompile="
                + metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION) + "\nhost="
                + ExpressionHost.class.getProtectionDomain().getCodeSource().getLocation() + "\nengine="
                + dev.semaweave.compilation.DefaultExpressionAotCompiler.class.getProtectionDomain().getCodeSource()
                    .getLocation()
                + "\n";
            Files.writeString(root.resolve(mode + ".txt"), evidence);
            System.out.println(evidence);
        }
    }
}
