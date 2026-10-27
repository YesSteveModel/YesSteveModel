package com.elfmcys.ysm.model.resource.client.render;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Counts the actual metadata, payload-hash and native-decode boundaries without replacing them. */
public final class GeometryReadAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        System.setProperty("ysm.geometryReadProbe", "true");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String owner, Class<?> redefining, ProtectionDomain domain,
                byte[] bytes) {
                if ("com/elfmcys/ysm/format/schema/baked/model/BakedModelView".equals(owner)) {
                    return instrument(bytes, "<init>", "metadata", false, "readModelData", "payload", false);
                } else if ("com/elfmcys/ysm/format/container/ChunkDecoding".equals(owner)) {
                    return instrument(bytes, "decodeZstd", "hash", true, "validateDirectPayload", "hash", true);
                } else if ("com/elfmcys/ysm/natives/render/NativeBakedModel".equals(owner)) {
                    return instrument(bytes, "read", "decode", false, null, null, false);
                }
                return null;
            }
        });
    }

    private static byte[] instrument(byte[] bytes, String first, String firstCallback, boolean firstChunk,
        String second, String secondCallback, boolean secondChunk) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        var matches = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
                var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(first) && !name.equals(second))
                    return delegate;
                matches[0]++;
                var callback = name.equals(first) ? firstCallback : secondCallback;
                var chunk = name.equals(first) ? firstChunk : secondChunk;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (chunk)
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "com/elfmcys/ysm/model/resource/client/render/GeometryReadProbe", callback,
                            chunk ? "(Lcom/elfmcys/ysm/format/container/AssetContainerView$ChunkInfo;)V" : "()V",
                            false);
                    }
                };
            }
        }, 0);
        if (matches[0] != (second == null ? 1 : 2))
            throw new IllegalStateException("Geometry probe boundaries changed");
        return writer.toByteArray();
    }
}
