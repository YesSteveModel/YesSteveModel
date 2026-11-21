package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.natives.storage.StorageProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;

/** Uses the delivered classes and embedded native library without a development path override. */
public final class PackagedNativeStorageProbe {
    private PackagedNativeStorageProbe() {}

    public static void main(String[] args) throws Exception {
        var archive = Path.of(args[0]).toAbsolutePath().normalize();
        for (var type : List.of(NativeRuntime.class, StorageProbe.class)) {
            var source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!archive.equals(source.toAbsolutePath().normalize()))
                throw new IllegalStateException("Probe did not load delivered class: " + type.getName());
        }
        var work = Path.of(args[1]);
        Files.createDirectories(work);
        var directory = Files.createTempDirectory(work, "probe-");
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        var library =
            os.contains("win") ? "ysm.dll" : os.contains("mac") || os.contains("darwin") ? "libysm.dylib" : "libysm.so";
        var extracted = directory.resolve(library);
        try (var jar = new JarFile(archive.toFile())) {
            for (var name : List.of("ysm.dll", "libysm.so", "libysm-android.so", "libysm.dylib")) {
                var entry = jar.getJarEntry("META-INF/native/" + name);
                if (entry == null)
                    throw new IllegalStateException("Missing packaged native library: " + name);
                byte[] bytes;
                try (var stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (bytes.length == 0)
                    throw new IllegalStateException("Empty packaged native library: " + name);
                System.out.println(name + " bytes=" + bytes.length + " sha256="
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                if (name.equals(library))
                    Files.write(extracted, bytes);
            }
        }
        NativeRuntime.load(extracted.toAbsolutePath().toString());
        NativeRuntime.initialize(new NativeRuntime.JavaConfig(5));
        try (var cancelled = NativeCancellation.create()) {
            StorageProbe.check(directory, cancelled);
        }
        System.out.println("Packaged native storage probe passed: " + archive);
    }
}
