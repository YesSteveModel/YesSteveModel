package com.elfmcys.ysm.verification;

import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.frontend.MolangNames;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.*;
import dev.semaweave.interpreter.*;
import dev.semaweave.registry.*;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One hand-written oracle runs against both ordinary and relocated runtimes in separate JVMs. */
public final class MolangArrowSemanticProcess {
    private static final ExpressionIdentityHash HASH = ExpressionIdentityHash.SHA_256;
    private static final CustomType REFERENCE = new CustomType(new SymbolKey("matrix:subject"));
    private static final ListType REFERENCES = new ListType(REFERENCE);
    private static final List<LocalName> LOCALS =
        List.of("a", "b", "x", "n", "sum", "item").stream().map(LocalName::new).toList();
    private static final String ORIGINAL =
        "v.flame_level ?? (v.flame_level = ysm.projectile_owner -> ysm.equipped_enchantment_level('Mainhand', 'minecraft:flame'))";
    private static int sourceCalls;

    private MolangArrowSemanticProcess() {}

    public static void main(String[] arguments) throws Throwable {
        require(arguments.length == 2, "expected directory and COLD or DISK");
        Path root = Path.of(arguments[0]);
        boolean cold = arguments[1].equals("COLD");
        require(cold || arguments[1].equals("DISK"), "unknown source");
        Files.createDirectories(root);
        var cases = cases();
        var programs = programs(root, cold, cases);
        var environment = environment(programs, "current");
        var metrics = new Metrics();
        var backend = new JaninoJavaCompilerBackend();
        CompiledArtifact artifact;
        if (cold) {
            artifact =
                new DefaultExpressionAotCompiler<Context>(backend, metrics).compile(TaskContext.NONE, environment.unit,
                    environment.host, environment.registry, CompilerOptions.DEFAULT, environment.bindings, HASH);
            Files.write(root.resolve("matrix.swa"), ArtifactCodecs.v1().encode(artifact, HASH).toByteArray());
            for (var entry : artifact.classes().entrySet()) {
                Path classFile = root.resolve("generated-classes").resolve(entry.getKey().replace('.', '/') + ".class");
                Files.createDirectories(classFile.getParent());
                Files.write(classFile, entry.getValue().toByteArray());
            }
        } else {
            artifact = ArtifactCodecs.v1().decodeAndVerify(
                ReadOnlyBytes.copyOf(Files.readAllBytes(root.resolve("matrix.swa"))), environment.expected, HASH);
        }
        require(artifact.classes().size() > 8, "capacity fixture did not generate a split artifact");
        var observations = new ArrayList<String>();
        int persistentSourceCalls;
        Map<String, Integer> persistentStages;
        try (var resource = ModuleLoaders.<Context>standard(metrics).load(artifact, environment.expected,
            environment.registry, environment.registry.visibilityRoot(), HASH)) {
            verify(resource, environment, programs, cases, Mode.PERSISTENT, metrics, observations);
            verifyIdentityRejection(root, environment, programs);
            persistentSourceCalls = sourceCalls;
            persistentStages = metrics.snapshot();
            require(sourceCalls == (cold ? programs.size() - 3 : 0), "source stage count: " + sourceCalls);
            for (var stage : List.of(EngineMetrics.Stage.SEMANTIC_ANALYSIS, EngineMetrics.Stage.SYNTAX_GENERATION,
                EngineMetrics.Stage.BACKEND_COMPILATION))
                require(metrics.calls.getOrDefault(stage, 0) == (cold ? 1 : 0), "persistent stage " + stage);
            // Transient compilation starts only after the persistent warm counters and results are fixed.
            verify(resource, environment, programs, cases, Mode.TRANSIENT, metrics, observations);
            verify(resource, environment, programs, cases, Mode.INTERPRETED, metrics, observations);
        }
        String canonical = String.join("\n", observations) + '\n';
        Path expected = root.resolve("observations.txt");
        if (cold)
            Files.writeString(expected, canonical);
        else
            require(Files.readString(expected).equals(canonical), "independent warm values or traces differ");
        var report = new LinkedHashMap<String, Object>();
        report.put("source", arguments[1]);
        report.put("sourceCallsBeforeTransient", persistentSourceCalls);
        report.put("persistentStageCalls", persistentStages);
        report.put("finalStageCalls", metrics.snapshot());
        report.put("programs", programs.size());
        report.put("classCount", artifact.classes().size());
        report.put("classBytes", artifact.classes().values().stream().mapToLong(value -> value.size()).sum());
        report.put("runtimeClass", CompiledArtifact.class.getName());
        report.put("runtimeCodeSource",
            CompiledArtifact.class.getProtectionDomain().getCodeSource().getLocation().toString());
        report.put("frontendCodeSource",
            MolangFrontend.class.getProtectionDomain().getCodeSource().getLocation().toString());
        report.put("classpath", System.getProperty("java.class.path"));
        report.put("observations", observations);
        report.put("controlledBoundary",
            "Exact registry/provider; real game equipment and owner roles use the Forge oracle.");
        Files.writeString(root.resolve("report-" + arguments[1] + ".json"),
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report) + '\n');
        System.out.println("MOLANG_ARROW_SEMANTIC_OK source=" + arguments[1] + " programs=" + programs.size()
            + " observations=" + observations.size() + " classes=" + artifact.classes().size() + " persistent_stages="
            + persistentStages);
    }

    private record Case(String name, String source, float value, List<String> trace) {
        Case(String name, String source, float value) {
            this(name, source, value, List.of());
        }
    }

    private static List<Case> cases() {
        var result = new ArrayList<Case>();
        String[] grouping = {"0??1?2:3", "1?null:2??7", "0?null:2??7", "1?(t.x=2):3;return t.x;",
            "t.a=test.mark((t.b=test.mark(3))+1);test.mark(t.b);return test.mark(t.a);", "1?{t.x=2;null??4;}:3",
            "1?0?2:3:4", "0?2:1?3:4", "0?1?2", "return t.x=1??2", "test.next()??test.next()??test.next()",
            "test.next()??test.next()??test.next()", "test.next()??test.next()??test.next()", "0&&test.next()||1",
            "1||test.next()&&test.next()", "1?test.mark(2):test.mark(3)", "0?test.mark(2):test.mark(3)", "10-3-2",
            "12/3*2", "!-+1", "1+2*3<8==1&&0||1?2:3??4", "v.a=(v.b=3);return v.a+v.b;"};
        float[] values = {0, 7, 2, 2, 4, 4, 3, 3, 0, 1, 0, 4, 9, 1, 1, 2, 3, 5, 8, 0, 2, 6};
        for (int i = 0; i < grouping.length; i++) {
            List<String> trace = switch (i) {
                case 4 -> List.of("mark:3.0", "mark:4.0", "mark:3.0", "mark:4.0");
                case 10 -> List.of("next:0.0");
                case 11 -> List.of("next:missing", "next:4.0");
                case 12 -> List.of("next:missing", "next:missing", "next:9.0");
                case 15 -> List.of("mark:2.0");
                case 16 -> List.of("mark:3.0");
                default -> List.of();
            };
            result.add(new Case("group-" + i, grouping[i], values[i], trace));
        }
        String[] counts = {"-1", "0", "0.49", "0.5", "1.49", "1.5", "1024", "1025"};
        int[] iterations = {0, 0, 0, 1, 1, 2, 1024, 1024};
        for (int i = 0; i < counts.length; i++)
            result.add(new Case("loop-" + i, "t.sum=0;loop(" + counts[i] + ",{t.sum=t.sum+1;});return t.sum+100;",
                iterations[i] + 100));
        result.add(new Case("loop-nan", null, 100));
        result.add(new Case("loop-positive-infinity", null, 1124));
        result.add(new Case("loop-negative-infinity", null, 100));
        result.add(new Case("loop-capture", "t.n=1.5;t.sum=0;loop(test.mark(t.n),{t.sum=t.sum+1;t.n=0;});return t.sum;",
            2, List.of("mark:1.5")));
        result.add(new Case("loop-sequential", "t.sum=0;" + "loop(1024,{t.sum=t.sum+1;});".repeat(11) + "return t.sum;",
            11264));
        result.add(new Case("loop-nested", "t.sum=0;loop(1024,{loop(1024,{t.sum=t.sum+1;});});return t.sum;", 1048576));
        result.add(new Case("for-each", "t.sum=0;for_each(t.item,test.references(),{t.sum=t.sum+1;});return t.sum;",
            10001, List.of("references:10001")));
        result.add(new Case("large-tail",
            "t.sum=test.tail("
                + String.join(",", java.util.stream.IntStream.range(0, 10001).mapToObj(Integer::toString).toList())
                + ");loop(1.5,{t.sum=t.sum+1;});return t.sum;",
            10003, List.of("tail:10001")));
        result.add(
            new Case("arrow-loop", "test.reference()->{t.sum=0;loop(1.5,{t.sum=t.sum+test.current();});return t.sum;}",
                40, List.of("reference:source", "derive:target", "current:target", "current:target")));
        result.add(new Case("function-loop", "fn.loopfn()", 2));
        result.add(new Case("parent-restore", "(test.reference()->test.current())+test.current()", 30,
            List.of("reference:source", "derive:target", "current:target", "current:source")));
        result.add(new Case("prefix-arrow", "-test.reference()->test.current()", -20,
            List.of("reference:source", "derive:target", "current:target")));
        result.add(new Case("arrow-prefix", "test.reference()->-test.current()", -20,
            List.of("reference:source", "derive:target", "current:target")));
        result.add(new Case("not-arrow", "!test.reference()->test.current()", 0,
            List.of("reference:source", "derive:target", "current:target")));
        result.add(new Case("mixed-scope", "(test.reference()->test.action(test.current()))+test.root_read()", 130,
            List.of("reference:source", "derive:target", "current:target", "action:source:20.0", "root:source")));
        result.add(new Case("shared-function", "fn.view()+(test.reference()->fn.view())", 50, List.of("current:source",
            "root:source", "reference:source", "derive:target", "current:target", "root:source")));
        result.add(new Case("depth-32", "fn.recurse(31,test.mark(1))", 7, Collections.nCopies(32, "mark:1.0")));
        result.add(new Case("depth-33", "fn.recurse(32,test.mark(1))", Float.NaN, Collections.nCopies(32, "mark:1.0")));
        result.add(new Case("depth-sequential", "t.sum=0;loop(64,{t.sum=t.sum+fn.recurse(0,0);});return t.sum;", 448));
        result.add(new Case("large-capacity", "t.sum=0;" + "t.sum=t.sum+1;".repeat(2300) + "return t.sum;", 2300));
        result.add(new Case("large-string", "test.length('" + "a".repeat(1_048_577) + "')", 1_048_577));
        result.add(new Case("original", "(" + ORIGINAL + ")??-99", 0));
        result.add(new Case("nullable", "(test.reference()->test.action(test.current()))??77", 77));
        return result;
    }

    private static List<ProgramSource> programs(Path root, boolean cold, List<Case> cases) throws Exception {
        var result = new ArrayList<ProgramSource>();
        for (var item : cases) {
            EncodedProgram encoded;
            if (cold) {
                if (item.source == null) {
                    float count = switch (item.name) {
                        case "loop-nan" -> Float.NaN;
                        case "loop-positive-infinity" -> Float.POSITIVE_INFINITY;
                        case "loop-negative-infinity" -> Float.NEGATIVE_INFINITY;
                        default -> throw new AssertionError(item.name);
                    };
                    var sum = new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.LOCAL, "sum"),
                        ProgramNode.AccessForm.READ, List.of());
                    encoded = PortableProgramCodecs.v1().encode(PortablePrograms.ofRoots(List.of(
                        new ProgramNode.Assignment(sum, ProgramNode.F32.of(0)),
                        new ProgramNode.Loop(ProgramNode.F32.of(count),
                            new ProgramNode.Assignment(sum,
                                new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD, sum, ProgramNode.F32.of(1)))),
                        new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD, sum, ProgramNode.F32.of(100)))));
                } else {
                    encoded = frontend(item.source, MolangFrontend.SourceMode.EXPRESSION);
                }
                Files.write(root.resolve(item.name + ".portable"), encoded.payload().toByteArray());
            } else {
                encoded = new EncodedProgram(1,
                    ReadOnlyBytes.copyOf(Files.readAllBytes(root.resolve(item.name + ".portable"))));
            }
            result.add(new ProgramSource(key(item.name), encoded,
                new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(), LOCALS),
                Optional.empty(), new DiagnosticLocation("arrow-matrix/" + item.name)));
        }
        for (String function : List.of("view", "loopfn", "recurse")) {
            String source = switch (function) {
                case "view" -> "return test.current()+test.root_read();";
                case "loopfn" -> "t.sum=0;loop(1.5,{t.sum=t.sum+1;});return t.sum;";
                case "recurse" -> "return t.remaining>0?fn.recurse(t.remaining-1,test.mark(1)):7;";
                default -> throw new AssertionError();
            };
            EncodedProgram encoded;
            if (cold) {
                encoded = frontend(source, MolangFrontend.SourceMode.USER_FUNCTION);
                Files.write(root.resolve(function + ".portable"), encoded.payload().toByteArray());
            } else {
                encoded = new EncodedProgram(1,
                    ReadOnlyBytes.copyOf(Files.readAllBytes(root.resolve(function + ".portable"))));
            }
            var parameters = function.equals("recurse")
                ? List.<EntryParameter>of(new RequiredParameter(new LocalName("remaining"), BuiltinType.F32),
                    new RequiredParameter(new LocalName("touch"), BuiltinType.F32))
                : List.<EntryParameter>of();
            result.add(new ProgramSource(key("fn-" + function), encoded,
                new UserFunctionEntrySignature(new SymbolKey(MolangNames.userFunctionSymbol(function)),
                    new EntryResult.Value(BuiltinType.F32), parameters, LOCALS),
                Optional.empty(), new DiagnosticLocation("arrow-matrix/function/" + function)));
        }
        return result;
    }

    private static EncodedProgram frontend(String source, MolangFrontend.SourceMode mode) throws Exception {
        sourceCalls++;
        return new MolangFrontend().compile(source, mode, new MolangFrontend.SourceLocation("arrow-matrix")).program();
    }

    private record Environment(RegistrySnapshot<Context> registry, BindingPlan bindings, HostAbi host,
        CompilationUnit unit, ExpectedArtifactIdentity expected) {
    }

    private static Environment environment(List<ProgramSource> programs, String change) throws Exception {
        var context = ContextTypes.create("matrix:context", 1, Context.class,
            List.of(new ChildContextBinding(REFERENCE,
                member(change.equals("child-linkage") ? "alternateDerive" : "derive", ChildContext.class, Context.class,
                    Subject.class, SubjectBindingFactory.class, ExecutionFrame.class),
                !change.equals("nullable"))),
            HASH);
        var builder = ExpressionRegistry.builder(context, HASH);
        builder.registerType(
            new ReferenceTypeDescriptor(REFERENCE, change.equals("reference-abi") ? 2 : 1, Subject.class));
        builder.registerType(new IndexedListTypeDescriptor(REFERENCES, 1, List.class, REFERENCE,
            member("size", int.class, List.class), member("at", Subject.class, List.class, int.class),
            EffectSet.empty(), EffectSet.empty(), CopyStrategyDescriptor.generated("matrix:copy", 1),
            DisplayStrategyDescriptor.generated("matrix:display", 1),
            EqualityStrategyDescriptor.generated("matrix:equality", 1), Optional.empty()));
        builder.registerProvider(new ProviderSchema("matrix:provider", 1, Provider.class, true));
        var action = new JavaMemberBinding(Provider.class, "action",
            MethodType.methodType(float.class, Context.class, float.class), InvocationKind.INSTANCE,
            Optional.of("matrix:provider"));
        builder.registerFunction(function("test:action", BuiltinType.F32, action, false, BuiltinType.F32));
        builder.registerFunction(
            function("test:reference", REFERENCE, member("reference", Subject.class, Context.class), true));
        builder.registerFunction(
            function("ysm:projectile_owner", REFERENCE, member("owner", Subject.class, Context.class), true));
        builder.registerFunction(function("ysm:equipped_enchantment_level", BuiltinType.F32,
            member("enchantment", float.class, Context.class, String.class, String.class), false, BuiltinType.STRING,
            BuiltinType.STRING));
        for (String name : List.of("current", "root_read"))
            builder.registerFunction(
                function("test:" + name, BuiltinType.F32, member(name, float.class, Context.class), false));
        builder
            .registerFunction(function("test:next", BuiltinType.F32, member("next", Float.class, Context.class), true));
        builder.registerFunction(function("test:mark", BuiltinType.F32,
            member("mark", float.class, Context.class, float.class), false, BuiltinType.F32));
        builder.registerFunction(
            function("test:references", REFERENCES, member("references", List.class, Context.class), false));
        builder.registerFunction(function("test:length", BuiltinType.F32,
            member("length", float.class, Context.class, String.class), false, BuiltinType.STRING));
        builder.registerFunction(new FunctionDescriptor<>(new SymbolKey("test:tail"), FunctionCallShape.VARIADIC, 0,
            Integer.MAX_VALUE, List.of(), List.of(), Optional.of(BuiltinType.F32), BuiltinType.F32,
            member("tail", float.class, Context.class, int.class, float[].class),
            EffectSet.of(new EffectId("matrix:trace")), "matrix", 1));
        var registry = builder.freeze();
        var bindings = BindingPlan.builder().subjectAccessor(member("subject", Object.class, Context.class))
            .rootContextAccessor(
                member(change.equals("root-frame") ? "alternateRoot" : "root", Context.class, ExecutionFrame.class))
            .rootBindingAccessor(member("rootBinding", SubjectBinding.class, ExecutionFrame.class))
            .scope(member("root_read", float.class, Context.class),
                change.equals("scope") ? InvocationScope.CURRENT : InvocationScope.ROOT)
            .guard(action, action, DeclarationEffect.ACTION, Source.class, InvocationScope.ROOT)
            .guard(member("owner", Subject.class, Context.class), member("owner", Subject.class, Context.class),
                DeclarationEffect.CONTEXT, Source.class, InvocationScope.CURRENT)
            .guard(member("enchantment", float.class, Context.class, String.class, String.class),
                member("enchantment", float.class, Context.class, String.class, String.class),
                DeclarationEffect.CONTEXT, Target.class, InvocationScope.CURRENT)
            .build();
        var host = new HostAbi("matrix:host", "1", Fingerprint.compute(HASH, ReadOnlyBytes.copyOf(new byte[] {31})));
        var base = CompilationPolicy.DEFAULT;
        var policy = new CompilationPolicy(base.missingSymbols(), base.semanticFailure(), base.arityMismatch(),
            base.numericPolicy(), base.executionLimits(), ObjectLanguageRules.YSM_STATIC_OBJECTS);
        var unitBuilder = CompilationUnit.builder(policy, PortableProgramCodecs.v1(), HASH);
        for (ProgramSource program : programs)
            unitBuilder.addProgram(program);
        var unit = unitBuilder.build();
        var expected = ExpectedArtifactIdentity.create(unit, host, registry, CompilerOptions.DEFAULT, bindings,
            new JaninoJavaCompilerBackend().descriptor(), HASH);
        return new Environment(registry, bindings, host, unit, expected);
    }

    private static FunctionDescriptor<Context> function(String symbol, ValueType result, JavaMemberBinding member,
        boolean nullable, ValueType... arguments) {
        return new FunctionDescriptor<>(new SymbolKey(symbol), FunctionCallShape.FIXED, arguments.length,
            arguments.length, List.of(arguments), List.of(), Optional.empty(), result, member,
            EffectSet.of(new EffectId("matrix:trace")), "matrix", 1, Optional.empty(), nullable);
    }

    private static void verify(CompiledModuleResource<Context> resource, Environment environment,
        List<ProgramSource> programs, List<Case> cases, Mode mode, Metrics metrics, List<String> observations)
        throws Throwable {
        var dynamic = TransientDynamicAccess.none(HASH);
        var slots = new TreeSet<LocalName>(Comparator.comparing(LocalName::name));
        slots.addAll(resource.instanceSlotNames());
        var functions = new TreeSet<ProgramKey>();
        programs.stream().filter(value -> value.signature() instanceof UserFunctionEntrySignature)
            .forEach(value -> functions.add(value.key()));
        var layout = resource.createTransientLayout(
            new TransientAccessRequest(slots, slots, functions, dynamic.descriptor(), environment.unit.policy()));
        Map<String, TransientProgramLease<Context>> leases = new LinkedHashMap<>();
        try {
            if (mode == Mode.TRANSIENT)
                for (int i = 0; i < cases.size(); i++)
                    leases.put(cases.get(i).name,
                        TransientCompilers.<Context>standard(new JaninoJavaCompilerBackend(), metrics).compile(
                            TaskContext.NONE, programs.get(i).program(), ProgramShape.F32, layout,
                            TransientPurpose.ONE_SHOT, HASH));
            for (int i = 0; i < cases.size(); i++) {
                var item = cases.get(i);
                if (Set.of("original", "nullable").contains(item.name))
                    continue;
                try (var run = new Run(resource, environment, layout, dynamic, mode)) {
                    if (item.name.equals("group-10"))
                        run.context.reads.addAll(Arrays.asList(0f, 4f, 9f));
                    else if (item.name.equals("group-11"))
                        run.context.reads.addAll(Arrays.asList(null, 4f, 9f));
                    else
                        run.context.reads.addAll(Arrays.asList(null, null, 9f));
                    if (item.name.equals("depth-33")) {
                        try {
                            run.value(item.name, mode, leases);
                            throw new AssertionError("depth 33 was accepted");
                        } catch (DepthFailure expected) {
                            require(run.frame.depth == 0, "depth leak after rejection");
                        }
                        require(run.context.trace.equals(item.trace), item.name + " argument trace");
                        var depthTrace = List.copyOf(run.context.trace);
                        run.context.trace.clear();
                        require(run.value("group-0", mode, leases) == 0, "next expression after depth rejection");
                        observations
                            .add(mode.name().toLowerCase(Locale.ROOT) + ":depth-33=DEPTH_FAILURE:next=0:" + depthTrace);
                    } else {
                        float value = run.value(item.name, mode, leases);
                        require(value == item.value, item.name + " expected " + item.value + " actual " + value);
                        require(run.context.trace.equals(item.trace), item.name + " trace " + run.context.trace);
                        observations.add(mode.name().toLowerCase(Locale.ROOT) + ':' + item.name + '=' + value + ':'
                            + run.context.trace);
                    }
                }
            }
            for (int level : new int[] {0, 3})
                try (var run = new Run(resource, environment, layout, dynamic, mode)) {
                    run.context.subject.owner.level = level;
                    require(run.value("original", mode, leases) == level, "first original " + level);
                    require(run.value("original", mode, leases) == level, "cached original " + level);
                    require(
                        run.context.trace
                            .equals(List.of("owner:source", "derive:target", "enchantment:target:" + level)),
                        "original cache trace");
                    observations.add(mode.name().toLowerCase(Locale.ROOT) + ":original-" + level + '=' + level + ','
                        + level + ':' + run.context.trace);
                }
            try (var run = new Run(resource, environment, layout, dynamic, mode)) {
                Subject target = run.context.subject.owner;
                run.context.subject.owner = null;
                require(run.value("original", mode, leases) == -99, "missing original must not become zero");
                require(run.value("original", mode, leases) == -99, "missing original should be retried");
                run.context.subject.owner = target;
                target.level = 3;
                require(run.value("original", mode, leases) == 3, "original after owner appears");
                require(run.value("original", mode, leases) == 3, "cached original after retry");
                require(run.context.trace.equals(
                    List.of("owner:source", "owner:source", "owner:source", "derive:target", "enchantment:target:3")),
                    "missing retry trace");
                observations
                    .add(mode.name().toLowerCase(Locale.ROOT) + ":original-missing=-99,-99,3,3:" + run.context.trace);
            }
            try (var run = new Run(resource, environment, layout, dynamic, mode)) {
                run.context.subject.owner.valid = false;
                require(run.value("nullable", mode, leases) == 77, "nullable child fallback");
                require(run.context.trace.equals(List.of("reference:source", "derive:target")),
                    "nullable RHS must be skipped");
                observations.add(mode.name().toLowerCase(Locale.ROOT) + ":nullable=77:" + run.context.trace);
            }
        } finally {
            leases.values().forEach(TransientProgramLease::close);
        }
    }

    private enum Mode {
        PERSISTENT, TRANSIENT, INTERPRETED
    }

    private static void verifyIdentityRejection(Path root, Environment current, List<ProgramSource> programs)
        throws Exception {
        var encoded = ReadOnlyBytes.copyOf(Files.readAllBytes(root.resolve("matrix.swa")));
        for (String change : List.of("scope", "reference-abi", "child-linkage", "nullable", "root-frame")) {
            var changed = environment(programs, change);
            try {
                ArtifactCodecs.v1().decodeAndVerify(encoded, changed.expected, HASH);
                throw new AssertionError("old artifact accepted after " + change);
            } catch (ArtifactIdentityMismatchException expected) {
                System.out.println("ARROW_IDENTITY_REJECTION dimension=" + change + " reason=" + expected.reason());
            }
        }
        var identity = current.expected.identity();
        var legacy = new CompiledArtifactIdentity(identity.hashAlgorithm(), identity.input(),
            "pre-independent-loop-and-unbounded-compilation", identity.runtimeAbi(), identity.codegenAbi(),
            identity.javaFeature(), identity.classfileMajor(), identity.sourceGeneratorId(),
            identity.sourceGeneratorVersion(), identity.backend(), identity.artifactFingerprint());
        try {
            ArtifactCodecs.v1().decodeAndVerify(encoded,
                new ExpectedArtifactIdentity(legacy, current.expected.registry()), HASH);
            throw new AssertionError("old resource/loop semantic identity accepted");
        } catch (ArtifactIdentityMismatchException expected) {
            require(expected.reason() == ArtifactIdentityMismatchException.Reason.PROGRAM_SEMANTIC_ABI,
                "semantic identity reason");
            System.out
                .println("ARROW_IDENTITY_REJECTION dimension=resource-loop-semantics reason=" + expected.reason());
        }
    }

    private static final class Run implements AutoCloseable {
        final CompiledModuleResource<Context> resource;
        final Environment environment;
        final CompiledModuleInstance<Context> instance;
        final TransientExecutionAccess<Context> access;
        final Context context;
        final Frame frame;
        InterpreterEnvironment<Context> interpreterEnvironment;
        InterpreterInstance<Context> interpreter;
        final Map<ProgramKey, InterpreterProgram> prepared = new HashMap<>();

        Run(CompiledModuleResource<Context> resource, Environment environment, TransientLayout<Context> layout,
            TransientDynamicAccess dynamic, Mode mode) {
            this.resource = resource;
            this.environment = environment;
            var source = new Source("source", 10);
            source.owner = new Target("target", 20);
            context = new Context(source, new ArrayList<>());
            var provider = new Provider(source);
            var providers = ProviderInstanceTable.create(environment.registry, environment.bindings,
                Map.of("matrix:provider", provider));
            if (mode == Mode.INTERPRETED) {
                var functions = new InterpreterFunctions() {
                    public Reference bind(ProgramKey caller, SymbolKey symbol, int count) {
                        return environment.unit.programs().stream()
                            .filter(p -> p.signature() instanceof UserFunctionEntrySignature fn
                                && fn.callable().equals(symbol))
                            .findFirst().<Reference>map(p -> new Known(p.key(), p.location(), Optional.empty()))
                            .orElseGet(Missing::new);
                    }

                    public Result acquire(Known reference) throws Throwable {
                        return new Available(prepare(reference.key(), environment));
                    }
                };
                interpreterEnvironment = new InterpreterEnvironment<>(environment.registry, environment.bindings,
                    CompilerOptions.DEFAULT, NonFinitePolicy.PRESERVE_IEEE_754, functions);
                interpreter = interpreterEnvironment.newInstance(providers,
                    interpreterEnvironment.newRoamingState(RoamingRole.PRODUCER), InterpreterDiagnostics.NONE);
                context.binding = interpreterEnvironment.bindSubject(source);
                frame = new Frame(context);
                instance = null;
                access = null;
                return;
            }
            var linked = RegistryRuntimeLinkage.<Context>create(providers, RuntimeSymbolTable.empty());
            instance = resource.newInstance(new RuntimeLinkage<>() {
                public RegistryRuntimeLinkage<Context> registry() {
                    return linked;
                }

                public RuntimeDiagnostics diagnostics() {
                    return RuntimeDiagnostics.none();
                }
            });
            context.binding = instance.bindSubject(source);
            access = instance.openTransientAccess(layout, dynamic);
            frame = new Frame(context);
        }

        InterpreterProgram prepare(ProgramKey key, Environment environment) throws Exception {
            var hit = prepared.get(key);
            if (hit != null)
                return hit;
            var input = environment.unit.programs().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
            var program = interpreterEnvironment.prepare(new InterpreterSource(input.key(), input.program(),
                input.signature(), input.location(), input.entryPermissions(), Map.of()));
            prepared.put(key, program);
            return program;
        }

        float value(String id, Mode mode, Map<String, TransientProgramLease<Context>> leases) throws Throwable {
            if (mode == Mode.INTERPRETED)
                return interpreter.executeFloat(prepare(key(id), environment), context, context.binding, frame,
                    List.of());
            if (mode == Mode.TRANSIENT)
                return leases.get(id).program().executeFloat(access, context, context.binding, frame);
            return instance.executeFloat(resource.bindProgram(key(id), ProgramShape.F32), context, context.binding,
                frame);
        }

        public void close() {
            if (interpreter != null) {
                interpreter.close();
                interpreterEnvironment.close();
            } else {
                access.close();
                instance.close();
            }
        }
    }

    public static class Subject {
        final String name;
        final float number;
        Subject owner;
        boolean valid = true;
        int level;

        Subject(String name, float number) {
            this.name = name;
            this.number = number;
        }
    }

    public static final class Source extends Subject {
        Source(String name, float number) {
            super(name, number);
        }
    }

    public static final class Target extends Subject {
        Target(String name, float number) {
            super(name, number);
        }
    }

    public static final class Context {
        final Subject subject;
        final List<String> trace;
        final List<Float> reads = new ArrayList<>();
        SubjectBinding binding;
        int readIndex;

        Context(Subject subject, List<String> trace) {
            this.subject = subject;
            this.trace = trace;
        }
    }

    public static final class Provider {
        final Subject owner;

        Provider(Subject owner) {
            this.owner = owner;
        }

        public float action(Context context, float value) {
            require(context.subject == owner, "ROOT context and source receiver differ");
            context.trace.add("action:" + owner.name + ':' + value);
            return 100 + value;
        }
    }

    public static ChildContext<Context> derive(Context parent, Subject subject, SubjectBindingFactory bindings,
        ExecutionFrame frame) {
        if (subject == null)
            return null;
        parent.trace.add("derive:" + subject.name);
        if (!subject.valid)
            return null;
        Context child = new Context(subject, parent.trace);
        child.binding = bindings.bind(subject);
        return new ChildContext<>(child, child.binding);
    }

    public static ChildContext<Context> alternateDerive(Context parent, Subject subject, SubjectBindingFactory bindings,
        ExecutionFrame frame) {
        return derive(parent, subject, bindings, frame);
    }

    public static Context root(ExecutionFrame frame) {
        return ((Frame)frame).context;
    }

    public static Context alternateRoot(ExecutionFrame frame) {
        return root(frame);
    }

    public static SubjectBinding rootBinding(ExecutionFrame frame) {
        return root(frame).binding;
    }

    public static Object subject(Context context) {
        return context.subject;
    }

    public static Subject reference(Context context) {
        context.trace.add("reference:" + context.subject.name);
        return context.subject.owner;
    }

    public static Subject owner(Context context) {
        context.trace.add("owner:" + context.subject.name);
        return context.subject.owner;
    }

    public static float enchantment(Context context, String slot, String name) {
        require(slot.equals("Mainhand") && name.equals("minecraft:flame"), "original expression arguments");
        context.trace.add("enchantment:" + context.subject.name + ':' + context.subject.level);
        return context.subject.level;
    }

    public static float current(Context context) {
        context.trace.add("current:" + context.subject.name);
        return context.subject.number;
    }

    public static float root_read(Context context) {
        context.trace.add("root:" + context.subject.name);
        return context.subject.number;
    }

    public static Float next(Context context) {
        Float value = context.reads.get(context.readIndex++);
        context.trace.add("next:" + (value == null ? "missing" : value));
        return value;
    }

    public static float mark(Context context, float value) {
        context.trace.add("mark:" + value);
        return value;
    }

    public static List<Subject> references(Context context) {
        context.trace.add("references:10001");
        return Collections.nCopies(10001, context.subject.owner);
    }

    public static int size(List<Subject> values) {
        return values.size();
    }

    public static Subject at(List<Subject> values, int index) {
        return values.get(index);
    }

    public static float tail(Context context, int argc, float[] values) {
        require(argc == 10001 && values.length == 10001, "large variadic argument count");
        for (int i = 0; i < 10001; i++)
            require(values[i] == i, "large variadic argument order at " + i);
        context.trace.add("tail:10001");
        return 10001;
    }

    public static float length(Context context, String value) {
        require(value.length() == 1_048_577 && value.chars().allMatch(character -> character == 'a'),
            "large literal contents");
        return value.length();
    }

    private static final class DepthFailure extends RuntimeException {
        private static final long serialVersionUID = 1;
    }

    private static final class Frame implements ExecutionFrame {
        final Context context;
        int depth;
        boolean action;

        Frame(Context context) {
            this.context = context;
        }

        public boolean actionAllowed() {
            return action;
        }

        public void beginTopLevel(boolean allowed) {
            require(depth == 0, "depth was not reset");
            action = allowed;
        }

        public void endTopLevel() {
            require(depth == 0, "depth leaked");
            action = false;
        }

        public void enterUserFunction() {
            if (depth == 32)
                throw new DepthFailure();
            depth++;
        }

        public void exitUserFunction() {
            depth--;
        }
    }

    private static final class Metrics implements EngineMetrics {
        final Map<Stage, Integer> calls = new EnumMap<>(Stage.class);

        public void recordDuration(Stage stage, long nanos) {
            calls.merge(stage, 1, Integer::sum);
        }

        Map<String, Integer> snapshot() {
            var result = new TreeMap<String, Integer>();
            for (Stage stage : Stage.values())
                result.put(stage.name(), calls.getOrDefault(stage, 0));
            return result;
        }
    }

    private static JavaMemberBinding member(String name, Class<?> result, Class<?>... arguments) {
        return new JavaMemberBinding(MolangArrowSemanticProcess.class, name, MethodType.methodType(result, arguments),
            InvocationKind.STATIC, Optional.empty());
    }

    private static ProgramKey key(String id) {
        return new ProgramKey(ReadOnlyBytes.copyOf(id.getBytes(StandardCharsets.UTF_8)));
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
