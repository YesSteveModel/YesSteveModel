package com.elfmcys.ysm.expression.host;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Validates the exact optional API references reachable from generated built-in owners. */
final class OptionalLinkageVerifier {
    private static final String COMPAT_PACKAGE = "com.elfmcys.ysm.client.compat.";

    private OptionalLinkageVerifier() {}

    static void verify(OptionalCapability capability, ClassLoader loader) {
        var roots = BuiltinDeclarations.surfaces().stream()
            .filter(surface -> surface.optionalCapability() == capability).map(BuiltinSurface::declaringOwner)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        verify(loader, roots, capability::ownsExternalClass, binaryName -> binaryName.startsWith(COMPAT_PACKAGE));
    }

    static void verify(ClassLoader loader, Set<String> roots, Predicate<String> externalClass,
        Predicate<String> followedClass) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(externalClass, "externalClass");
        Objects.requireNonNull(followedClass, "followedClass");
        var pending = new ArrayDeque<MethodRef>();
        roots.forEach(root -> pending.add(MethodRef.all(root)));
        var visited = new HashSet<MethodRef>();
        while (!pending.isEmpty()) {
            var reference = pending.removeFirst();
            if (visited.add(reference)) {
                scan(loader, reference, externalClass, followedClass, pending);
            }
        }
    }

    private static void scan(ClassLoader loader, MethodRef reference, Predicate<String> externalClass,
        Predicate<String> followedClass, ArrayDeque<MethodRef> pending) {
        String resourceName = reference.owner().replace('.', '/') + ".class";
        try (var input = loader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw missingClass(reference.owner(), null);
            }
            var matched = new boolean[] {reference.name() == null || "<clinit>".equals(reference.name())};
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                    if (reference.name() != null
                        && (!reference.name().equals(name) || !reference.descriptor().equals(descriptor))) {
                        return null;
                    }
                    matched[0] = true;
                    return visitor(loader, reference.owner(), externalClass, followedClass, pending);
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!matched[0]) {
                throw new NoSuchMethodError(reference.toString());
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read optional linkage owner " + reference.owner(), failure);
        }
    }

    private static MethodVisitor visitor(ClassLoader loader, String currentOwner, Predicate<String> externalClass,
        Predicate<String> followedClass, ArrayDeque<MethodRef> pending) {
        return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitTypeInsn(int opcode, String type) {
                requireType(loader, Type.getObjectType(type), externalClass);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                var binaryOwner = owner.replace('/', '.');
                requireType(loader, Type.getType(descriptor), externalClass);
                if (externalClass.test(binaryOwner)) {
                    requireField(loader, opcode, binaryOwner, name, descriptor);
                }
                if ((opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC)
                    && shouldFollow(currentOwner, binaryOwner, followedClass)) {
                    pending.add(new MethodRef(binaryOwner, "<clinit>", "()V"));
                }
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                var binaryOwner = owner.replace('/', '.');
                requireMethodType(loader, descriptor, externalClass);
                if (externalClass.test(binaryOwner)) {
                    requireMethod(loader, opcode, binaryOwner, name, descriptor);
                }
                if (shouldFollow(currentOwner, binaryOwner, followedClass)) {
                    pending.add(new MethodRef(binaryOwner, name, descriptor));
                    if (opcode == Opcodes.INVOKESTATIC) {
                        pending.add(new MethodRef(binaryOwner, "<clinit>", "()V"));
                    }
                }
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
                requireMethodType(loader, descriptor, externalClass);
                requireHandle(loader, bootstrapMethodHandle, externalClass);
                for (var argument : bootstrapMethodArguments) {
                    if (argument instanceof Type type) {
                        requireType(loader, type, externalClass);
                    } else if (argument instanceof Handle handle) {
                        requireHandle(loader, handle, externalClass);
                    }
                }
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof Type type) {
                    requireType(loader, type, externalClass);
                }
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                requireType(loader, Type.getType(descriptor), externalClass);
            }

            @Override
            public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                org.objectweb.asm.Label handler, String type) {
                if (type != null) {
                    requireType(loader, Type.getObjectType(type), externalClass);
                }
            }
        };
    }

    private static boolean shouldFollow(String currentOwner, String targetOwner, Predicate<String> followedClass) {
        return currentOwner.equals(targetOwner) || followedClass.test(targetOwner);
    }

    private static void requireHandle(ClassLoader loader, Handle handle, Predicate<String> externalClass) {
        var owner = handle.getOwner().replace('/', '.');
        switch (handle.getTag()) {
            case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> {
                requireType(loader, Type.getType(handle.getDesc()), externalClass);
                if (externalClass.test(owner)) {
                    requireField(loader, handle.getTag(), owner, handle.getName(), handle.getDesc());
                }
            }
            case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL,
                Opcodes.H_INVOKEINTERFACE -> {
                requireMethodType(loader, handle.getDesc(), externalClass);
                if (externalClass.test(owner)) {
                    requireMethod(loader, handle.getTag(), owner, handle.getName(), handle.getDesc());
                }
            }
            default -> throw new IllegalArgumentException("Unsupported optional linkage handle " + handle);
        }
    }

    private static void requireMethodType(ClassLoader loader, String descriptor, Predicate<String> externalClass) {
        var type = Type.getMethodType(descriptor);
        requireType(loader, type.getReturnType(), externalClass);
        for (var argument : type.getArgumentTypes()) {
            requireType(loader, argument, externalClass);
        }
    }

    private static void requireType(ClassLoader loader, Type type, Predicate<String> externalClass) {
        if (type.getSort() == Type.ARRAY) {
            requireType(loader, type.getElementType(), externalClass);
        } else if (type.getSort() == Type.METHOD) {
            requireMethodType(loader, type.getDescriptor(), externalClass);
        } else if (type.getSort() == Type.OBJECT && externalClass.test(type.getClassName())) {
            load(loader, type.getClassName());
        }
    }

    private static void requireMethod(ClassLoader loader, int opcode, String ownerName, String name,
        String descriptor) {
        var owner = load(loader, ownerName);
        var methodType = Type.getMethodType(descriptor);
        var parameters = classes(loader, methodType.getArgumentTypes());
        if ("<init>".equals(name)) {
            Constructor<?> constructor;
            try {
                constructor = owner.getDeclaredConstructor(parameters);
            } catch (NoSuchMethodException failure) {
                throw new NoSuchMethodError(ownerName + "." + name + descriptor);
            }
            requirePublic(owner, constructor.getModifiers(), ownerName + "." + name + descriptor);
            return;
        }
        var method = findMethod(owner, name, parameters);
        if (method == null || method.getReturnType() != classFor(loader, methodType.getReturnType())) {
            throw new NoSuchMethodError(ownerName + "." + name + descriptor);
        }
        boolean expectedStatic = opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.H_INVOKESTATIC;
        if (Modifier.isStatic(method.getModifiers()) != expectedStatic) {
            throw new IncompatibleClassChangeError(ownerName + "." + name + descriptor);
        }
        requirePublic(method.getDeclaringClass(), method.getModifiers(), ownerName + "." + name + descriptor);
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>[] parameters) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
            }
            for (var type : current.getInterfaces()) {
                var method = findMethod(type, name, parameters);
                if (method != null) {
                    return method;
                }
            }
        }
        return null;
    }

    private static void requireField(ClassLoader loader, int opcode, String ownerName, String name, String descriptor) {
        var owner = load(loader, ownerName);
        var field = findField(owner, name);
        if (field == null || field.getType() != classFor(loader, Type.getType(descriptor))) {
            throw new NoSuchFieldError(ownerName + "." + name + ":" + descriptor);
        }
        boolean expectedStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
            || opcode == Opcodes.H_GETSTATIC || opcode == Opcodes.H_PUTSTATIC;
        if (Modifier.isStatic(field.getModifiers()) != expectedStatic) {
            throw new IncompatibleClassChangeError(ownerName + "." + name + ":" + descriptor);
        }
        requirePublic(field.getDeclaringClass(), field.getModifiers(), ownerName + "." + name + ":" + descriptor);
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
            for (var type : current.getInterfaces()) {
                var field = findField(type, name);
                if (field != null) {
                    return field;
                }
            }
        }
        return null;
    }

    private static void requirePublic(Class<?> owner, int memberModifiers, String member) {
        if (!Modifier.isPublic(owner.getModifiers()) || !Modifier.isPublic(memberModifiers)) {
            throw new IllegalAccessError(member);
        }
    }

    private static Class<?>[] classes(ClassLoader loader, Type[] types) {
        var result = new Class<?>[types.length];
        for (int index = 0; index < types.length; index++) {
            result[index] = classFor(loader, types[index]);
        }
        return result;
    }

    private static Class<?> classFor(ClassLoader loader, Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> void.class;
            case Type.BOOLEAN -> boolean.class;
            case Type.CHAR -> char.class;
            case Type.BYTE -> byte.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.FLOAT -> float.class;
            case Type.LONG -> long.class;
            case Type.DOUBLE -> double.class;
            case Type.OBJECT -> load(loader, type.getClassName());
            case Type.ARRAY -> load(loader, type.getDescriptor().replace('/', '.'));
            default -> throw new IllegalArgumentException("Unsupported optional linkage type " + type);
        };
    }

    private static Class<?> load(ClassLoader loader, String binaryName) {
        try {
            return Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException failure) {
            throw missingClass(binaryName, failure);
        }
    }

    private static NoClassDefFoundError missingClass(String binaryName, Throwable cause) {
        var error = new NoClassDefFoundError(binaryName.replace('.', '/'));
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    private record MethodRef(String owner, String name, String descriptor) {
        private static MethodRef all(String owner) {
            return new MethodRef(owner, null, null);
        }
    }
}
