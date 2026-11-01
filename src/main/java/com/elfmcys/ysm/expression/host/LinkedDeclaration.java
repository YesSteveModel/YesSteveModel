package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.api.molang.MolangContextView;
import dev.semaweave.*;
import dev.semaweave.registry.*;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.world.entity.Entity;

/** Exact linkage exists only for classes that passed safe startup qualification. */
public record LinkedDeclaration(DeclarationSchema schema, String verifiedModId, Class<?> owner, Class<?> helper,
    ClassLoader loader, List<Member> members) {
    public LinkedDeclaration {
        members = List.copyOf(members);
    }

    public record Member(DeclarationSchema.Member schema, Class<? extends Entity> subject, JavaMemberBinding binding, Optional<JavaMemberBinding> contextFreeTarget) {
    }

    public static LinkedDeclaration link(DeclarationSchema schema, ClassLoader loader)
        throws ReflectiveOperationException {
        return link(schema, loader, null);
    }

    public static LinkedDeclaration link(DeclarationSchema schema, ClassLoader loader, java.net.URL resource)
        throws ReflectiveOperationException {
        return link(schema, loader, resource, schema.builtin() ? "ysm" : schema.modId());
    }

    public static LinkedDeclaration link(DeclarationSchema schema, ClassLoader loader, java.net.URL resource,
        String verifiedModId) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(schema.owner(), false, loader);
        Class<?> helper = Class.forName(schema.helper(), false, loader);
        ClassLoader visibilityRoot = MolangContext.class.getClassLoader();
        if (Class.forName(owner.getName(), false, visibilityRoot) != owner
            || Class.forName(helper.getName(), false, visibilityRoot) != helper)
            throw new IllegalArgumentException("declaration is not exactly visible from the host root");
        if (resource != null) {
            String root = resource.toExternalForm();
            root =
                root.substring(0, root.length() - (DeclarationSchema.RESOURCE_ROOT + schema.owner() + ".bin").length());
            for (Class<?> type : List.of(owner, helper)) {
                String path = type.getName().replace('.', '/') + ".class";
                var origin = type.getResource("/" + path);
                if (origin == null || !origin.toExternalForm().equals(root + path))
                    throw new IllegalArgumentException("implementation/helper is not in the safe resource source");
            }
        }
        var generated = (byte[])helper.getMethod("schema").invoke(null);
        if (!Arrays.equals(generated, schema.encode()))
            throw new IllegalArgumentException("generated helper/schema mismatch");
        if (!Objects.equals(owner.getProtectionDomain().getCodeSource(), helper.getProtectionDomain().getCodeSource()))
            throw new IllegalArgumentException("helper belongs to another source");
        var members = new ArrayList<Member>();
        for (var m : schema.members()) {
            MethodType original = MethodType.fromMethodDescriptorString(m.descriptor(), loader);
            Method method = owner.getMethod(m.javaName(), original.parameterArray());
            if (method.getReturnType() != original.returnType()
                || Modifier.isStatic(method.getModifiers()) != m.isStatic())
                throw new IllegalArgumentException("exact original member mismatch");
            Class<?> subject = Class.forName(m.subject(), false, loader);
            com.elfmcys.ysm.api.molang.DeclarationLinkage.verify(schema, owner, method, m, MolangContextView.class,
                Entity.class);
            verifyRecipe(helper, m.result(), loader, visibilityRoot);
            for (var value : m.fixed())
                verifyRecipe(helper, value, loader, visibilityRoot);
            if (m.tail() != null)
                verifyRecipe(helper, m.tail(), loader, visibilityRoot);
            var parameters = new ArrayList<Class<?>>();
            parameters.add(MolangContext.class);
            if (!m.shape().equals("FIXED"))
                parameters.add(int.class);
            for (var v : m.fixed())
                parameters.add(carrier(v, loader));
            if (m.tail() != null)
                parameters.add(carrier(m.tail(), loader).arrayType());
            Class<?> result = carrier(m.result(), loader);
            var binding = new JavaMemberBinding(helper, m.adapter(), MethodType.methodType(result, parameters),
                m.isStatic() ? InvocationKind.STATIC : InvocationKind.INSTANCE,
                m.isStatic() ? Optional.empty() : Optional.of(providerId(schema)));
            Method adapter = helper.getMethod(m.adapter(), parameters.toArray(Class<?>[]::new));
            if (adapter.getReturnType() != result || Modifier.isStatic(adapter.getModifiers()) != m.isStatic())
                throw new IllegalArgumentException("exact generated adapter mismatch");
            @SuppressWarnings("unchecked")
            Class<? extends Entity> exact = (Class<? extends Entity>)subject;
            Optional<JavaMemberBinding> target = Optional.empty();
            if (!m.contextFreeTarget().isEmpty()) {
                var targetParameters = Arrays.copyOfRange(original.parameterArray(), 1, original.parameterCount());
                var targetMethod = owner.getMethod(m.contextFreeTarget(), targetParameters);
                if (!Modifier.isStatic(targetMethod.getModifiers()) || targetMethod.getReturnType() != result)
                    throw new IllegalArgumentException("exact context-free target mismatch");
                // Only the collector's verified internal source can authorize this optimization.
                if (schema.builtin())
                    target = Optional.of(binding(owner, targetMethod.getName(), result, targetParameters));
            }
            members.add(new Member(m, exact, binding, target));
        }
        if (members.stream().anyMatch(member -> !member.schema().isStatic()))
            com.elfmcys.ysm.api.molang.DeclarationLinkage.verifyComponent(owner, helper,
                com.elfmcys.ysm.client.entity.AnimatableEntity.class, EntityComponent.class,
                Class.forName(schema.componentSubject(), false, loader));
        return new LinkedDeclaration(schema, verifiedModId, owner, helper, loader, members);
    }

    private static void verifyRecipe(Class<?> helper, DeclarationSchema.Value value, ClassLoader loader,
        ClassLoader root) throws ReflectiveOperationException {
        Class<?> type = carrier(value, loader);
        if (!type.isPrimitive() && Class.forName(type.getName(), false, root) != type)
            throw new IllegalArgumentException("carrier is not exactly visible from the host root");
        if (value.element() != null)
            verifyRecipe(helper, value.element(), loader, root);
        for (var field : value.fields())
            verifyRecipe(helper, field.value(), loader, root);
        if (!value.composite())
            return;
        verifyStatic(helper, value.prefix() + "Equal", boolean.class, type, type);
        if (value.array()) {
            verifyStatic(helper, value.prefix() + "Zero", type);
            verifyStatic(helper, value.prefix() + "Length", int.class, type);
            verifyStatic(helper, value.prefix() + "Read", expressionCarrier(value.element(), loader), type, int.class);
        } else
            for (var field : value.fields())
                verifyStatic(helper, value.prefix() + "Read_" + field.name(), expressionCarrier(field.value(), loader),
                    type);
    }

    private static void verifyStatic(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
        throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (method.getReturnType() != result || !Modifier.isStatic(method.getModifiers()))
            throw new IllegalArgumentException("exact value helper mismatch");
    }

    public boolean hasMembers() {
        return members.stream().anyMatch(m -> !m.schema().isStatic());
    }

    public static String providerId(DeclarationSchema schema) {
        return "ysm.declaration:" + schema.owner();
    }

    public Class<?> componentSubject() throws ClassNotFoundException {
        return Class.forName(schema.componentSubject(), false, loader);
    }

    static Class<?> carrier(DeclarationSchema.Value value, ClassLoader loader) throws ClassNotFoundException {
        return switch (value.carrier()) {
            case "float" -> float.class;
            case "int" -> int.class;
            case "boolean" -> boolean.class;
            case "void" -> void.class;
            default -> Class.forName(value.carrier(), false, loader);
        };
    }

    static ValueType type(DeclarationSchema.Value value) {
        return switch (value.kind()) {
            case "FLOAT", "INT" -> BuiltinType.F32;
            case "BOOLEAN" -> BuiltinType.BOOL;
            case "STRING" -> BuiltinType.STRING;
            case "VOID" -> BuiltinType.UNSET;
            case "RECORD" -> value.carrier().equals(MolangVector.class.getName()) ? ExpressionHost.VECTOR
                : new StructType(new SymbolKey("ysm.java:record_" + hash(value.carrier())));
            case "LIST" -> new ListType(type(value.element()), "ysm.java:" + hash(value.identity()));
            case "FLOAT_LIST" -> new ListType(BuiltinType.F32, "ysm:float-list");
            default -> throw new IllegalArgumentException("invalid recipe kind");
        };
    }

    static DefaultValue defaultValue(DeclarationSchema.Value value, String text) {
        Object literal = DeclarationSchema.literal(value, text);
        if (literal instanceof Integer n)
            return DefaultValue.ofInt(n);
        if (literal instanceof Float n)
            return DefaultValue.ofFloat(n);
        if (literal instanceof Boolean b)
            return DefaultValue.ofBoolean(b);
        return DefaultValue.ofString((String)literal);
    }

    static JavaMemberBinding binding(Class<?> owner, String name, Class<?> result, Class<?>... parameters) {
        return new JavaMemberBinding(owner, name, MethodType.methodType(result, parameters), InvocationKind.STATIC,
            Optional.empty());
    }

    static String hash(String text) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static void register(ExpressionRegistry.Builder<MolangContext> builder, List<ExpressionHost.ManifestEntry> manifest,
        List<LinkedDeclaration> declarations) {
        Set<String> types =
            new HashSet<>(Set.of(ExpressionHost.VECTOR.stableId(), ExpressionHost.FLOAT_LIST.stableId()));
        for (var d : declarations) {
            if (d.hasMembers())
                builder.registerProvider(
                    new ProviderSchema(providerId(d.schema()), ExpressionAbi.ANIMATION_NUMBER, d.helper(), true));
            for (var m : d.members()) {
                var s = m.schema();
                for (var v : s.fixed())
                    registerValue(builder, d, v, types);
                if (s.tail() != null)
                    registerValue(builder, d, s.tail(), types);
                registerValue(builder, d, s.result(), types);
                EffectSet effects = s.effect().equals("ACTION")
                    ? EffectSet.of(new EffectId("ysm:context-state"), new EffectId("ysm:action"))
                    : EffectSet.of(new EffectId("ysm:context-state"));
                {
                    var defaults = new ArrayList<DefaultValue>();
                    for (int i = 0; i < s.defaults().size(); i++)
                        defaults.add(defaultValue(s.fixed().get(s.fixed().size() - s.defaults().size() + i),
                            s.defaults().get(i)));
                    builder.registerFunction(
                        new FunctionDescriptor<>(new SymbolKey(s.symbol()), FunctionCallShape.valueOf(s.shape()),
                            s.minimum(), s.maximum(), s.fixed().stream().map(LinkedDeclaration::type).toList(),
                            defaults, Optional.ofNullable(s.tail()).map(LinkedDeclaration::type), type(s.result()),
                            m.binding(), effects, d.verifiedModId(), ExpressionAbi.ANIMATION_NUMBER, Optional.empty(),
                            s.mayReturnMissing()));
                }
                manifest.add(new ExpressionHost.ManifestEntry(s.symbol(), "INVOKE", s.shape(), s.minimum(), s.maximum(),
                    s.fixed().stream().map(v -> type(v).stableId()).toList(), s.defaults(),
                    s.tail() == null ? "" : type(s.tail()).stableId(), type(s.result()).stableId(),
                    "LEFT_TO_RIGHT_EAGER", effects.asSortedSet().stream().map(EffectId::canonical).toList(),
                    "LOCAL_UNSET", "Published", true, m.binding().providerId().orElse(""),
                    m.binding() + ":" + s.descriptor() + ":" + d.verifiedModId() + ":"
                        + hash(Base64.getEncoder().encodeToString(d.schema().encode()))));
            }
        }
    }

    private static void registerValue(ExpressionRegistry.Builder<MolangContext> builder, LinkedDeclaration d,
        DeclarationSchema.Value v, Set<String> seen) {
        if (v.element() != null)
            registerValue(builder, d, v.element(), seen);
        for (var f : v.fields())
            registerValue(builder, d, f.value(), seen);
        if (!v.composite() || !seen.add(type(v).stableId()))
            return;
        try {
            Class<?> carrier = carrier(v, d.loader());
            String id = "ysm.java:" + hash(v.identity()), prefix = v.prefix();
            var equality = binding(d.helper(), prefix + "Equal", boolean.class, carrier, carrier);
            var copies = CopyStrategyDescriptor.generated(id + ":immutable", ExpressionAbi.ANIMATION_NUMBER);
            var display = DisplayStrategyDescriptor.generated(id + ":no-display", ExpressionAbi.ANIMATION_NUMBER);
            var equals = EqualityStrategyDescriptor.linked(id + ":equality", ExpressionAbi.ANIMATION_NUMBER, equality);
            if (v.array())
                builder.registerType(new IndexedListTypeDescriptor(type(v), ExpressionAbi.ANIMATION_NUMBER, carrier,
                    type(v.element()), binding(d.helper(), prefix + "Length", int.class, carrier),
                    binding(d.helper(), prefix + "Read", expressionCarrier(v.element(), d.loader()), carrier,
                        int.class),
                    EffectSet.empty(), EffectSet.empty(), copies, display, equals,
                    Optional.of(binding(d.helper(), prefix + "Zero", carrier))));
            else {
                var fields = new TreeMap<String, StructMemberDescriptor>();
                for (var f : v.fields())
                    fields.put(f.name().toLowerCase(Locale.ROOT),
                        new StructMemberDescriptor(f.name().toLowerCase(Locale.ROOT), type(f.value()),
                            Optional.of(binding(d.helper(), prefix + "Read_" + f.name(),
                                expressionCarrier(f.value(), d.loader()), carrier)),
                            Optional.empty(), EffectSet.empty(), EffectSet.empty()));
                builder.registerType(new HostStructTypeDescriptor(type(v), ExpressionAbi.ANIMATION_NUMBER, carrier,
                    fields, copies, display, equals, Optional.empty()));
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalArgumentException("cannot link value carrier", failure);
        }
    }

    private static Class<?> expressionCarrier(DeclarationSchema.Value v, ClassLoader loader)
        throws ClassNotFoundException {
        return switch (v.kind()) {
            case "INT", "FLOAT" -> float.class;
            case "BOOLEAN" -> boolean.class;
            default -> carrier(v, loader);
        };
    }
}
