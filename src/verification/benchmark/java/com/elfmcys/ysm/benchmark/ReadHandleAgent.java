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
                            public void visitCode() {
                                super.visitCode();
                                var stage =
                                    name.equals("com/elfmcys/ysm/format/parser/ModelParser") && method.equals("capture")
                                        ? 0
                                        : name.equals("com/elfmcys/ysm/format/parser/RawModelAssembler")
                                            && method.equals("parse")
                                                ? 1
                                                : name.equals("com/elfmcys/ysm/natives/NativeArchive")
                                                    && method.equals("getFile")
                                                        ? 2
                                                        : name.equals("com/elfmcys/ysm/natives/NativeArchive")
                                                            && method.equals("open") ? 3
                                                                : name.equals("com/elfmcys/ysm/natives/NativeArchive")
                                                                    && method.equals("members") ? 4 : -1;
                                if (stage >= 0) {
                                    super.visitLdcInsn(stage);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/benchmark/ReadHandleProbe", "sourceCall", "(I)V", false);
                                }
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (name.equals("com/elfmcys/ysm/format/parser/CapturedSourceData")
                                    && method.equals("freeze") && opcode == Opcodes.RETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/benchmark/ReadHandleProbe", "captured",
                                        "(Ljava/lang/Object;)V", false);
                                }
                                if (name.equals("com/elfmcys/ysm/natives/NativeArchive") && method.equals("getFile")
                                    && opcode == Opcodes.ARETURN) {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/benchmark/ReadHandleProbe", "archiveMember",
                                        "(Ljava/lang/Object;)V", false);
                                }
                                super.visitInsn(opcode);
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String called, String desc,
                                boolean isInterface) {
                                boolean observe =
                                    opcode == Opcodes.INVOKESTATIC
                                        && (owner.equals("java/nio/channels/FileChannel") && called.equals("open")
                                            || owner.equals("java/nio/file/Files") && java.util.Set
                                                .of("newByteChannel", "newInputStream", "readAttributes",
                                                    "getAttribute", "exists", "isRegularFile", "isDirectory",
                                                    "isReadable", "isWritable", "size", "getLastModifiedTime",
                                                    "getPosixFilePermissions", "list", "newDirectoryStream")
                                                .contains(called));
                                if (owner.equals("java/nio/file/Path") && called.equals("toRealPath")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/benchmark/ReadHandleProbe", "toRealPath",
                                        "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Ljava/nio/file/Path;", false);
                                    return;
                                }
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
