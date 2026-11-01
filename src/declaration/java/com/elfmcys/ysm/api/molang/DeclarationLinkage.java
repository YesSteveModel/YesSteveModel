package com.elfmcys.ysm.api.molang;

import java.lang.reflect.*;
import java.util.*;

/** Reflection supplies facts after qualification; safe schema remains the common rule authority. */
public final class DeclarationLinkage {
    private DeclarationLinkage() {}

    public static void verify(DeclarationSchema schema, Class<?> owner, Method method, DeclarationSchema.Member member,
        Class<?> context, Class<?> entity) throws ReflectiveOperationException {
        Map<TypeVariable<?>, Type> environment = environment(owner, method.getDeclaringClass(), Map.of());
        if (environment == null || !Modifier.isPublic(method.getModifiers()) || method.getTypeParameters().length != 0
            || Modifier.isStatic(method.getModifiers()) != member.isStatic())
            throw new IllegalArgumentException("original member does not match declaration");
        Type[] parameters = method.getGenericParameterTypes();
        if (parameters.length == 0)
            throw new IllegalArgumentException("missing context view");
        Type first = resolve(parameters[0], environment);
        Class<?> subject = Class.forName(member.subject(), false, owner.getClassLoader());
        Class<?> view = context;
        if (!(first instanceof ParameterizedType p) || p.getRawType() != view || p.getActualTypeArguments().length != 1
            || p.getActualTypeArguments()[0] != subject || !entity.isAssignableFrom(subject))
            throw new IllegalArgumentException("exact resolved context/subject mismatch");
        if (!member.isStatic()) {
            Class<?> component =
                Class.forName("com.elfmcys.ysm.expression.host.EntityComponent", false, owner.getClassLoader());
            Map<TypeVariable<?>, Type> componentEnvironment = environment(owner, component, Map.of());
            if (componentEnvironment == null
                || resolve(component.getTypeParameters()[0], componentEnvironment) != subject)
                throw new IllegalArgumentException("exact resolved component/context mismatch");
        }
        int offset = member.shape().equals("FIXED") ? 1 : 2;
        if (parameters.length != offset + member.fixed().size() + (member.tail() == null ? 0 : 1)
            || offset == 2 && resolve(parameters[1], environment) != int.class
            || method.isVarArgs() != (member.tail() != null))
            throw new IllegalArgumentException("original call shape mismatch");
        for (int i = 0; i < member.fixed().size(); i++)
            verifyValue(member.fixed().get(i), resolve(parameters[offset + i], environment), owner, context);
        if (member.tail() != null) {
            Type array = resolve(parameters[parameters.length - 1], environment);
            Type element = array instanceof Class<?> c && c.isArray() ? c.getComponentType()
                : array instanceof GenericArrayType a ? a.getGenericComponentType() : null;
            verifyValue(member.tail(), element, owner, context);
        }
        verifyValue(member.result(), resolve(method.getGenericReturnType(), environment), owner, context);
        Class<?> nullable =
            Class.forName("com.elfmcys.ysm.api.annotation.MolangNullable", false, context.getClassLoader());
        boolean actualNullable = Arrays.stream(method.getAnnotations()).anyMatch(a -> a.annotationType() == nullable);
        if (actualNullable != member.nullableRecord())
            throw new IllegalArgumentException("exact return nullability mismatch");
    }

    public static void verifyComponent(Class<?> declaration, Class<?> helper, Class<?> animatable, Class<?> component,
        Class<?> subject) throws ReflectiveOperationException {
        Constructor<?> constructor = declaration.getConstructor(animatable);
        Type parameter = constructor.getGenericParameterTypes()[0];
        if (constructor.getTypeParameters().length != 0 || !(parameter instanceof ParameterizedType p)
            || p.getRawType() != animatable || p.getActualTypeArguments().length != 1
            || p.getActualTypeArguments()[0] != subject)
            throw new IllegalArgumentException("exact resolved owner/component subject mismatch");
        if (!component.isAssignableFrom(helper))
            throw new IllegalArgumentException("generated helper does not implement component lifecycle");
        helper.getConstructor(animatable);
        if (helper.getMethod("provider").getReturnType() != declaration)
            throw new IllegalArgumentException("generated helper provider mismatch");
    }

    private static void verifyValue(DeclarationSchema.Value recipe, Type type, Class<?> owner, Class<?> api)
        throws ReflectiveOperationException {
        Class<?> expected = carrier(recipe.carrier(), owner.getClassLoader());
        Type raw = type instanceof ParameterizedType p ? p.getRawType() : type;
        if (raw != expected)
            throw new IllegalArgumentException("exact value carrier mismatch: " + recipe.carrier());
        if (recipe.kind().equals("LIST")) {
            if (!(type instanceof ParameterizedType p) || p.getActualTypeArguments().length != 1)
                throw new IllegalArgumentException("raw value carrier");
            verifyValue(recipe.element(), p.getActualTypeArguments()[0], owner, api);
        } else if (recipe.kind().equals("RECORD")) {
            if (!expected.isRecord() || expected.getTypeParameters().length != 0
                || !Modifier.isPublic(expected.getModifiers())
                || !Objects.equals(expected.getProtectionDomain().getCodeSource(),
                    owner.getProtectionDomain().getCodeSource())
                    && !Objects.equals(expected.getProtectionDomain().getCodeSource(),
                        api.getProtectionDomain().getCodeSource()))
                throw new IllegalArgumentException("record carrier belongs to another source");
            RecordComponent[] fields = expected.getRecordComponents();
            if (fields.length != recipe.fields().size())
                throw new IllegalArgumentException("record schema mismatch");
            for (int i = 0; i < fields.length; i++) {
                if (!fields[i].getName().equals(recipe.fields().get(i).name()))
                    throw new IllegalArgumentException("record field mismatch");
                verifyValue(recipe.fields().get(i).value(), fields[i].getGenericType(), owner, api);
            }
        }
    }

    private static Class<?> carrier(String name, ClassLoader loader) throws ClassNotFoundException {
        return switch (name) {
            case "int" -> int.class;
            case "float" -> float.class;
            case "boolean" -> boolean.class;
            case "void" -> void.class;
            default -> Class.forName(name, false, loader);
        };
    }

    private static Map<TypeVariable<?>, Type> environment(Type current, Class<?> target,
        Map<TypeVariable<?>, Type> inherited) {
        Class<?> raw = current instanceof ParameterizedType p ? (Class<?>)p.getRawType() : (Class<?>)current;
        var local = new HashMap<>(inherited);
        if (current instanceof ParameterizedType p) {
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = p.getActualTypeArguments();
            for (int i = 0; i < variables.length; i++)
                local.put(variables[i], resolve(arguments[i], inherited));
        }
        if (raw == target)
            return local;
        var parents = new ArrayList<Type>(List.of(raw.getGenericInterfaces()));
        if (raw.getGenericSuperclass() != null)
            parents.add(raw.getGenericSuperclass());
        for (Type parent : parents) {
            var found = environment(parent, target, local);
            if (found != null)
                return found;
        }
        return null;
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> environment) {
        if (type instanceof TypeVariable<?> v) {
            Type resolved = environment.get(v);
            return resolved == null || resolved == v ? v : resolve(resolved, environment);
        }
        if (type instanceof ParameterizedType p) {
            Type[] arguments =
                Arrays.stream(p.getActualTypeArguments()).map(t -> resolve(t, environment)).toArray(Type[]::new);
            return new ParameterizedType() {
                public Type[] getActualTypeArguments() {
                    return arguments.clone();
                }

                public Type getRawType() {
                    return p.getRawType();
                }

                public Type getOwnerType() {
                    return p.getOwnerType();
                }
            };
        }
        if (type instanceof GenericArrayType a) {
            Type element = resolve(a.getGenericComponentType(), environment);
            return element instanceof Class<?> c ? c.arrayType() : (GenericArrayType)() -> element;
        }
        return type;
    }
}
