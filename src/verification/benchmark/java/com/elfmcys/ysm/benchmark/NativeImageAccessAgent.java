package com.elfmcys.ysm.benchmark;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;

/** Supplies production Mixin getters and optional priority observations in ordinary benchmark forks. */
public final class NativeImageAccessAgent {
    private static final String IMAGE = "com/mojang/blaze3d/platform/NativeImage";
    private static final String ACCESSOR = "com/elfmcys/ysm/mixin/client/NativeImageAccessor";

    public static void premain(String arguments, Instrumentation instrumentation) {
        var priorityProbe = "priority".equals(arguments);
        if (priorityProbe)
            System.setProperty("ysm.priority.probe", "true");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain,
                byte[] bytes) {
                if (!IMAGE.equals(name))
                    return priorityProbe ? instrumentPriority(name, bytes) : null;
                var writer = new ClassWriter(0);
                var fields = new java.util.HashSet<String>();
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public void visit(int version, int access, String name, String signature, String parent,
                        String[] interfaces) {
                        if (Arrays.asList(interfaces).contains(ACCESSOR))
                            throw new IllegalStateException("NativeImage is already transformed");
                        var updated = Arrays.copyOf(interfaces, interfaces.length + 1);
                        updated[interfaces.length] = ACCESSOR;
                        super.visit(version, access, name, signature, parent, updated);
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                        Object value) {
                        if ((name.equals("pixels") || name.equals("size")) && descriptor.equals("J"))
                            fields.add(name);
                        return super.visitField(access, name, descriptor, signature, value);
                    }

                    @Override
                    public void visitEnd() {
                        if (!fields.equals(java.util.Set.of("pixels", "size")))
                            throw new IllegalStateException("NativeImage field layout changed");
                        for (var field : fields) {
                            var method = super.visitMethod(Opcodes.ACC_PUBLIC, "ysm$" + field, "()J", null, null);
                            method.visitCode();
                            method.visitVarInsn(Opcodes.ALOAD, 0);
                            method.visitFieldInsn(Opcodes.GETFIELD, IMAGE, field, "J");
                            method.visitInsn(Opcodes.LRETURN);
                            method.visitMaxs(2, 1);
                            method.visitEnd();
                        }
                        super.visitEnd();
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }

    private static byte[] instrumentPriority(String name, byte[] bytes) {
        if (name == null)
            return null;
        var render = "com/elfmcys/ysm/model/resource/client/render/";
        String method;
        String enter;
        String exit;
        if (name.equals(render + "PreparedTargetCache$Production")) {
            method = "run";
            enter = "producerStarted";
            exit = "producerFinished";
        } else if (name.equals(render + "ModelRenderTargetLoader$DefaultBuildStage")) {
            method = "build";
            enter = "buildStarted";
            exit = "buildFinished";
        } else if (name.equals(render + "BakedModelCache")) {
            method = "bake";
            enter = "baked";
            exit = null;
        } else {
            return null;
        }
        var writer = new ClassWriter(0);
        var matches = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
                var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method))
                    return delegate;
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private void probe(String callback) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "com/elfmcys/ysm/model/resource/client/render/ResourcePriorityProbe", callback, "()V", false);
                    }

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        probe(enter);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (exit != null && (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
                            || opcode == Opcodes.ATHROW))
                            probe(exit);
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (matches[0] != 1)
            throw new IllegalStateException("Priority probe method changed: " + name + "." + method);
        return writer.toByteArray();
    }
}
