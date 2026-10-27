package com.elfmcys.ysm.mock.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Observes existing GUI seams before compatibility bootstrap can load the entity classes. */
public final class GuiEntryHostAgent {
    private static final String OBSERVER = "com/elfmcys/ysm/mock/host/GuiEntryObservation";

    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (name == null || !(name.equals("com/elfmcys/ysm/client/entity/AnimatableEntity")
                    || name.equals("com/elfmcys/ysm/client/gui/PlayerTextureScreen")
                    || name.equals("com/elfmcys/ysm/model/resource/client/render/ModelRenderTargetLoader")
                    || name.equals("com/elfmcys/ysm/network/NetworkHandler")
                    || name.equals("com/elfmcys/ysm/natives/render/NativeRenderer")))
                    return null;
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                        String[] exceptions) {
                        var delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                        var installation = name.endsWith("/AnimatableEntity") && method.equals("install");
                        var receiving = name.endsWith("/PlayerTextureScreen") && method.equals("receiveReady");
                        var preparation = name.endsWith("/ModelRenderTargetLoader") && method.equals("loadAsync");
                        var network = name.endsWith("/NetworkHandler") && method.equals("sendFrame")
                            && descriptor.startsWith("(Lnet/minecraftforge/network/NetworkDirection;");
                        var render = name.endsWith("/NativeRenderer") && method.equals("render");
                        if (!installation && !receiving && !preparation && !network && !render)
                            return delegate;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                if (installation || network) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OBSERVER,
                                        installation ? "beforeInstallation" : "networkSent", "(Ljava/lang/Object;)V", false);
                                }
                                if (preparation)
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OBSERVER, "preparationStarted", "()V", false);
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (installation && opcode == Opcodes.ARETURN) {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OBSERVER, "afterInstallation",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                }
                                if (receiving && opcode == Opcodes.IRETURN) {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OBSERVER, "afterReceive", "(Z)V", false);
                                }
                                if (render && opcode == Opcodes.IRETURN) {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitVarInsn(Opcodes.ILOAD, 3);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OBSERVER, "rendered", "(ZI)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}
