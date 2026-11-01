package com.elfmcys.ysm.client.entity;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.*;

/** Counts boundary calls only in separate observation processes. */
public final class EntityOwnershipProbeAgent {
    public static Instrumentation instrumentation;
    public static final AtomicLong ownerSync = new AtomicLong();
    public static final AtomicLong resolution = new AtomicLong();
    public static final AtomicLong sharedStoreMonitors = new AtomicLong();
    private static final String SELF = "com/elfmcys/ysm/client/entity/EntityOwnershipProbeAgent";

    public static void ownerSync() { ownerSync.incrementAndGet(); }
    public static void resolution() { resolution.incrementAndGet(); }
    public static void sharedStoreMonitor() { sharedStoreMonitors.incrementAndGet(); }

    public static void premain(String argument, Instrumentation value) {
        instrumentation = value;
        value.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                ProtectionDomain domain, byte[] bytes) {
                if (name == null || !(name.startsWith("com/elfmcys/ysm/expression/aot/EntityExpressionRuntime")
                    || name.equals("com/elfmcys/ysm/model/resource/client/AnimationStore"))) return null;
                var tree = new org.objectweb.asm.tree.ClassNode();
                new ClassReader(bytes).accept(tree, 0);
                boolean resolve = tree.methods.stream().anyMatch(method -> method.name.equals("resolve"));
                for (var method : tree.methods) {
                    if (name.endsWith("/AnimationStore") && (method.access & Opcodes.ACC_PUBLIC) != 0
                        && method.name.equals(resolve ? "resolve" : "get")) {
                        method.instructions.insert(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                            SELF, "resolution", "()V", false));
                    }
                    if (name.endsWith("/AnimationStore") && (method.access & Opcodes.ACC_SYNCHRONIZED) != 0)
                        method.instructions.insert(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                            SELF, "sharedStoreMonitor", "()V", false));
                    for (var instruction : method.instructions.toArray()) {
                        if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                            && call.owner.equals("java/util/concurrent/locks/ReentrantLock")
                            && (call.name.equals("lock") || call.name.equals("tryLock"))) {
                            method.instructions.insertBefore(call, new org.objectweb.asm.tree.MethodInsnNode(
                                Opcodes.INVOKESTATIC, SELF, "ownerSync", "()V", false));
                        }
                    }
                }
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                tree.accept(writer);
                return writer.toByteArray();
            }
        });
    }
}
