package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import dev.semaweave.*;
import dev.semaweave.compilation.*;
import dev.semaweave.program.*;
import dev.semaweave.registry.*;
import java.lang.invoke.MethodType;
import java.util.*;

/** Runtime providers keep scalar compilation fixtures from collapsing into literal-only work. */
public final class ScalarCompilationCases {
    private static final StructType RECORD = new StructType(new SymbolKey("bench:record"));
    private static final StructType OTHER = new StructType(new SymbolKey("bench:other"));
    private static final ListType ARRAY = new ListType(BuiltinType.STRING);
    private static final ListType RECORD_ARRAY = new ListType(RECORD);
    private static final List<String> TRACE = new ArrayList<>();

    public record Value(float number) {
    }

    public record Other(float number) {
    }

    public static float number(Object context) {
        TRACE.add("number");
        return 2;
    }

    public static boolean bool(Object context) {
        TRACE.add("bool");
        return true;
    }

    public static String text(Object context) {
        TRACE.add("text");
        return "false";
    }

    public static Value record(Object context) {
        TRACE.add("record");
        return new Value(0);
    }

    public static Other other(Object context) {
        TRACE.add("other");
        return new Other(0);
    }

    public static List<String> array(Object context) {
        TRACE.add("array");
        return List.of("0");
    }

    public static List<Value> records(Object context) {
        TRACE.add("records");
        return List.of(new Value(0));
    }

    public static float take(Object context, float value) {
        TRACE.add("take:" + value);
        return value;
    }

    public static boolean truth(Object context, boolean value) {
        TRACE.add("truth:" + value);
        return value;
    }

    public static int length(List<?> values) {
        return values.size();
    }

    public static String get(List<String> values, int index) {
        return values.get(index);
    }

    public static Value getRecord(List<Value> values, int index) {
        return values.get(index);
    }

    public static RegistrySnapshot<Object> registry() {
        var hash = ExpressionIdentityHash.SHA_256;
        var builder = ExpressionRegistry.builder(ContextTypes.create("benchmark:scalar", 1, Object.class, hash), hash);
        builder.registerType(struct(RECORD, Value.class));
        builder.registerType(struct(OTHER, Other.class));
        builder.registerType(arrayType(ARRAY, BuiltinType.STRING, "get", String.class));
        builder.registerType(arrayType(RECORD_ARRAY, RECORD, "getRecord", Value.class));
        add(builder, "number", BuiltinType.F32, float.class);
        add(builder, "bool", BuiltinType.BOOL, boolean.class);
        add(builder, "text", BuiltinType.STRING, String.class);
        add(builder, "record", RECORD, Value.class);
        add(builder, "other", OTHER, Other.class);
        add(builder, "array", ARRAY, List.class);
        add(builder, "records", RECORD_ARRAY, List.class);
        add(builder, "take", BuiltinType.F32, float.class, float.class);
        add(builder, "truth", BuiltinType.BOOL, boolean.class, boolean.class);
        return builder.freeze();
    }

    private static HostStructTypeDescriptor struct(StructType type, Class<?> carrier) {
        var fields = new TreeMap<String, StructMemberDescriptor>();
        fields.put("number",
            new StructMemberDescriptor("number", BuiltinType.F32,
                Optional.of(new JavaMemberBinding(carrier, "number", MethodType.methodType(float.class),
                    InvocationKind.RECEIVER, Optional.empty())),
                Optional.empty(), EffectSet.empty(), EffectSet.empty()));
        return new HostStructTypeDescriptor(type, 1, carrier, fields,
            CopyStrategyDescriptor.generated(type.key().canonical() + ":copy", 1),
            DisplayStrategyDescriptor.generated(type.key().canonical() + ":display", 1),
            EqualityStrategyDescriptor.generated(type.key().canonical() + ":equal", 1), Optional.empty());
    }

    private static IndexedListTypeDescriptor arrayType(ListType type, ValueType element, String getter,
        Class<?> carrier) {
        return new IndexedListTypeDescriptor(type, 1, List.class, element, binding("length", int.class, List.class),
            binding(getter, carrier, List.class, int.class), EffectSet.empty(), EffectSet.empty(),
            CopyStrategyDescriptor.generated("bench:" + getter + "-copy", 1),
            DisplayStrategyDescriptor.generated("bench:" + getter + "-display", 1),
            EqualityStrategyDescriptor.generated("bench:" + getter + "-equal", 1), Optional.empty());
    }

    private static void add(ExpressionRegistry.Builder<Object> builder, String name, ValueType result, Class<?> carrier,
        Class<?>... arguments) {
        List<ValueType> parameters = Arrays.stream(arguments)
            .map(type -> (ValueType)(type == boolean.class ? BuiltinType.BOOL : BuiltinType.F32)).toList();
        builder.registerFunction(new FunctionDescriptor<>(new SymbolKey("bench:" + name), FunctionCallShape.FIXED,
            arguments.length, arguments.length, parameters, List.of(), Optional.empty(), result,
            binding(name, carrier,
                MethodType.methodType(carrier, arguments).insertParameterTypes(0, Object.class).parameterArray()),
            EffectSet.of(new EffectId("bench:trace")), "benchmark", 1));
    }

    private static JavaMemberBinding binding(String name, Class<?> result, Class<?>... parameters) {
        return new JavaMemberBinding(ScalarCompilationCases.class, name, MethodType.methodType(result, parameters),
            InvocationKind.STATIC, Optional.empty());
    }

    public static List<ProgramNode> roots(String scenario) {
        var persistent = slot(ProgramNode.SymbolDomain.INSTANCE, "joined");
        var local = slot(ProgramNode.SymbolDomain.LOCAL, "joined");
        return switch (scenario) {
            case "scalarEquivalent" -> List.of(new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD,
                call("take", call("bool")), call("truth", call("number"))));
            case "scalarObjects" ->
                List.of(call("take", call("text")), call("take", call("array")), call("take", call("record")));
            case "scalarCompare" -> List
                .of(call("take", new ProgramNode.Binary(ProgramNode.BinaryOperator.EQ, call("text"), call("number"))));
            case "scalarJoin" -> List.of(new ProgramNode.Assignment(persistent, call("bool")),
                new ProgramNode.Assignment(persistent, call("number")), call("take", persistent),
                new ProgramNode.Block(List.of(new ProgramNode.Assignment(local, call("bool")),
                    new ProgramNode.Assignment(local, call("number")), call("take", local))));
            case "scalarConflicts" -> List.of(new ProgramNode.Assignment(persistent, call("text")),
                new ProgramNode.Assignment(persistent, call("number")), call("take", persistent),
                new ProgramNode.Assignment(slot(ProgramNode.SymbolDomain.INSTANCE, "schemas"), call("record")),
                new ProgramNode.Assignment(slot(ProgramNode.SymbolDomain.INSTANCE, "schemas"), call("other")),
                new ProgramNode.Assignment(slot(ProgramNode.SymbolDomain.INSTANCE, "arrays"), call("array")),
                new ProgramNode.Assignment(slot(ProgramNode.SymbolDomain.INSTANCE, "arrays"), call("records")));
            default -> throw new IllegalArgumentException("unknown scalar scenario: " + scenario);
        };
    }

    private static ProgramNode.Symbol slot(ProgramNode.SymbolDomain domain, String name) {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(domain, name), ProgramNode.AccessForm.READ, List.of());
    }

    private static ProgramNode call(String name, ProgramNode... values) {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "bench:" + name),
            ProgramNode.AccessForm.INVOKE, List.of(values));
    }

    static Map<String, Object> execute(CompiledModuleResource<Object> resource, CompiledArtifact artifact,
        RegistrySnapshot<Object> registry, String scenario) throws Throwable {
        var linked = RegistryRuntimeLinkage.<Object>create(ProviderInstanceTable.create(registry, Map.of()),
            RuntimeSymbolTable.empty());
        var linkage = new RuntimeLinkage<Object>() {
            public ContextType<Object> contextType() {
                return registry.contextType();
            }

            public RegistryRuntimeLinkage<Object> registry() {
                return linked;
            }

            public BakedConstantTable constants() {
                return artifact.constants();
            }

            public RuntimeDiagnostics diagnostics() {
                return RuntimeDiagnostics.none();
            }

            public ResourceLease<Object> retainResource() {
                return resource.retain();
            }
        };
        var values = new ArrayList<Float>();
        TRACE.clear();
        try (var instance = resource.newInstance(linkage)) {
            Object context = new Object();
            var subject = instance.bindSubject(context);
            for (int id = 0; id < roots(scenario).size(); id++)
                values.add(instance.executeFloat(instance.bindProgram(new ProgramId(id), ProgramShape.F32), context,
                    subject, new Frame()));
        }
        if (Boolean.getBoolean("scalar.requireOracle") && !scenario.equals("scalarConflicts")) {
            var expected = switch (scenario) {
                case "scalarEquivalent" -> List.of(2f);
                case "scalarObjects" -> List.of(1f, 1f, 1f);
                case "scalarCompare" -> List.of(0f);
                case "scalarJoin" -> List.of(1f, 2f, 2f, 2f);
                default -> throw new AssertionError(scenario);
            };
            var trace = switch (scenario) {
                case "scalarEquivalent" -> List.of("bool", "take:1.0", "number", "truth:true");
                case "scalarObjects" -> List.of("text", "take:1.0", "array", "take:1.0", "record", "take:1.0");
                case "scalarCompare" -> List.of("text", "number", "take:0.0");
                case "scalarJoin" -> List.of("bool", "number", "take:2.0", "bool", "number", "take:2.0");
                default -> throw new AssertionError(scenario);
            };
            if (!values.equals(expected) || !TRACE.equals(trace) || !artifact.report().localRecoveries().isEmpty()
                || !artifact.report().replacements().isEmpty())
                throw new AssertionError("scalar oracle: " + scenario + " values=" + values + " trace=" + TRACE);
        }
        return Map.of("values", values, "trace", List.copyOf(TRACE));
    }

    private static final class Frame implements ExecutionFrame {
        private boolean active;

        public boolean actionAllowed() {
            return active;
        }

        public void beginTopLevel(boolean allowAction) {
            if (active)
                throw new AssertionError("active frame");
            active = true;
        }

        public void endTopLevel() {
            active = false;
        }

        public void chargeLoopIteration() {}

        public void chargeProviderCall() {}

        public void chargeControlTransfer() {}

        public boolean tryEnterUserFunction(int id) {
            return true;
        }

        public void exitUserFunction(int id) {}

        public ArgumentScratch acquireArgumentScratch(int size) {
            throw new AssertionError("unexpected scratch");
        }

        public void releaseArgumentScratch(ArgumentScratch scratch) {
            throw new AssertionError("unexpected scratch");
        }
    }
}
