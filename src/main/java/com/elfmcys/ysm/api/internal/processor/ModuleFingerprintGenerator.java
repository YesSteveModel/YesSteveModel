package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.internal.processor.asm.ClassWriter;
import com.elfmcys.ysm.api.internal.processor.asm.Opcodes;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import javax.annotation.processing.Filer;
import javax.tools.StandardLocation;

final class ModuleFingerprintGenerator {
    private ModuleFingerprintGenerator() {}

    static void write(Filer filer, String moduleId, Map<String, byte[]> classes) throws IOException {
        String moduleHash = digest(Map.of(moduleId, moduleId.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        String binaryName = "com.elfmcys.ysm.compatibility.fingerprint.Module_" + moduleHash;
        String bodyHash = digest(classes);

        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
            DescriptorUtil.internalName(binaryName), null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BODY_HASH",
            "Ljava/lang/String;", null, bodyHash).visitEnd();
        writer.visitEnd();
        try (OutputStream output =
            filer.createResource(StandardLocation.CLASS_OUTPUT, "", DescriptorUtil.internalName(binaryName) + ".class")
                .openOutputStream()) {
            output.write(writer.toByteArray());
        }
    }

    private static String digest(Map<String, byte[]> classes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            classes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
                digest.update(entry.getValue());
                digest.update((byte)0xff);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
