package com.elfmcys.ysm.model.resource.client.render;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Records the actual selected createSet input without changing the loader result. */
public final class GuiPurposeInputAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("com/elfmcys/ysm/expression/aot/RenderTargetExpressionModuleFactory"))
                    return null;
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                        String[] exceptions) {
                        var delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (!method.equals("createSet") || Type.getArgumentTypes(descriptor).length != 11)
                            return delegate;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                for (int index : new int[] {6, 7, 8, 11})
                                    super.visitVarInsn(Opcodes.ALOAD, index);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/elfmcys/ysm/model/resource/client/render/GuiPurposeCacheProbe", "input",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}
