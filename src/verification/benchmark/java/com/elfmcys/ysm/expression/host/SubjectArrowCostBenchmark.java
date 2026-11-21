package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.capability.PlayerStateTracker;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.program.*;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import org.openjdk.jmh.annotations.*;

/** Uses the same bounded subjects as the host seam tests, with counting outside timed assertions. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(1)
public class SubjectArrowCostBenchmark {
    static final ExpressionIdentityHash HASH = ExpressionIdentityHash.SHA_256;
    static final String ORIGINAL =
        "v.flame_level ?? (v.flame_level = ysm.projectile_owner -> ysm.equipped_enchantment_level('Mainhand', 'minecraft:flame'))";
    public static long currentCalls, rootCalls;

    public static float root(MolangContext context, float value) {
        rootCalls++;
        return value + context.subjectSample().position(0);
    }

    public static float current(MolangContext context) {
        currentCalls++;
        return context.subjectSample().position(0);
    }

    static ExpressionHost host() throws Exception {
        var base = ExpressionHost.create(HostAvailability.all());
        var members = new ArrayList<LinkedDeclaration.Member>();
        var scalar = new DeclarationSchema.Value("FLOAT", "float", "", null, List.of());
        for (var name : List.of("root", "current")) {
            boolean root = name.equals("root");
            var params =
                root ? new Class<?>[] {MolangContext.class, float.class} : new Class<?>[] {MolangContext.class};
            var schema = new DeclarationSchema.Member("cost", name, name,
                MethodType.methodType(float.class, params).toMethodDescriptorString(), "m" + members.size(),
                Entity.class.getName(), true, true, root ? "ACTION" : "CONTEXT", root ? "ORIGIN" : "SUBJECT",
                root ? 1 : 0, root ? 1 : 0, root ? List.of(scalar) : List.of(), List.of(), null, scalar, false);
            members.add(new LinkedDeclaration.Member(schema, Entity.class,
                LinkedDeclaration.binding(SubjectArrowCostBenchmark.class, name, float.class, params)));
        }
        var schema = new DeclarationSchema(SubjectArrowCostBenchmark.class.getName(),
            SubjectArrowCostBenchmark.class.getName() + "MolangBindings", "cost", "cost", false, false, "", List.of(),
            members.stream().map(LinkedDeclaration.Member::schema).toList());
        var declarations = new ArrayList<>(base.declarations());
        declarations.add(new LinkedDeclaration(schema, "cost", SubjectArrowCostBenchmark.class,
            SubjectArrowCostBenchmark.class, SubjectArrowCostBenchmark.class.getClassLoader(), members));
        return ExpressionHost.createFrozen(HostAvailability.all(), base.compilationPolicy(), declarations,
            base.declarations());
    }

    static SubjectSample sample(float x) {
        var draft = new SubjectSample.Draft();
        draft.position(x, 2, 3, 4, 5, 6, 1, 8, 9, 10, 11);
        draft.orientation(12, 13, 14, 15, 16);
        draft.pitch(17, 18);
        draft.closeEyes(true);
        draft.control("fixed");
        draft.melody(19, 20, 21, 22, 23);
        return draft.freeze();
    }

    static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(type));
    }

    @State(Scope.Thread)
    public static class Workload {
        @Param({"first3", "first0", "hit", "missing", "nested1", "nested2", "nested4", "nested8", "repeated1",
            "repeated2", "repeated4", "repeated8"})
        public String scenario;
        CompiledModuleResource<MolangContext> resource;
        CompiledModuleInstance<MolangContext> instance;
        ProgramRef entry, clear;
        MolangContext context;
        SubjectBinding binding;
        Arrow source;
        Player player;
        long expectedOwners, expectedEquipment, expectedCurrent, expectedRoot;
        float expected;
        CompiledArtifact artifact;
        Map<String, Object> structure;
        long coldNanos;
        SubjectBindingFactory bindingFactory;

        @Setup(Level.Trial)
        public void setup() throws Throwable {
            ComponentScopeTest.initializeMinecraftEntityClasses();
            source = allocate(Arrow.class);
            source.sample = sample(2);
            player = allocate(Player.class);
            player.sample = sample(7);
            player.projection = PlayerQueryProjection.MISSING;
            player.equipment = new ItemStack(Items.BOW);
            if (!scenario.equals("first0"))
                player.equipment.enchant(Enchantments.FLAMING_ARROWS, 3);
            source.owner = scenario.equals("missing") ? null : player;
            String expression = ORIGINAL;
            var functions = new LinkedHashMap<String, String>();
            if (scenario.startsWith("nested") || scenario.startsWith("repeated")) {
                int depth = Integer.parseInt(scenario.replaceAll("[a-z]", ""));
                source.owner = source;
                if (scenario.startsWith("nested")) {
                    var cursor = source;
                    for (int i = 1; i < depth; i++) {
                        var next = allocate(Arrow.class);
                        next.sample = sample(i + 2);
                        cursor.owner = next;
                        cursor = next;
                    }
                    cursor.owner = player;
                    expression = "fn.read()";
                    for (int i = 0; i < depth; i++)
                        expression = "ysm.projectile_owner->(" + expression + ")";
                    expected = 9;
                } else {
                    source.owner = player;
                    expression = String.join("+", Collections.nCopies(depth, "(ysm.projectile_owner->fn.read())"));
                    expected = 9 * depth;
                }
                functions.put("read", "cost.root(cost.current())");
                expectedOwners = depth;
                expectedCurrent = scenario.startsWith("nested") ? 1 : depth;
                expectedRoot = expectedCurrent;
            } else {
                expected = scenario.equals("first0") || scenario.equals("missing") ? 0 : 3;
                expectedOwners = scenario.equals("hit") ? 0 : 1;
                expectedEquipment = scenario.equals("hit") || scenario.equals("missing") ? 0 : 1;
            }
            var host = host();
            var codec = PortableProgramCodecs.v1();
            var unit = CompilationUnit.builder(host.compilationPolicy(), codec, HASH);
            add(unit, 0, expression, false, "");
            add(unit, 1, "v.flame_level=null;return 0;", false, "");
            for (var function : functions.entrySet())
                add(unit, 2, function.getValue(), true, function.getKey());
            var built = unit.build();
            var backend = new JaninoJavaCompilerBackend();
            var plan = host.expressionBinding().bindingPlan();
            var metrics = new com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics();
            for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans())
                pool.resetPeakUsage();
            long started = System.nanoTime();
            artifact = new DefaultExpressionAotCompiler<MolangContext>(backend, metrics).compile(TaskContext.NONE,
                built, host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, plan, HASH);
            coldNanos = System.nanoTime() - started;
            structure = new LinkedHashMap<>();
            structure.put("coldEngineNanos", coldNanos);
            structure.put("report", artifact.report().metrics());
            structure.put("catalogPrograms", artifact.catalog().size());
            structure.put("stages", metrics.snapshot());
            structure.put("heapPools", java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                .map(pool -> Map.of("pool", pool.getName(), "peakUsedBytes", pool.getPeakUsage().getUsed())).toList());
            resource = ModuleLoaders.<MolangContext>standard().load(
                artifact, ExpectedArtifactIdentity.create(built, host.hostAbi(), host.registry(),
                    CompilerOptions.DEFAULT, plan, backend.descriptor(), HASH),
                host.registry(), host.registry().visibilityRoot(), HASH);
            var registry = RegistryRuntimeLinkage.<MolangContext>create(TestComponentHolders.providers(host, plan),
                RuntimeSymbolTable.empty());
            instance = resource.newInstance(new RuntimeLinkage<>() {
                public RegistryRuntimeLinkage<MolangContext> registry() {
                    return registry;
                }

                public RuntimeDiagnostics diagnostics() {
                    return RuntimeDiagnostics.none();
                }
            });
            entry = instance.bindProgram(resource.programs().requireId(key(0)), ProgramShape.F32);
            clear = instance.bindProgram(resource.programs().requireId(key(1)), ProgramShape.F32);
            binding = instance.bindSubject(source);
            bindingFactory = instance::bindSubject;
            context = MolangContext.logical(source, new InvocationInputs<Entity>() {
                public Entity entity() {
                    return source;
                }
            }, MolangContext.LogicalPhase.ACTION, resource.newRoamingState(RoamingRole.PRODUCER),
                new InvocationProvenance("cost", scenario, 0, 1, 1));
            context.bindOrigin(binding);
            context.captureSubjectInputs(source.sample);
            context.bindExpression("cost", 0);
            if (scenario.equals("hit"))
                run();
            resetCounts();
            float value = evaluate();
            long owners = ownerReads(source, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (value != expected || owners != expectedOwners || player.equipmentReads != expectedEquipment
                || currentCalls != expectedCurrent || rootCalls != expectedRoot)
                throw new AssertionError(scenario + " truth " + value + "/" + owners + "/" + player.equipmentReads + "/"
                    + currentCalls + "/" + rootCalls);
            System.out.println("ARROW_TRUTH " + scenario + " value=" + value + " owners=" + owners + " equipment="
                + player.equipmentReads + " current=" + currentCalls + " root=" + rootCalls);
        }

        void resetCounts() {
            resetOwners(source, Collections.newSetFromMap(new IdentityHashMap<>()));
            player.equipmentReads = 0;
            currentCalls = rootCalls = 0;
        }

        float run() throws Throwable {
            return instance.executeFloat(entry, context, binding, context);
        }

        float evaluate() throws Throwable {
            if (scenario.equals("first3") || scenario.equals("first0"))
                instance.executeFloat(clear, context, binding, context);
            return run();
        }

        @Setup(Level.Invocation)
        public void prepareFirst() throws Throwable {
            if (scenario.equals("first3") || scenario.equals("first0"))
                instance.executeFloat(clear, context, binding, context);
        }

        @TearDown(Level.Trial)
        public void close() {
            if (context != null) {
                context.releaseExpression();
                context.closeRound();
            }
            if (instance != null)
                instance.close();
            if (resource != null)
                resource.close();
        }
    }

    static void add(CompilationUnit.Builder unit, int id, String expression, boolean function, String name)
        throws Exception {
        var portable = new MolangFrontend().compile(expression,
            function ? MolangFrontend.SourceMode.USER_FUNCTION : MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("cost/" + id));
        var locals = portable.referencedSymbols().stream().filter(s -> s.domain() == ProgramNode.SymbolDomain.LOCAL)
            .map(s -> new LocalName(s.name())).distinct().toList();
        unit.addProgram(new ProgramSource(key(id), portable.program(),
            function ? new UserFunctionEntrySignature(
                new SymbolKey(com.elfmcys.ysm.expression.frontend.MolangNames.userFunctionSymbol(name)),
                new EntryResult.Value(BuiltinType.F32), List.of(), List.of())
                : new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(),
                    locals),
            Optional.empty(), new DiagnosticLocation("cost/" + id)));
    }

    static ProgramKey key(int value) {
        return new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)value}));
    }

    static long ownerReads(Entity entity, Set<Entity> seen) {
        if (!(entity instanceof Arrow arrow) || !seen.add(entity))
            return 0;
        return arrow.ownerReads + ownerReads(arrow.owner, seen);
    }

    static void resetOwners(Entity entity, Set<Entity> seen) {
        if (entity instanceof Arrow arrow && seen.add(entity)) {
            arrow.ownerReads = 0;
            resetOwners(arrow.owner, seen);
        }
    }

    @Benchmark
    public float execute(Workload state) throws Throwable {
        return state.run();
    }

    @Benchmark
    public ChildContext<MolangContext> childAndBinding(Workload state) {
        return MolangContext.derive(state.context, state.player, state.bindingFactory, state.context);
    }

    @State(Scope.Thread)
    public static class SampleWorkload {
        SubjectSample.Draft draft;
        Player player;
        PlayerStateTracker tracker;
        com.elfmcys.ysm.proto.network.GameplayStateProto gameplay;
        com.elfmcys.ysm.proto.network.EffectStateSetProto effects;

        @Setup
        public void setup() throws Exception {
            ComponentScopeTest.initializeMinecraftEntityClasses();
            draft = new SubjectSample.Draft();
            draft.position(1, 2, 3, 4, 5, 6, 1, 8, 9, 10, 11);
            draft.orientation(12, 13, 14, 15, 16);
            draft.pitch(17, 18);
            draft.closeEyes(true);
            draft.control("fixed");
            draft.melody(19, 20, 21, 22, 23);
            player = allocate(Player.class);
            player.sample = SubjectSample.MISSING;
            player.projection = PlayerQueryProjection.MISSING;
            tracker = new PlayerStateTracker(player, false);
            gameplay = com.elfmcys.ysm.proto.network.GameplayStateProto.newBuilder().setHealth(13).setFoodLevel(4)
                .setMaxHealth(20).setExperienceLevel(5).setMoveXQ7(63).setFlying(true).build();
            tracker.updateProtocolState(gameplay, null, true);
            effects = com.elfmcys.ysm.proto.network.EffectStateSetProto.newBuilder()
                .addEffects(com.elfmcys.ysm.proto.network.EffectStateProto.newBuilder().setEffectId("minecraft:speed")
                    .setLevel(2).build())
                .addEffects(com.elfmcys.ysm.proto.network.EffectStateProto.newBuilder()
                    .setEffectId("minecraft:strength").setLevel(1).build())
                .build();
            tracker.updateProtocolState(gameplay, effects, true);
            if (player.projection.effects().size() != 2)
                throw new AssertionError("effect projection truth");
            player.projection = PlayerQueryProjection.MISSING;
            tracker.updateProtocolState(gameplay, null, true);
            if (player.projection.health() != 13 || player.projection.foodLevel() != 4)
                throw new AssertionError("projection publication truth");
        }
    }

    @Benchmark
    public SubjectSample freeze(SampleWorkload state) {
        return state.draft.freeze();
    }

    @Benchmark
    public SubjectSample publish(SampleWorkload state) {
        state.player.ysm$subjectSample(state.draft.freeze());
        return state.player.ysm$subjectSample();
    }

    @Benchmark
    public PlayerQueryProjection projection(SampleWorkload state) {
        state.tracker.updateProtocolState(state.gameplay, null, true);
        return state.player.projection;
    }

    @Benchmark
    public PlayerQueryProjection projectionEffects2(SampleWorkload state) {
        state.tracker.updateProtocolState(state.gameplay, state.effects, true);
        return state.player.projection;
    }

    public static final class Arrow extends Snowball implements SubjectSampleAccess {
        Entity owner;
        int ownerReads;
        SubjectSample sample;

        private Arrow() {
            super((EntityType<? extends Snowball>)null, null);
        }

        @Override
        public Entity getOwner() {
            ownerReads++;
            return owner;
        }

        @Override
        public ClientLevel level() {
            return null;
        }

        public SubjectSample ysm$subjectSample() {
            return sample;
        }

        public void ysm$subjectSample(SubjectSample next) {
            sample = next;
        }
    }

    public static final class Player extends RemotePlayer implements SubjectSampleAccess, PlayerQueryAccess {
        ItemStack equipment;
        int equipmentReads;
        SubjectSample sample;
        PlayerQueryProjection projection;

        private Player() {
            super(null, null);
        }

        @Override
        public ItemStack getItemBySlot(EquipmentSlot slot) {
            equipmentReads++;
            return equipment;
        }

        @Override
        public ClientLevel level() {
            return null;
        }

        public SubjectSample ysm$subjectSample() {
            return sample;
        }

        public void ysm$subjectSample(SubjectSample next) {
            sample = next;
        }

        public PlayerQueryProjection ysm$playerQueryProjection() {
            return projection;
        }

        public void ysm$playerQueryProjection(PlayerQueryProjection next) {
            projection = next;
        }
    }

    public static void main(String[] args) throws Throwable {
        var reports = new LinkedHashMap<String, Object>();
        for (String scenario : List.of("first3", "first0", "hit", "missing", "nested1", "nested2", "nested4", "nested8",
            "repeated1", "repeated2", "repeated4", "repeated8")) {
            var state = new Workload();
            state.scenario = scenario;
            try {
                state.setup();
                if (args.length > 0) {
                    var path = java.nio.file.Path.of(args[0]).resolve(scenario);
                    java.nio.file.Files.createDirectories(path);
                    for (var entry : state.artifact.classes().entrySet())
                        java.nio.file.Files.write(path.resolve(entry.getKey() + ".class"),
                            entry.getValue().toByteArray());
                }
                reports.put(scenario, state.structure);
            } finally {
                state.close();
            }
        }
        var sample = new SampleWorkload();
        sample.setup();
        var instrumentation = com.elfmcys.ysm.benchmark.NativeImageAccessAgent.instrumentation;
        if (instrumentation != null) {
            var sizes = new LinkedHashMap<String, Long>();
            sizes.put("draftShallow", instrumentation.getObjectSize(sample.draft));
            sizes.put("sampleShallow", instrumentation.getObjectSize(sample.draft.freeze()));
            sizes.put("projection0Shallow", instrumentation.getObjectSize(sample.player.projection));
            sample.tracker.updateProtocolState(sample.gameplay, sample.effects, true);
            sizes.put("projection2Shallow", instrumentation.getObjectSize(sample.player.projection));
            sizes.put("projection2OwnedGraph",
                ownedBytes(sample.player.projection, Collections.newSetFromMap(new IdentityHashMap<>())));
            var state = new Workload();
            state.scenario = "hit";
            try {
                state.setup();
                sizes.put("rootContextShallow", instrumentation.getObjectSize(state.context));
                sizes.put("sourceBindingShallow", instrumentation.getObjectSize(state.binding));
                var child = MolangContext.derive(state.context, state.player, state.bindingFactory, state.context);
                sizes.put("childPairShallow", instrumentation.getObjectSize(child));
                sizes.put("childContextShallow", instrumentation.getObjectSize(child.context()));
                sizes.put("childBindingShallow", instrumentation.getObjectSize(child.subjectBinding()));
            } finally {
                state.close();
            }
            System.out.println("OWNED_SIZE " + new com.google.gson.Gson().toJson(sizes));
        }
        System.out.println("ARROW_COST_STRUCTURE " + new com.google.gson.Gson().toJson(reports));
    }

    static long ownedBytes(Object value, Set<Object> seen) throws Exception {
        if (value == null || !seen.add(value))
            return 0;
        Class<?> type = value.getClass();
        String name = type.getName();
        if (!(value instanceof PlayerQueryProjection || value instanceof Map || name.startsWith("java.util.HashMap$")
            || name.startsWith("java.util.ImmutableCollections$") || type.isArray()))
            return 0;
        long bytes = com.elfmcys.ysm.benchmark.NativeImageAccessAgent.instrumentation.getObjectSize(value);
        if (type.isArray()) {
            if (!type.getComponentType().isPrimitive())
                for (var element : (Object[])value)
                    bytes += ownedBytes(element, seen);
            return bytes;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            for (var field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
                    continue;
                field.setAccessible(true);
                bytes += ownedBytes(field.get(value), seen);
            }
        return bytes;
    }
}
