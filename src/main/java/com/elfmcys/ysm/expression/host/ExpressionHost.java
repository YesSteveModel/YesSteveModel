package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.compatibility.AbiVersion;

import dev.semaweave.BuiltinType;
import dev.semaweave.CompilationPolicy;
import dev.semaweave.ContextType;
import dev.semaweave.ContextTypes;
import dev.semaweave.EffectSet;
import dev.semaweave.EngineAbi;
import dev.semaweave.ExecutionLimits;
import dev.semaweave.HostAbi;
import dev.semaweave.ListType;
import dev.semaweave.MissingSymbolPolicy;
import dev.semaweave.StructType;
import dev.semaweave.SymbolKey;
import dev.semaweave.ValueType;
import dev.semaweave.compilation.BindingPlan;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;
import dev.semaweave.registry.CopyStrategyDescriptor;
import dev.semaweave.registry.ConversionDescriptor;
import dev.semaweave.registry.ConversionFlags;
import dev.semaweave.registry.DisplayStrategyDescriptor;
import dev.semaweave.registry.EqualityStrategyDescriptor;
import dev.semaweave.registry.ExpressionRegistry;
import dev.semaweave.registry.HostStructTypeDescriptor;
import dev.semaweave.registry.InvocationKind;
import dev.semaweave.registry.IndexedListTypeDescriptor;
import dev.semaweave.registry.JavaMemberBinding;
import dev.semaweave.registry.RegistrySnapshot;
import dev.semaweave.registry.StructMemberDescriptor;
import com.elfmcys.ysm.expression.host.builtin.MathBuiltins;

import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** The single frozen registry, context ABI and provider linkage authority for YSM. */
public final class ExpressionHost {
    public static final int ABI_VERSION = ExpressionAbi.ANIMATION_NUMBER;
    public static final StructType VECTOR = new StructType(new SymbolKey("ysm:vector3"));
    public static final ListType FLOAT_LIST = new ListType(BuiltinType.F32, "ysm:float-list");
    public static final ListType EVENT_F32_ARGUMENTS = FLOAT_LIST;

    private static final String OWNER_ID = "ysm:expression-host";
    private static final Map<String,
        JavaMemberBinding> CONTEXT_FREE_MATH = Map.ofEntries(mathCall("abs", "abs", 1), mathCall("clamp", "clamp", 3),
            mathCall("cos", "cos", 1), mathCall("exp", "exp", 1), mathCall("floor", "floor", 1),
            mathCall("lerp", "lerp", 3), mathCall("max", "max", 2), mathCall("min", "min", 2),
            mathCall("mod", "mod", 2), mathCall("pow", "pow", 2), mathCall("round", "round", 1),
            mathCall("sin", "sin", 1), mathCall("sqrt", "sqrt", 1), mathCall("min_angle", "minAngle", 1));

    private static Map.Entry<String, JavaMemberBinding> mathCall(String symbol, String method, int arity) {
        Class<?>[] parameters = new Class<?>[arity];
        java.util.Arrays.fill(parameters, float.class);
        return Map.entry("math:" + symbol,
            LinkedDeclaration.binding(MathBuiltins.class, method, float.class, parameters));
    }

    private final HostAvailability availability;
    private final ExecutionLimits executionLimits;
    private final ContextType<MolangContext> contextType;
    private final RegistrySnapshot<MolangContext> registry;
    private final List<BuiltinDescriptor> builtins;
    private final List<LinkedDeclaration> declarations;
    private final DeclarationEnvironment declarationEnvironment;
    private final ExpressionBinding expressionBinding;
    private final CompilationPolicy compilationPolicy;
    private final List<ManifestEntry> manifest;
    private final HostAbi hostAbi;
    private final ExpressionHost builtinOnly;

    private ExpressionHost(HostAvailability availability, ExecutionLimits executionLimits,
        ContextType<MolangContext> contextType, RegistrySnapshot<MolangContext> registry,
        List<LinkedDeclaration> declarations, CompilationPolicy compilationPolicy, List<ManifestEntry> manifest,
        HostAbi hostAbi, ExpressionHost builtinOnly) {
        this.availability = availability;
        this.executionLimits = executionLimits;
        this.contextType = contextType;
        this.registry = registry;
        this.declarations = List.copyOf(declarations);
        this.builtins = declarations.stream().filter(d -> d.schema().builtin()).flatMap(d -> d.members().stream().map(
            m -> new BuiltinDescriptor(BuiltinDeclarations.surface(d.schema(), m.schema()), m.binding(), m.subject())))
            .toList();
        this.declarationEnvironment = DeclarationEnvironment.create(this.declarations);
        this.expressionBinding = createBinding();
        this.compilationPolicy = compilationPolicy;
        this.manifest = manifest;
        this.hostAbi = hostAbi;
        this.builtinOnly = builtinOnly == null ? this : builtinOnly;
    }

    public static ExpressionHost create(HostAvailability availability) {
        return MolangExtensions.admit(availability, List.of()).host();
    }

    public static ExpressionHost create(HostAvailability availability, ExecutionLimits executionLimits) {
        return create(availability, ExpressionPolicy.create(ExpressionPolicy.COMPILER_LIMITS, executionLimits));
    }

    public static ExpressionHost create(HostAvailability availability, CompilationPolicy policy) {
        return MolangExtensions.admit(availability, List.of(), policy).host();
    }

    static ExpressionHost createFrozen(HostAvailability availability, CompilationPolicy policy,
        List<LinkedDeclaration> declarations, List<LinkedDeclaration> builtinDeclarations) {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(policy, "policy");
        var executionLimits = policy.executionLimits();
        var hash = ExpressionIdentityHash.SHA_256;
        var contextType = ContextTypes.create("ysm:invocation-context", ABI_VERSION, MolangContext.class, hash);
        var builder = ExpressionRegistry.builder(contextType, hash, policy.compilerLimits());
        var manifest = new ArrayList<ManifestEntry>();

        registerVector(builder);
        registerEventArguments(builder, manifest);
        registerScalarConversions(builder, manifest);
        LinkedDeclaration.register(builder, manifest, declarations);
        manifest.sort(Comparator.comparing(ManifestEntry::stableId).thenComparing(ManifestEntry::accessKind));

        var registry = builder.freeze();
        var stableManifest = List.copyOf(manifest);
        var hostAbi = hostAbi(registry, stableManifest, executionLimits);
        var builtinOnly = declarations.equals(builtinDeclarations) ? null
            : createFrozen(availability, policy, builtinDeclarations, builtinDeclarations);
        return new ExpressionHost(availability, executionLimits, registry.contextType(), registry, declarations, policy,
            stableManifest, hostAbi, builtinOnly);
    }

    /** Projects the already admitted builtin source without repeating qualification or linkage. */
    public ExpressionHost builtinOnly() {
        return builtinOnly;
    }

    public List<LinkedDeclaration> declarations() {
        return declarations;
    }

    public HostAvailability availability() {
        return availability;
    }

    public ExecutionLimits executionLimits() {
        return executionLimits;
    }

    public ContextType<MolangContext> contextType() {
        return contextType;
    }

    public RegistrySnapshot<MolangContext> registry() {
        return registry;
    }

    public List<BuiltinDescriptor> builtins() {
        return builtins;
    }

    public DeclarationEnvironment declarationEnvironment() {
        return declarationEnvironment;
    }

    public ExpressionBinding expressionBinding() {
        return expressionBinding;
    }

    public ExpressionBinding expressionBinding(Class<?> subjectType) {
        return new ExpressionBinding(declarationEnvironment,
            expressionBinding.bindingPlan().withRootSubjectType(subjectType));
    }

    public CompilationPolicy compilationPolicy() {
        return compilationPolicy;
    }

    public List<ManifestEntry> manifest() {
        return manifest;
    }

    public HostAbi hostAbi() {
        return hostAbi;
    }

    private ExpressionBinding createBinding() {
        var builder = BindingPlan.builder()
            .invocationValidator(
                LinkedDeclaration.binding(MolangContext.class, "validateInvocation", void.class, MolangContext.class))
            .subjectAccessor(
                LinkedDeclaration.binding(MolangContext.class, "subjectOrNull", Object.class, MolangContext.class));
        for (var declaration : declarations)
            for (var member : declaration.members()) {
                builder.guard(member.binding(), member.binding(),
                    dev.semaweave.registry.DeclarationEffect.valueOf(member.schema().effect()), member.subject());
                if (declaration.owner() == MathBuiltins.class && declaration.schema().builtin()) {
                    var target = CONTEXT_FREE_MATH.get(member.schema().symbol());
                    if (target != null) {
                        if (!member.schema().javaName().equals(target.methodName()))
                            throw new IllegalArgumentException("trusted math declaration member mismatch");
                        builder.contextFreeCall(member.binding(), target);
                    }
                }
            }
        var plan = builder.build();
        plan.validate(registry);
        return new ExpressionBinding(declarationEnvironment, plan);
    }

    public record ManifestEntry(String stableId, String accessKind, String callShape, int minimumArguments,
        int maximumArguments, List<String> argumentTypes, List<String> defaultArguments, String variadicElementType,
        String resultType, String evaluationOrder, List<String> effects, String missingPolicy, String availability,
        boolean available, String providerId, String javaMember) {
        public ManifestEntry {
            argumentTypes = List.copyOf(argumentTypes);
            defaultArguments = List.copyOf(defaultArguments);
            effects = List.copyOf(effects);
        }
    }

    private static void registerVector(ExpressionRegistry.Builder<MolangContext> builder) {
        var members = new TreeMap<String, StructMemberDescriptor>();
        members.put("x", vectorMember("x", "vectorX"));
        members.put("y", vectorMember("y", "vectorY"));
        members.put("z", vectorMember("z", "vectorZ"));
        builder.registerType(new HostStructTypeDescriptor(VECTOR, ABI_VERSION, MolangVector.class, members,
            copyStrategy("vector", MolangVector.class, "copyVector"),
            displayStrategy("vector", MolangVector.class, "displayVector"),
            equalityStrategy("vector", MolangVector.class, "equalVector"),
            Optional.of(staticBinding(HostBindings.class, "zeroVector", MethodType.methodType(MolangVector.class)))));
    }

    private static void registerEventArguments(ExpressionRegistry.Builder<MolangContext> builder,
        List<ManifestEntry> manifest) {
        var carrier = it.unimi.dsi.fastutil.floats.FloatList.class;
        builder.registerType(new IndexedListTypeDescriptor(FLOAT_LIST, ABI_VERSION, carrier, BuiltinType.F32,
            new JavaMemberBinding(carrier, "size", MethodType.methodType(int.class), InvocationKind.RECEIVER,
                Optional.empty()),
            new JavaMemberBinding(carrier, "getFloat", MethodType.methodType(float.class, int.class),
                InvocationKind.RECEIVER, Optional.empty()),
            EffectSet.empty(), EffectSet.empty(),
            CopyStrategyDescriptor.generated("ysm:float-list:immutable", ABI_VERSION),
            DisplayStrategyDescriptor.generated("ysm:float-list:display", ABI_VERSION),
            EqualityStrategyDescriptor.linked("ysm:float-list:equality", ABI_VERSION,
                staticBinding(HostBindings.class, "equalFloatList",
                    MethodType.methodType(boolean.class, carrier, carrier))),
            Optional.of(staticBinding(HostBindings.class, "emptyFloatList", MethodType.methodType(carrier)))));
    }

    private static void registerScalarConversions(ExpressionRegistry.Builder<MolangContext> builder,
        List<ManifestEntry> manifest) {
        registerConversion(builder, manifest, BuiltinType.BOOL, BuiltinType.F32, HostBindings.class, "booleanToFloat",
            MethodType.methodType(float.class, boolean.class), new ConversionFlags(true, false, false));
        registerConversion(builder, manifest, BuiltinType.F32, BuiltinType.BOOL, HostBindings.class, "floatToBoolean",
            MethodType.methodType(boolean.class, float.class), new ConversionFlags(false, false, false));
    }

    private static void registerConversion(ExpressionRegistry.Builder<MolangContext> builder,
        List<ManifestEntry> manifest, ValueType from, ValueType to, Class<?> owner, String methodName,
        MethodType methodType, ConversionFlags flags) {
        var method = staticBinding(owner, methodName, methodType);
        builder.registerConversion(new ConversionDescriptor(from, to, ExpressionAbi.ANIMATION_NUMBER, true, flags,
            method, Optional.of(method), EffectSet.empty(), OWNER_ID, ABI_VERSION));
        manifest.add(new ManifestEntry("ysm:conversion:" + from.stableId() + "->" + to.stableId(), "CONVERT",
            "CONVERSION", 1, 1, List.of(from.stableId()), List.of(), "", to.stableId(), "DIRECT", List.of(),
            MissingSymbolPolicy.ERROR.name(), "core", true, OWNER_ID, owner.getName() + '.' + methodName));
    }

    private static HostAbi hostAbi(RegistrySnapshot<MolangContext> registry, List<ManifestEntry> manifest,
        ExecutionLimits executionLimits) {
        var canonical = new StringBuilder();
        appendField(canonical, "ysm:expression-host-abi:" + AbiVersion.CACHE_ANIMATION);
        appendField(canonical, HostCompatibility.SHA256);
        var engineAbi = EngineAbi.current();
        appendField(canonical, engineAbi.packageName());
        appendField(canonical, Integer.toString(engineAbi.version()));
        appendField(canonical, registry.fingerprint().algorithm().stableId());
        appendField(canonical, Integer.toString(registry.fingerprint().algorithm().abiVersion()));
        appendField(canonical, registry.fingerprint().value().toString());
        appendField(canonical, Integer.toString(executionLimits.maxLoopIterations()));
        appendField(canonical, Integer.toString(executionLimits.maxUserFunctionDepth()));
        appendField(canonical, Integer.toString(executionLimits.maxProviderCalls()));
        appendField(canonical, Integer.toString(executionLimits.maxControlTransfers()));
        appendField(canonical, Integer.toString(executionLimits.maxArgumentScratch()));
        appendField(canonical, Integer.toString(EffectBatch.DEFAULT_MAX_COMMANDS));
        for (var entry : manifest) {
            appendField(canonical, entry.stableId());
            appendField(canonical, entry.accessKind());
            appendField(canonical, entry.callShape());
            appendField(canonical, Integer.toString(entry.minimumArguments()));
            appendField(canonical, Integer.toString(entry.maximumArguments()));
            appendFields(canonical, entry.argumentTypes());
            appendFields(canonical, entry.defaultArguments());
            appendField(canonical, entry.variadicElementType());
            appendField(canonical, entry.resultType());
            appendField(canonical, entry.evaluationOrder());
            appendFields(canonical, entry.effects());
            appendField(canonical, entry.missingPolicy());
            appendField(canonical, entry.availability());
            appendField(canonical, Boolean.toString(entry.available()));
            appendField(canonical, entry.providerId());
            appendField(canonical, entry.javaMember());
        }
        var preimage = ReadOnlyBytes.copyOf(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return new HostAbi(OWNER_ID, AbiVersion.CACHE_ANIMATION,
            Fingerprint.compute(ExpressionIdentityHash.SHA_256, preimage));
    }

    private static void appendField(StringBuilder output, String value) {
        output.append(value.length()).append(':').append(value);
    }

    private static void appendFields(StringBuilder output, List<String> values) {
        appendField(output, Integer.toString(values.size()));
        values.forEach(value -> appendField(output, value));
    }

    private static StructMemberDescriptor vectorMember(String name, String method) {
        return new StructMemberDescriptor(name, BuiltinType.F32,
            Optional
                .of(staticBinding(HostBindings.class, method, MethodType.methodType(float.class, MolangVector.class))),
            Optional.empty(), EffectSet.empty(), EffectSet.empty());
    }

    private static CopyStrategyDescriptor copyStrategy(String name, Class<?> type, String method) {
        return copyStrategy(name, type, method, HostBindings.class);
    }

    private static CopyStrategyDescriptor copyStrategy(String name, Class<?> type, String method, Class<?> owner) {
        var binding = staticBinding(owner, method, MethodType.methodType(type, type));
        return CopyStrategyDescriptor.linked("ysm:" + name + "-copy", ABI_VERSION, binding, binding);
    }

    private static DisplayStrategyDescriptor displayStrategy(String name, Class<?> type, String method) {
        return displayStrategy(name, type, method, HostBindings.class);
    }

    private static DisplayStrategyDescriptor displayStrategy(String name, Class<?> type, String method,
        Class<?> owner) {
        return DisplayStrategyDescriptor.linked("ysm:" + name + "-display", ABI_VERSION,
            staticBinding(owner, method, MethodType.methodType(String.class, type)));
    }

    private static EqualityStrategyDescriptor equalityStrategy(String name, Class<?> type, String method) {
        return equalityStrategy(name, type, method, HostBindings.class);
    }

    private static EqualityStrategyDescriptor equalityStrategy(String name, Class<?> type, String method,
        Class<?> owner) {
        return EqualityStrategyDescriptor.linked("ysm:" + name + "-equality", ABI_VERSION,
            staticBinding(owner, method, MethodType.methodType(boolean.class, type, type)));
    }

    private static JavaMemberBinding staticBinding(Class<?> owner, String methodName, MethodType methodType) {
        return new JavaMemberBinding(owner, methodName, methodType, InvocationKind.STATIC, Optional.empty());
    }

}
