package com.elfmcys.ysm.model.catalog;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Measures production method bodies without adding production counters or profiler hooks. */
public final class CatalogReloadTimingAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain,
                byte[] bytes) {
                var catalog = "com/elfmcys/ysm/model/catalog/ReloadableModelCatalog";
                if (!name.equals(catalog) && !name.equals(catalog + "$ActiveScan")
                    && !name.equals("com/elfmcys/ysm/format/schema/model/ModelFileView")) {
                    return null;
                }
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                        String[] exceptions) {
                        var delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                        var stage =
                            method.equals("buildProjection") ? 0
                                : method.equals("publish") ? 1
                                    : method.equals("notifyObserver") ? 2 : method.equals("readMetadata")
                                        && descriptor.contains(";Lcom/elfmcys/ysm/format/schema/model/ModelFileView;")
                                            ? 3 : -1;
                        if (stage < 0) {
                            return delegate;
                        }
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            private void probe(String operation) {
                                super.visitLdcInsn(stage);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/elfmcys/ysm/model/catalog/CatalogReloadProbe", operation, "(I)V", false);
                            }

                            @Override
                            public void visitCode() {
                                super.visitCode();
                                probe("enter");
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                                    probe("exit");
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
