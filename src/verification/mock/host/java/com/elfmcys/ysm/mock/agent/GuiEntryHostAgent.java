package com.elfmcys.ysm.mock.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Observes existing GUI seams before compatibility bootstrap can load the entity classes. */
public final class GuiEntryHostAgent {
    private static final String OBSERVER = "com/elfmcys/ysm/mock/host/GuiEntryObservation";

    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (name == null || !(name.equals("com/elfmcys/ysm/client/entity/AnimatableEntity")
                    || name.equals("net/minecraft/client/renderer/LevelRenderer")
                    || name.equals("com/elfmcys/ysm/client/gui/PlayerTextureScreen")
                    || name.equals("com/elfmcys/ysm/model/resource/client/render/ModelRenderTargetLoader")
                    || name.equals("com/elfmcys/ysm/network/NetworkHandler")
                    || name.equals("com/elfmcys/ysm/natives/render/NativeRenderer")))
                    return null;
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                if (name.equals("net/minecraft/client/renderer/LevelRenderer"))
                    bytes = controlledNormalReturns(bytes);
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
                        String ownership = name.endsWith("/LevelRenderer") && method.equals("renderLevel")
                            ? "render" : name.endsWith("/AnimatableEntity") && method.equals("beginAsyncUpdate")
                            ? "submit" : name.endsWith("/AnimatableEntity") && method.equals("waitForAsyncUpdate")
                            ? "get" : name.endsWith("/AnimatableEntity") && method.startsWith("lambda$beginAsyncUpdate$")
                            ? "execute" : null;
                        if (!installation && !receiving && !preparation && !network && !render && ownership == null)
                            return delegate;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            private void ownership(String suffix) {
                                if (ownership == null) return;
                                super.visitLdcInsn(ownership + suffix);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/elfmcys/ysm/mock/host/OwnershipHostObservation", "event",
                                    "(Ljava/lang/String;Ljava/lang/Object;)V", false);
                            }
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                ownership("-enter");
                                if ("submit".equals(ownership) || "execute".equals(ownership)) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/elfmcys/ysm/mock/host/OwnershipHostObservation",
                                        ownership.equals("submit") ? "beforeSubmit" : "beforeExecute",
                                        "(Ljava/lang/Object;)V", false);
                                }
                                if (network) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OBSERVER,
                                        "networkSent", "(Ljava/lang/Object;)V", false);
                                }
                                if (preparation)
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OBSERVER, "preparationStarted", "()V", false);
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
                                    ownership("-return");
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

    private static byte[] controlledNormalReturns(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        MethodNode head = null, drain = null;
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("com/elfmcys/ysm/client/animation/AnimationParallelTicker")) continue;
                if (call.name.equals("scheduleAll")) head = method;
                if (call.name.equals("waitAll")) drain = method;
            }
        }
        if (head == null || drain == null) throw new IllegalStateException("Production render ownership hooks missing");
        for (var method : node.methods) {
            if (!method.name.equals("renderLevel")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || !call.name.equals(head.name)) continue;
                var injected = new InsnList();
                var ordinary = new LabelNode();
                var start = new LabelNode();
                var end = new LabelNode();
                var caught = new LabelNode();
                injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/elfmcys/ysm/mock/host/OwnershipHostObservation", "afterSchedule", "()Z", false));
                injected.add(new JumpInsnNode(Opcodes.IFEQ, ordinary));
                injected.add(start);
                injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
                int slot = 1;
                for (var argument : Type.getArgumentTypes(method.desc)) {
                    injected.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
                    slot += argument.getSize();
                }
                injected.add(new TypeInsnNode(Opcodes.NEW, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo"));
                injected.add(new InsnNode(Opcodes.DUP));
                injected.add(new LdcInsnNode("renderLevel"));
                injected.add(new InsnNode(Opcodes.ICONST_0));
                injected.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "<init>", "(Ljava/lang/String;Z)V", false));
                injected.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, drain.name, drain.desc, false));
                injected.add(end);
                injected.add(new InsnNode(Opcodes.RETURN));
                injected.add(caught);
                injected.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"}));
                injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/elfmcys/ysm/mock/host/OwnershipHostObservation", "caughtDrain", "(Ljava/lang/Throwable;)V", false));
                injected.add(new InsnNode(Opcodes.RETURN));
                injected.add(ordinary);
                injected.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(instruction, injected);
                method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, caught, "java/lang/Throwable"));
                break;
            }
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
