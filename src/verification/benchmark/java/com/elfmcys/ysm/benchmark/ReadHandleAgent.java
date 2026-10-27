package com.elfmcys.ysm.benchmark;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Observes the real channels returned by Java filesystem open calls. */
public final class ReadHandleAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        ReadHandleProbe.initialize();
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain,
                byte[] bytes) {
                if (!name.startsWith("com/elfmcys/ysm/") || name.contains("ReadHandle"))
                    return null;
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                        String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9,
                            super.visitMethod(access, method, descriptor, signature, exceptions)) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String called, String desc,
                                boolean isInterface) {
                                boolean observe = opcode == Opcodes.INVOKESTATIC
                                    && (owner.equals("java/nio/channels/FileChannel") && called.equals("open")
                                        || owner.equals("java/nio/file/Files")
                                            && (called.equals("newByteChannel") || called.equals("newInputStream")));
                                super.visitMethodInsn(opcode,
                                    observe ? "com/elfmcys/ysm/benchmark/ReadHandleProbe" : owner, called, desc,
                                    isInterface);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}
