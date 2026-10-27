package com.elfmcys.ysm.model.storage;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

public final class PreviewDecodeAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        System.setProperty("ysm.previewDecodeProbe", "true");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String owner, Class<?> type, ProtectionDomain domain,
                byte[] bytes) {
                var staging = "com/elfmcys/ysm/model/storage/CacheFile".equals(owner);
                var geometry = "com/elfmcys/ysm/model/resource/client/render/ModelRenderTargetLoader$DefaultBuildStage"
                    .equals(owner);
                var texture = "com/elfmcys/ysm/model/resource/client/render/PreparedTextureSet".equals(owner);
                if (!staging && !geometry && !texture && !"com/elfmcys/ysm/natives/image/Image".equals(owner))
                    return null;
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9,
                            super.visitMethod(access, name, descriptor, signature, exceptions)) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                if (geometry && name.equals("bakeModel") || texture && name.equals("decode")) {
                                    super.visitVarInsn(Opcodes.ALOAD, 3);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/model/storage/PreviewDecodeProbe",
                                        geometry ? "geometry" : "texture", "(Ljava/lang/String;)V", false);
                                }
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (staging && name.equals("write") && opcode == Opcodes.IRETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/model/storage/PreviewDecodeProbe", "stagingWritten",
                                        "(Lcom/elfmcys/ysm/model/storage/CacheFile;)V", false);
                                }
                                super.visitInsn(opcode);
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String target, String method, String desc,
                                boolean itf) {
                                if (target.equals("com/elfmcys/ysm/natives/image/Image$Native")
                                    && method.equals("nDecode"))
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/model/storage/PreviewDecodeProbe", "decoded", "()V", false);
                                super.visitMethodInsn(opcode, target, method, desc, itf);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}
