package com.elfmcys.ysm.verification.paging;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Identical entry/normal-return probes are applied to both source revisions. */
public final class PageFrameAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (name == null || !(name.equals("com/elfmcys/ysm/client/texture/CustomTexture")
                    || name.equals("com/elfmcys/ysm/client/texture/CustomTextureManager$MinecraftHost")
                    || name.equals("com/elfmcys/ysm/natives/image/Image")
                    || name.startsWith("com/elfmcys/ysm/client/compat/") && name.endsWith("BuiltinComponent")
                    || name.equals("com/elfmcys/ysm/client/gui/button/CatalogModelButton")
                    || name.equals("com/elfmcys/ysm/client/gui/button/CatalogPageFrame$CardWork")
                    || name.equals("com/elfmcys/ysm/client/entity/AnimatableEntity")
                    || name.equals("com/elfmcys/ysm/expression/host/builtin/CuriosBuiltins")
                    || name.equals("com/elfmcys/ysm/expression/host/DeclarationComponentScope")
                    || name.equals("com/mojang/blaze3d/platform/GlStateManager")
                    || name.equals("com/elfmcys/ysm/format/schema/file/FileChunkDataSource"))) return null;
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                        String[] exceptions) {
                        var target = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (name.endsWith("/DeclarationComponentScope") && method.equals("invoke")) {
                            return new MethodVisitor(Opcodes.ASM9, target) {
                                private void hook(String edge) {
                                    super.visitLdcInsn(edge);
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/client/gui/PageFrameHost", "observeHook",
                                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                }
                                @Override public void visitCode() { super.visitCode(); hook(""); }
                                @Override public void visitInsn(int opcode) {
                                    if (opcode == Opcodes.RETURN) hook("-return");
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                        String kind = null;
                        int object = -1;
                        boolean integer = false;
                        if (name.endsWith("/CustomTexture")) {
                            if (method.equals("<init>") && descriptor.contains("BiConsumer")) { kind = "construct"; object = 0; }
                            if (method.equals("load")) { kind = "load"; object = 0; }
                            if (method.equals("upload") || method.equals("uploadPrepared")) { kind = "upload"; object = 0; }
                            if (method.equals("close")) { kind = "close"; object = 0; }
                        } else if (name.endsWith("$MinecraftHost")) {
                            if (method.equals("register")) { kind = "host-register"; object = 2; }
                            if (method.equals("release")) { kind = "host-release"; object = 1; }
                        } else if (name.endsWith("BuiltinComponent") && (method.equals("update")
                            || method.equals("setRenderTarget") || method.equals("beginEval") || method.equals("endEval"))) {
                            kind = "provider:" + name.substring(name.lastIndexOf('/') + 1) + ":" + method; object = 0;
                        } else if ((name.endsWith("BuiltinComponent") || name.endsWith("/CuriosBuiltins"))
                            && (access & Opcodes.ACC_STATIC) != 0
                            && descriptor.startsWith("(Lcom/elfmcys/ysm/api/molang/MolangContextView;")) {
                            kind = "provider-call:" + name.substring(name.lastIndexOf('/') + 1) + ":" + method; object = 0;
                        } else if (name.endsWith("/CatalogModelButton") && method.equals("renderEntity")) {
                            kind = "draw3d"; object = 0;
                        } else if (name.endsWith("$CardWork") && (method.equals("run") || method.equals("join"))) {
                            kind = "task-" + method; object = 0;
                        } else if (name.endsWith("/AnimatableEntity") && method.equals("tickAnimation")) {
                            kind = "animation"; object = 0;
                        } else if (name.endsWith("/GlStateManager") && method.equals("_deleteTexture")
                            && descriptor.equals("(I)V")) {
                            kind = "gl-delete"; object = 0; integer = true;
                        } else if (name.endsWith("/FileChunkDataSource")
                            && (method.equals("readPayload") || method.equals("readStoredVerified"))) {
                            kind = "chunk-read"; object = 0;
                        } else if (name.endsWith("/Image") && (method.equals("decode") || method.equals("decodeToBuffer"))) {
                            kind = "decode"; object = 0;
                        }
                        if (kind == null) return target;
                        final var observedKind = kind;
                        final var observedObject = object;
                        final boolean observedInteger = integer;
                        final boolean constructor = method.equals("<init>");
                        return new MethodVisitor(Opcodes.ASM9, target) {
                            private void emit(String suffix) {
                                super.visitLdcInsn(observedKind + suffix);
                                super.visitVarInsn(observedInteger ? Opcodes.ILOAD : Opcodes.ALOAD, observedObject);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/elfmcys/ysm/client/gui/PageFrameHost", observedInteger ? "observeId" : "observe",
                                    observedInteger ? "(Ljava/lang/String;I)V" : "(Ljava/lang/String;Ljava/lang/Object;)V", false);
                            }
                            @Override public void visitCode() { super.visitCode(); if (!constructor) emit(""); }
                            @Override public void visitInsn(int opcode) {
                                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
                                    emit(constructor ? "" : "-return");
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
