package com.elfmcys.ysm.verification.physicsframe;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class PhysicsFrameAgent {
    private static final String HOST = "com/elfmcys/ysm/verification/physicsframe/PhysicsFrameHost";
    private static final String ENTITY_PACKAGE = "com/elfmcys/ysm/client/entity/";
    private static final String FUTURE_TASK = "java/util/concurrent/FutureTask";
    private static final String TICKER = "com/elfmcys/ysm/client/animation/AnimationParallelTicker";
    private static final String EVENT = "com/elfmcys/ysm/geckolib3/core/event/predicate/AnimationEvent";
    private static final String DEBUG_PACKAGE = "com/elfmcys/ysm/client/physics/debug/";
    private static final String DEBUG_VIEW = DEBUG_PACKAGE + "PhysicsDebugController$View";

    public static void premain(String arguments, Instrumentation instrumentation) {
        boolean riding = Boolean.parseBoolean(System.getenv("YSM_FRAME_RIDING"));
        boolean witness = Boolean.parseBoolean(System.getenv("YSM_FRAME_WITNESS"));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain,
                byte[] bytes) {
                boolean lifecycle = (ENTITY_PACKAGE + "EntityExecutionLifecycle").equals(name);
                boolean physics = (ENTITY_PACKAGE + "PlayerPhysicsSession").equals(name);
                boolean actor = witness && (ENTITY_PACKAGE + "AnimatableEntity").equals(name);
                boolean debug = witness && (DEBUG_PACKAGE + "PhysicsDebugController").equals(name);
                boolean renderer = witness && "com/elfmcys/ysm/geckolib3/geo/GeoReplacedEntityRenderer".equals(name);
                boolean ticker = riding && TICKER.equals(name);
                if (!lifecycle && !physics && !actor && !debug && !renderer && !ticker)
                    return null;
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                        String[] exceptions) {
                        var target = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (lifecycle && method.equals("awaitTask") && descriptor.equals("(Z)V"))
                            return measureTaskCalls(target);
                        if (physics && method.equals("accept") && descriptor.startsWith("(III"))
                            return countAccepted(target, witness);
                        if (actor && method.equals("createAnimationEvent"))
                            return observeReturn(target, "witnessAnimationEvent", EVENT);
                        if (actor && method.equals("getFrameRateLimit") && descriptor.equals("()I"))
                            return witnessFrameRate(target);
                        if (debug && method.equals("capture"))
                            return observeReturn(target, "witnessDraw", DEBUG_VIEW);
                        if (renderer && method.equals("renderPrepared"))
                            return hideWitnessWireframe(target);
                        if (ticker && method.equals("samplePassengers") && descriptor.equals("()V"))
                            return measurePassengerSampling(target);
                        return target;
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }

    private static MethodVisitor measurePassengerSampling(MethodVisitor target) {
        return new MethodVisitor(Opcodes.ASM9, target) {
            @Override
            public void visitCode() {
                super.visitCode();
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "beginPassengerSampling", "()V", false);
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.RETURN)
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "endPassengerSampling", "()V", false);
                super.visitInsn(opcode);
            }
        };
    }

    private static MethodVisitor measureTaskCalls(MethodVisitor target) {
        return new MethodVisitor(Opcodes.ASM9, target) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String method, String descriptor,
                boolean isInterface) {
                if (owner.equals(FUTURE_TASK) && method.equals("run") && descriptor.equals("()V")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "runClaimed",
                        "(Ljava/util/concurrent/FutureTask;)V", false);
                } else if (owner.equals(FUTURE_TASK) && method.equals("get")
                    && descriptor.equals("()Ljava/lang/Object;")) {
                    super.visitVarInsn(Opcodes.ILOAD, 1);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "joinTask",
                        "(Ljava/util/concurrent/FutureTask;Z)Ljava/lang/Object;", false);
                } else {
                    super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                }
            }
        };
    }

    private static MethodVisitor observeReturn(MethodVisitor target, String callback, String resultType) {
        return new MethodVisitor(Opcodes.ASM9, target) {
            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.ARETURN) {
                    super.visitInsn(Opcodes.DUP);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, callback, "(L" + resultType + ";)V", false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static MethodVisitor hideWitnessWireframe(MethodVisitor target) {
        return new MethodVisitor(Opcodes.ASM9, target) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String method, String descriptor,
                boolean isInterface) {
                if (owner.equals(DEBUG_PACKAGE + "PhysicsDebugWireframe") && method.equals("render")) {
                    // Keep the snapshot listener active while preserving the unobstructed game contour.
                    super.visitInsn(Opcodes.POP2);
                    super.visitInsn(Opcodes.POP);
                } else {
                    super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                }
            }
        };
    }

    private static MethodVisitor witnessFrameRate(MethodVisitor target) {
        return new MethodVisitor(Opcodes.ASM9, target) {
            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.IRETURN) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "witnessFrameRate",
                        "(IL" + ENTITY_PACKAGE + "AnimatableEntity;)I", false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static MethodVisitor countAccepted(MethodVisitor target, boolean witness) {
        return new MethodVisitor(Opcodes.ASM9, target) {
            @Override
            public void visitCode() {
                super.visitCode();
                super.visitVarInsn(Opcodes.ILOAD, 1);
                super.visitVarInsn(Opcodes.ILOAD, 2);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "physicsResult", "(II)V", false);
                if (witness) {
                    super.visitVarInsn(Opcodes.ILOAD, 1);
                    super.visitVarInsn(Opcodes.ILOAD, 2);
                    super.visitVarInsn(Opcodes.DLOAD, 8);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "witnessResult", "(IID)V", false);
                }
            }
        };
    }
}
