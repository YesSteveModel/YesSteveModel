package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.program.*;
import dev.semaweave.interpreter.*;
import java.lang.invoke.MethodType;
import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent values and traces exercise the host's source/current seam. */
public final class SubjectArrowIntegrationTest {
    private static Entity target;
    private static Entity source;
    private static int arguments;
    private static int referenceReads;
    private static final List<Entity> RECEIVERS = new ArrayList<>();
    private static final IllegalStateException FAILURE = new IllegalStateException("arrow provider failure");

    public static Entity reference(MolangContext context) {
        referenceReads++;
        return target;
    }

    public static float argument(MolangContext context) {
        arguments++;
        return context.subject() == source ? 10 : 20;
    }

    public static float playerAction(MolangContext context, float value) {
        RECEIVERS.add(context.subject());
        return value;
    }

    public static float arrowAction(MolangContext context, float value) {
        RECEIVERS.add(context.subject());
        return value;
    }

    public static float replaceSample(MolangContext context) {
        ((SubjectSampleAccess)context.subject()).ysm$subjectSample(sample(99));
        return 0;
    }

    public static float explode(MolangContext context) {
        throw FAILURE;
    }

    public static float recordReceiver(Entity entity, float value) {
        RECEIVERS.add(entity);
        return value;
    }

    public static List<Entity> entities() {
        return List.of(target, source);
    }

    private static LinkedDeclaration publicReferences;
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path temporary;

    private static synchronized LinkedDeclaration publicReferences() throws Exception {
        if (publicReferences != null)
            return publicReferences;
        var directory = java.nio.file.Files.createDirectory(temporary.resolve("public-references"));
        String owner = "com.elfmcys.ysm.expression.host.ArrowPublicReferences";
        String source = """
            package com.elfmcys.ysm.expression.host;
            import com.elfmcys.ysm.api.annotation.*;
            import com.elfmcys.ysm.api.molang.*;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.projectile.Projectile;
            import java.util.List;
            @YsmEntityComponent(value="reference_mod",modId="reference_mod") public final class ArrowPublicReferences {
                @MolangFunction
                public static List<Entity> entities() { return SubjectArrowIntegrationTest.entities(); }
                @MolangNullable @MolangFunction
                public static Entity missing() { return null; }
                @MolangFunction(value="source_action",alwaysOrigin = true,action = true)
                public static float sourceAction(MolangContextView<Projectile> context,float value) {
                    return SubjectArrowIntegrationTest.recordReceiver(context.subject(),value);
                }
            }
            """;
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var file = new javax.tools.SimpleJavaFileObject(
                java.net.URI.create("string:///" + owner.replace('.', '/') + ".java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignored) {
                    return source;
                }
            };
            var task = compiler.getTask(null, manager, diagnostics, List.of("--release", "17", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString()), null, List.of(file));
            task.setProcessors(List.of(new com.elfmcys.ysm.api.internal.processor.UnifiedDeclarationProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        var lookup = java.lang.invoke.MethodHandles.lookup();
        try (var input =
            java.nio.file.Files.newInputStream(directory.resolve(DeclarationSchema.RESOURCE_ROOT + owner + ".bin"))) {
            var schema = DeclarationSchema.decode(input);
            lookup.defineClass(java.nio.file.Files.readAllBytes(directory.resolve(owner.replace('.', '/') + ".class")));
            lookup.defineClass(
                java.nio.file.Files.readAllBytes(directory.resolve(schema.helper().replace('.', '/') + ".class")));
            publicReferences = LinkedDeclaration.link(schema, SubjectArrowIntegrationTest.class.getClassLoader());
            return publicReferences;
        }
    }

    @Test
    void childBorrowsSourceFrameAndCapturesTheFixedDeferWindow() throws Throwable {
        initialize();
        source = arrow();
        target = player();
        try (var run = new Run("return 0;")) {
            var origin = run.context(source);
            var events = new ArrayList<EventArguments>();
            var locations = new ArrayList<InvocationProvenance>();
            origin.deferredSubmit((event, location) -> {
                events.add(event);
                locations.add(location);
            });
            origin.beginTopLevel(true);
            try {
                var binding = MolangContext.originBinding(origin);
                var child = MolangContext.derive(origin, target, run.instance::bindSubject, origin).context();
                assertSame(target, child.subject());
                assertSame(origin, MolangContext.origin(origin));
                child.defer(0.5f, 3f);
                assertEquals(List.of(0.5f, 3f), events.get(0));
                assertEquals(origin.provenance(), locations.get(0));
                assertSame(binding, MolangContext.originBinding(origin));
            } finally {
                origin.endTopLevel();
                origin.releaseExpression();
                origin.closeRound();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void publicReadonlyEntityListsAndValueCallsPreserveIdentityAcrossSlotsAndTransient(boolean interpreted)
        throws Throwable {
        initialize();
        source = arrow();
        target = player();
        ((Player)target).sample = sample(7);
        try (var run = new Run(interpreted,
            "v.entities=reference_mod.entities();t.ref=v.entities[0];v.ref=t.ref;return v.ref==arrow.reference();",
            "return fn.identity(v.ref)==arrow.reference();", "return (true?v.ref:v.entities[1])->query.position(0);",
            "for_each(t.ref,v.entities,{v.count=(v.count??0)+1;});return v.count;",
            "return reference_mod.missing??v.ref;", "v.ref=null;return 77;",
            "return (v.ref??v.entities[1])==arrow.reference();")) {
            assertEquals(1, run.value(0, source));
            assertEquals(1, run.value(1, source));
            assertEquals(7, run.value(2, source));
            assertEquals(2, run.value(3, source));
            assertEquals(1, run.value(4, source));
            assertSame(target, run.referenceValue(source));
            assertEquals(7, run.transientValue("v.ref->query.position(0)", source));
            assertEquals(77, run.value(5, source));
            assertEquals(0, run.value(6, source));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void selfArrowReadsCompletedSampleAndCrossWorldTargetsSkipTheRhs(boolean interpreted) throws Throwable {
        initialize();
        var arrow = arrow();
        source = target = arrow;
        var preparing = new SubjectSample.Draft();
        preparing.position(11, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0);
        var published = arrow.sample = preparing.freeze();
        preparing.position(99, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0);
        try (var run = new Run(interpreted, "query.position(0)+(arrow.reference()->query.position(0))",
            "(arrow.reference()->arrow.argument())??77")) {
            assertEquals(13, run.value(0, source));
            assertSame(published, arrow.sample);
            var player = player();
            player.world = allocate(ClientLevel.class);
            target = player;
            arguments = 0;
            assertEquals(77, run.value(1, source));
            assertEquals(0, arguments);
            assertSame(published, arrow.sample);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void originalOwnerExpressionCachesZeroAndPositiveValuesAndRetriesOnlyMissing(boolean interpreted) throws Throwable {
        initialize();
        var shooter = player();
        var arrow = arrow();
        source = arrow;
        arrow.owner = shooter;
        String expression =
            "v.flame_level ?? (v.flame_level = ysm.projectile_owner -> ysm.equipped_enchantment_level('Mainhand', 'minecraft:flame'))";
        for (int level : new int[] {0, 3}) {
            shooter.equipment = new ItemStack(Items.BOW);
            if (level != 0)
                shooter.equipment.enchant(Enchantments.FLAMING_ARROWS, level);
            arrow.ownerReads = shooter.equipmentReads = 0;
            try (var run = new Run(interpreted, expression, "v.flame_level=null;return 1;")) {
                assertEquals(level, run.value(0, arrow));
                assertEquals(level, run.value(0, arrow));
                assertEquals(1, arrow.ownerReads);
                assertEquals(1, shooter.equipmentReads);
                run.value(1, arrow);
                assertEquals(level, run.transientValue(expression, arrow));
                assertEquals(level, run.transientValue(expression, arrow));
                assertEquals(2, arrow.ownerReads);
            }
        }
        arrow.owner = null;
        try (var run = new Run(interpreted, expression)) {
            assertEquals(0, run.value(0, arrow));
            shooter.equipmentReads = 0;
            arrow.owner = shooter;
            assertEquals(3, run.value(0, arrow));
            assertEquals(1, shooter.equipmentReads);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sourceQualificationPrecedesCurrentArgumentsInBothDirectionsAndTransient(boolean interpreted) throws Throwable {
        initialize();
        var arrow = arrow();
        var player = player();
        for (boolean reverse : new boolean[] {false, true}) {
            source = reverse ? player : arrow;
            target = reverse ? arrow : player;
            String allowed = reverse ? "player_action" : "arrow_action";
            String denied = reverse ? "arrow_action" : "player_action";
            String expression = "arrow.reference()->arrow." + allowed + "(arrow.argument())";
            String publicExpression = "(arrow.reference()->reference_mod.source_action(arrow.argument()))??77";
            try (var run = new Run(interpreted, expression,
                "(arrow.reference()->arrow." + denied + "(arrow.argument())) ?? 77", publicExpression)) {
                arguments = 0;
                RECEIVERS.clear();
                assertEquals(20, run.value(0, source));
                assertEquals(20, run.transientValue(expression, source));
                assertEquals(2, arguments);
                assertEquals(List.of(source, source), RECEIVERS);
                assertEquals(77, run.value(1, source));
                assertEquals(2, arguments);
                assertEquals(2, RECEIVERS.size());
                arguments = 0;
                RECEIVERS.clear();
                assertEquals(reverse ? 77 : 20, run.value(2, source));
                assertEquals(reverse ? 77 : 20, run.transientValue(publicExpression, source));
                assertEquals(reverse ? 0 : 2, arguments);
                assertEquals(reverse ? List.of() : List.of(source, source), RECEIVERS);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void childCapturesCompletedInputsAndRemoteProjectionAndRestoresParentControlFlow(boolean interpreted)
        throws Throwable {
        initialize();
        var arrow = arrow();
        var player = player();
        source = arrow;
        target = player;
        player.sample = sample(7);
        player.projection = new PlayerQueryProjection(13, 20, 4, 5, 6, 7, 8, true, false, Map.of());
        try (var run = new Run(interpreted, "query.position(0)+(arrow.reference()->query.position(0))",
            "arrow.reference()->{arrow.replace_sample();return query.position(0);}",
            "arrow.reference()->(query.health+ysm.food_level+query.player_level+ysm.xxa+ysm.yya+ysm.zza)",
            "arrow.reference()->ctrl.im_delta", "v.saved=arrow.reference();return 1;",
            "return v.saved == arrow.reference();", "return (v.saved->query.position(0)) ?? 77;",
            "loop(3,{arrow.reference()->{continue;};v.written=1;});return v.written??9;",
            "return (arrow.reference()->arrow.explode()) ?? 77;", "(arrow.reference()->query.health) ?? 77")) {
            assertEquals(9, run.value(0, arrow));
            assertEquals(7, run.value(1, arrow));
            assertEquals(101, run.value(0, arrow));
            assertEquals(43, run.value(2, arrow));
            assertEquals(99, run.value(3, arrow));
            assertEquals(1, run.value(4, arrow));
            assertEquals(1, run.value(5, arrow));
            assertEquals(9, run.value(7, arrow));
            player.projection = PlayerQueryProjection.MISSING;
            assertEquals(77, run.value(9, arrow));
            var removed = Entity.class.getDeclaredField("removalReason");
            removed.setAccessible(true);
            removed.set(player, Entity.RemovalReason.DISCARDED);
            assertEquals(1, run.value(5, arrow));
            assertEquals(77, run.value(6, arrow));
            target = null;
            assertEquals(1, run.value(7, arrow));
            target = arrow;
            assertSame(FAILURE, assertThrows(Throwable.class, () -> run.value(8, arrow)));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nestedOriginAndLexicalTransfersKeepTheSourceStateAndRestoreItsSubject(boolean interpreted) throws Throwable {
        initialize();
        source = arrow();
        target = player();
        referenceReads = arguments = 0;
        RECEIVERS.clear();
        try (var run = new Run(interpreted,
            "v.count=0;arrow.reference()->{v.count=v.count+1;arrow.reference()->arrow.arrow_action(arrow.argument());};return v.count;",
            "v.count=0;loop(3,{arrow.reference()->{v.count=v.count+1;break;};v.count=99;});return v.count;",
            "arrow.reference()->{return arrow.argument();};return 99;", "return arrow.argument();")) {
            assertEquals(1, run.value(0, source));
            assertEquals(2, referenceReads);
            assertEquals(1, arguments);
            assertEquals(List.of(source), RECEIVERS);
            assertEquals(1, run.value(1, source));
            assertEquals(20, run.value(2, source));
            assertEquals(10, run.value(3, source));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void contextOnlyEntryRejectsOriginActionBeforeArguments(boolean interpreted) throws Throwable {
        initialize();
        source = arrow();
        target = player();
        arguments = 0;
        RECEIVERS.clear();
        try (var run = new Run(interpreted, EntryPermissions.CONTEXT,
            "(arrow.reference()->arrow.arrow_action(arrow.argument()))??77")) {
            assertEquals(77, run.value(0, source));
            assertEquals(0, arguments);
            assertTrue(RECEIVERS.isEmpty());
        }
    }

    private static SubjectSample sample(float x) {
        var draft = new SubjectSample.Draft();
        draft.position(x, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0);
        draft.melody(x, 0, 0, 0, 0);
        return draft.freeze();
    }

    private static void initialize() throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(type));
    }

    private static Arrow arrow() throws Exception {
        var result = allocate(Arrow.class);
        result.sample = SubjectSample.MISSING;
        return result;
    }

    private static Player player() throws Exception {
        var result = allocate(Player.class);
        result.sample = SubjectSample.MISSING;
        result.projection = PlayerQueryProjection.MISSING;
        return result;
    }

    private static ExpressionHost host() throws Exception {
        var base = ExpressionHost.create(HostAvailability.all());
        var members = new ArrayList<LinkedDeclaration.Member>();
        for (String name : List.of("reference", "argument", "playerAction", "arrowAction", "replaceSample",
            "explode")) {
            var method = Arrays.stream(SubjectArrowIntegrationTest.class.getMethods())
                .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            boolean action = name.endsWith("Action");
            String symbol = switch (name) {
                case "playerAction" -> "player_action";
                case "arrowAction" -> "arrow_action";
                case "replaceSample" -> "replace_sample";
                default -> name;
            };
            Class<? extends Entity> subject = name.equals("playerAction")
                ? net.minecraft.world.entity.player.Player.class
                : name.equals("arrowAction") ? net.minecraft.world.entity.projectile.Projectile.class : Entity.class;
            var scalar = new DeclarationSchema.Value("FLOAT", "float", "", null, List.of());
            var result = name.equals("reference")
                ? new DeclarationSchema.Value("ENTITY", Entity.class.getName(), "", null, List.of()) : scalar;
            var schema =
                new DeclarationSchema.Member("arrow", symbol, name,
                    MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                        .toMethodDescriptorString(),
                    "m" + members.size(), subject.getName(), true, true, action ? "ACTION" : "CONTEXT",
                    action ? "ORIGIN" : "SUBJECT", action ? 1 : 0, action ? 1 : 0, action ? List.of(scalar) : List.of(),
                    List.of(), null, result, name.equals("reference"));
            members.add(new LinkedDeclaration.Member(schema, subject, LinkedDeclaration
                .binding(SubjectArrowIntegrationTest.class, name, method.getReturnType(), method.getParameterTypes())));
        }
        var schema = new DeclarationSchema(SubjectArrowIntegrationTest.class.getName(),
            SubjectArrowIntegrationTest.class.getName() + "MolangBindings", "arrow", "probe", false, false, "",
            List.of(), members.stream().map(LinkedDeclaration.Member::schema).toList());
        var linked = new LinkedDeclaration(schema, "probe", SubjectArrowIntegrationTest.class,
            SubjectArrowIntegrationTest.class, SubjectArrowIntegrationTest.class.getClassLoader(), members);
        var declarations = new ArrayList<>(base.declarations());
        declarations.add(linked);
        declarations.add(publicReferences());
        return ExpressionHost.createFrozen(HostAvailability.all(), base.compilationPolicy(), declarations,
            base.declarations());
    }

    private static final class Run implements AutoCloseable {
        final ExpressionHost host;
        InterpreterEnvironment<MolangContext> environment;
        InterpreterInstance<MolangContext> interpreter;
        final List<InterpreterProgram> interpretedEntries = new ArrayList<>();
        InterpreterProgram interpretedReference;
        final CompiledModuleResource<MolangContext> resource;
        final CompiledModuleInstance<MolangContext> instance;
        final ProgramRef referenceEntry;
        final List<ProgramRef> entries = new ArrayList<>();

        Run(String... expressions) throws Exception {
            this(false, expressions);
        }

        Run(boolean interpreted, String... expressions) throws Exception {
            this(interpreted, EntryPermissions.ACTION, expressions);
        }

        Run(boolean interpreted, EntryPermissions permissions, String... expressions) throws Exception {
            host = host();
            var codec = PortableProgramCodecs.v1();
            var unit = CompilationUnit.builder(host.compilationPolicy(), codec, ExpressionIdentityHash.SHA_256);
            var keys = new ArrayList<ProgramKey>();
            for (int i = 0; i < expressions.length; i++) {
                var key = new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)i}));
                keys.add(key);
                var portable = new MolangFrontend().compile(expressions[i], MolangFrontend.SourceMode.EXPRESSION,
                    new MolangFrontend.SourceLocation("arrow/" + i));
                var locals = portable.referencedSymbols().stream()
                    .filter(symbol -> symbol.domain() == ProgramNode.SymbolDomain.LOCAL)
                    .map(symbol -> new LocalName(symbol.name())).distinct().toList();
                unit.addProgram(new ProgramSource(
                    key, portable.program(), new TopLevelEntrySignature(ProgramShape.F32,
                        new EntryResult.Value(BuiltinType.F32), List.of(), locals),
                    Optional.empty(), new DiagnosticLocation("arrow/" + i), permissions));
            }
            var identity = new MolangFrontend().compile("return args[0];", MolangFrontend.SourceMode.USER_FUNCTION,
                new MolangFrontend.SourceLocation("arrow/identity"));
            unit.addProgram(new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)127})),
                identity.program(),
                UserFunctionEntrySignature.inferred(
                    new SymbolKey(com.elfmcys.ysm.expression.frontend.MolangNames.userFunctionSymbol("identity")),
                    new LocalName("args"), new UnionType(List.of(ExpressionHost.ENTITY, BuiltinType.F32)), List.of()),
                Optional.empty(), new DiagnosticLocation("arrow/identity")));
            var referenceKey = new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)126}));
            var reference = new MolangFrontend().compile("arrow.reference()", MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("arrow/value"));
            unit.addProgram(new ProgramSource(
                referenceKey, reference.program(), new TopLevelEntrySignature(ProgramShape.VALUE,
                    new EntryResult.Value(ExpressionHost.ENTITY), List.of(), List.of()),
                Optional.empty(), new DiagnosticLocation("arrow/value")));
            var built = unit.build();
            var plan = host.expressionBinding().bindingPlan();
            if (interpreted) {
                var functions = new InterpreterFunctions() {
                    public Reference bind(ProgramKey caller, SymbolKey symbol, int count) {
                        return built.programs().stream()
                            .filter(p -> p.signature() instanceof UserFunctionEntrySignature fn
                                && fn.callable().equals(symbol))
                            .findFirst().<Reference>map(p -> new Known(p.key(), p.location(), Optional.empty()))
                            .orElseGet(Missing::new);
                    }

                    public Result acquire(Known reference) throws Throwable {
                        var input = built.programs().stream().filter(p -> p.key().equals(reference.key())).findFirst()
                            .orElseThrow();
                        return new Available(prepare(input));
                    }
                };
                environment = new InterpreterEnvironment<>(host.registry(), plan, CompilerOptions.DEFAULT, functions);
                interpreter = environment.newInstance(TestComponentHolders.providers(host, plan),
                    environment.newRoamingState(RoamingRole.PRODUCER), InterpreterDiagnostics.NONE);
                for (var key : keys)
                    interpretedEntries.add(
                        prepare(built.programs().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow()));
                interpretedReference = prepare(
                    built.programs().stream().filter(p -> p.key().equals(referenceKey)).findFirst().orElseThrow());
                resource = null;
                instance = null;
                referenceEntry = null;
                return;
            }
            var backend = new JaninoJavaCompilerBackend();
            dev.semaweave.compilation.CompiledArtifact artifact;
            try {
                artifact = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE, built,
                    host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, plan, ExpressionIdentityHash.SHA_256);
            } catch (SemanticCompilationException failure) {
                throw new AssertionError(failure.programId() + " " + failure.nodePath(), failure);
            }
            var expected = ExpectedArtifactIdentity.create(built, host.hostAbi(), host.registry(),
                CompilerOptions.DEFAULT, plan, backend.descriptor(), ExpressionIdentityHash.SHA_256);
            resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected, host.registry(),
                host.registry().visibilityRoot(), ExpressionIdentityHash.SHA_256);
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
            for (var key : keys)
                entries.add(instance.bindProgram(resource.programs().requireId(key), ProgramShape.F32));
            referenceEntry = instance.bindProgram(resource.programs().requireId(referenceKey), ProgramShape.VALUE);
        }

        private InterpreterProgram prepare(ProgramSource input) throws Exception {
            return environment.prepare(new InterpreterSource(input.key(), input.program(), input.signature(),
                input.location(), input.entryPermissions(), Map.of()));
        }

        SubjectBinding bindSubject(Entity subject) {
            return interpreter == null ? instance.bindSubject(subject) : environment.bindSubject(subject);
        }

        MolangContext<Entity> context(Entity subject) {
            var inputs = new InvocationInputs<Entity>() {
                public Entity entity() {
                    return subject;
                }
            };
            var context = MolangContext.logical(subject, inputs, MolangContext.LogicalPhase.ACTION,
                interpreter == null ? new RoamingState.Aot(resource.newRoamingState(RoamingRole.PRODUCER))
                    : new RoamingState.Interpreted(interpreter.roamingState()),
                new InvocationProvenance("source", "arrow", 0, 1, 1));
            context.bindOrigin(bindSubject(subject));
            context.captureSubjectInputs(sample(2));
            context.bindExpression("arrow", 0);
            return context;
        }

        float value(int index, Entity subject) throws Throwable {
            var context = context(subject);
            try {
                return interpreter == null
                    ? instance.executeFloat(entries.get(index), context, bindSubject(subject), context) : interpreter
                        .executeFloat(interpretedEntries.get(index), context, bindSubject(subject), context, List.of());
            } finally {
                context.releaseExpression();
                context.closeRound();
                assertNull(MolangContext.originBinding(context));
                assertNull(context.subject());
                assertSame(SubjectSample.MISSING, context.subjectSample());
            }
        }

        Entity referenceValue(Entity subject) throws Throwable {
            var context = context(subject);
            try {
                return interpreter == null
                    ? (Entity)instance.executeValue(referenceEntry, context, bindSubject(subject), context)
                    : (Entity)interpreter
                        .execute(interpretedReference, context, bindSubject(subject), context, List.of()).carrier();
            } finally {
                context.releaseExpression();
                context.closeRound();
            }
        }

        float transientValue(String expression, Entity subject) throws Throwable {
            var context = context(subject);
            if (interpreter != null) {
                try {
                    var portable = new MolangFrontend().compile(expression, MolangFrontend.SourceMode.EXPRESSION,
                        new MolangFrontend.SourceLocation("arrow/reprepared"));
                    var program =
                        environment.prepare(new InterpreterSource(
                            new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {125})), portable.program(),
                            new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32),
                                List.of(), List.of()),
                            new DiagnosticLocation("arrow/reprepared"), EntryPermissions.ACTION, Map.of()));
                    return interpreter.executeFloat(program, context, bindSubject(subject), context, List.of());
                } finally {
                    context.releaseExpression();
                    context.closeRound();
                }
            }
            var none = TransientDynamicAccess.none(ExpressionIdentityHash.SHA_256);
            var slots = new TreeSet<LocalName>(Comparator.comparing(LocalName::name));
            slots.addAll(resource.instanceSlotNames());
            var policy = host.compilationPolicy();
            var transientPolicy = new CompilationPolicy(policy.missingSymbols(), SemanticFailurePolicy.FAIL_UNIT,
                policy.arityMismatch(), policy.numericPolicy(), policy.executionLimits(), policy.objectLanguageRules());
            var layout = resource.createTransientLayout(
                new TransientAccessRequest(slots, slots, new TreeSet<>(), none.descriptor(), transientPolicy));
            var portable = new MolangFrontend().compile(expression, MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("arrow/transient"));
            try (var lease = TransientCompilers.<MolangContext>standard(new JaninoJavaCompilerBackend()).compile(
                TaskContext.NONE, portable.program(), ProgramShape.F32, layout, TransientPurpose.ONE_SHOT,
                ExpressionIdentityHash.SHA_256); var access = instance.openTransientAccess(layout, none)) {
                return lease.program().executeFloat(access, context, instance.bindSubject(subject), context);
            } finally {
                context.releaseExpression();
                context.closeRound();
            }
        }

        public void close() {
            if (interpreter != null) {
                interpreter.close();
                environment.close();
            } else {
                instance.close();
                resource.close();
            }
        }
    }

    private static final class Arrow extends Snowball implements SubjectSampleAccess {
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

    private static final class Player extends RemotePlayer implements SubjectSampleAccess, PlayerQueryAccess {
        ItemStack equipment;
        int equipmentReads;
        SubjectSample sample;
        PlayerQueryProjection projection;
        ClientLevel world;

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
            return world;
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
}
