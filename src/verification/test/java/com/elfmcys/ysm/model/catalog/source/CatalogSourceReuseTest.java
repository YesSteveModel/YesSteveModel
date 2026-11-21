package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.*;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(NativeLibraryExtension.class)
class CatalogSourceReuseTest {
    @TempDir
    Path temp;

    @Test
    void rawZipSevenZipAndBothLegacyRawVersionsHaveOneColdParseAndNoWarmParse() throws Exception {
        var raw = fixture();
        var zip = writeZip(temp.resolve("model.zip"), raw);
        var seven = resource("solid.7z");
        var v1 = writeRaw(temp.resolve("v1.ysm"), raw, 1);
        var v2 = writeRaw(temp.resolve("v2.ysm"), raw, 2);
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var indexes = new ConvertedSourceIndexStore(ConvertedScope.of(temp, "test-schema"), cache);
            var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), indexes,
                new ConvertedObjectStore(cache));
            com.elfmcys.ysm.model.domain.ModelFileIdentity expected = null;
            for (var input : List.of(raw, zip, seven, v1, v2)) {
                var observed = observe(input);
                reset();
                try (var cold = resolver.resolveMaterialized(observed).resolvedContent()) {
                    var identity = cold.content().representation().identity();
                    if (expected == null)
                        expected = identity;
                    assertEquals(expected, identity, input.toString());
                    budget(input, "cold", 1);
                }
                reset();
                try (var warm = resolver.resolveMaterialized(observed).resolvedContent()) {
                    assertEquals(expected, warm.content().representation().identity());
                    budget(input, "warm", 0);
                    if (traced() && input.toString().endsWith(".ysm")) {
                        assertEquals(0, calls(3), "warm v1/v2 must not open LegacyBundle");
                        var reads = reads().stream().filter(row -> row.get("path").equals(input.toString())).toList();
                        assertEquals(24, reads.stream().mapToLong(row -> ((Number)row.get("bytes")).longValue()).sum());
                    }
                }
                var indexed =
                    indexes.find("models/" + temp.relativize(input).toString().replace('\\', '/')).orElseThrow();
                Files.delete(indexed.file());
                reset();
                var candidate = resolver.resolve(observed);
                assertTrue(candidate.cacheCandidate());
                assertEquals(indexed.file(), candidate.entry().backingFile());
                budget(input, "index-only-missing", 0);
                if (traced())
                    assertTrue(reads().stream().noneMatch(row -> row.get("path").equals(indexed.file().toString())));
                reset();
                try (var rebuilt = resolver.resolveMaterialized(observed).resolvedContent()) {
                    assertEquals(expected, rebuilt.content().representation().identity());
                    budget(input, "missing-converted", 1);
                }
                var fresh = indexes.find(indexed.rawRelativePath()).orElseThrow();
                var bytes = Files.readAllBytes(fresh.file());
                bytes[0] ^= 1;
                Files.write(fresh.file(), bytes);
                reset();
                try (var rebuilt = resolver.resolveMaterialized(observed).resolvedContent()) {
                    assertEquals(expected, rebuilt.content().representation().identity());
                    budget(input, "bad-converted", 1);
                }
            }
        }
    }

    @Test
    void legalSevenZipWithoutMemberCrcAlwaysUsesTheDirectParser() throws Exception {
        var input = resource("solid-no-crc.7z");
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
                new ConvertedSourceIndexStore(ConvertedScope.of(temp, "test-schema"), cache),
                new ConvertedObjectStore(cache));
            for (var n = 0; n < 2; n++) {
                reset();
                try (var content = resolver.resolveMaterialized(observe(input)).resolvedContent()) {
                    assertNotNull(content.content().view().getManifest());
                    budget(input, "no-crc-" + n, 1);
                    if (traced())
                        assertEquals(1, calls(3), "one archive owner supplies metadata and payload");
                }
            }
        }
    }

    @Test
    void storedMd5IsOnlyAHintAndRelativePathsDoNotCollapseToBasenames() throws Exception {
        var raw = fixture();
        var a = writeRaw(Files.createDirectories(temp.resolve("a")).resolve("same.ysm"), raw, 1);
        var b = writeRaw(Files.createDirectories(temp.resolve("b")).resolve("same.ysm"), raw, 2);
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var indexes = new ConvertedSourceIndexStore(ConvertedScope.of(temp, "test-schema"), cache);
            var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), indexes,
                new ConvertedObjectStore(cache));
            try (var content = resolver.resolveMaterialized(observe(a)).resolvedContent()) {
            }
            try (var content = resolver.resolveMaterialized(observe(b)).resolvedContent()) {
            }
            assertEquals(2, indexes.read().size());
            var bytes = Files.readAllBytes(a);
            bytes[bytes.length - 1] ^= 1;
            Files.write(a, bytes);
            reset();
            try (var content = resolver.resolveMaterialized(observe(a)).resolvedContent()) {
                budget(a, "retained-md5", 0);
            }
            bytes[8] ^= 1;
            Files.write(a, bytes);
            assertThrows(ModelSourceException.class, () -> resolver.resolveMaterialized(observe(a)));
        }
    }

    private SourceObservation observe(Path input) throws Exception {
        var kind =
            Files.isDirectory(input) ? ModelSourceKind.CURRENT_RAW_DIRECTORY : ModelSourceKind.CURRENT_RAW_ARCHIVE;
        return ModelSourceDiscovery.observe(new ModelSourceKey(new CatalogRootIdentity(CatalogRootKind.MODELS, temp),
            new ModelPath(temp.relativize(input).toString().replace('\\', '/')), kind), input);
    }

    private Path fixture() throws Exception {
        var source =
            Path.of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/misc/1_alex/ysm.json")).toURI())
                .getParent();
        var target = temp.resolve("raw");
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var output = target.resolve(source.relativize(path));
                if (Files.isDirectory(path))
                    Files.createDirectories(output);
                else
                    Files.copy(path, output);
            }
        }
        return target;
    }

    private Path resource(String name) throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/catalog-archives/" + name))) {
            var target = temp.resolve(name);
            Files.copy(input, target);
            return target;
        }
    }

    private static Path writeZip(Path target, Path source) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(target)); var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry("root/" + source.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return target;
    }

    private static Path writeRaw(Path target, Path source, int version) throws Exception {
        var body = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(body); var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                var packed = new ByteArrayOutputStream();
                try (var zlib = new DeflaterOutputStream(packed)) {
                    Files.copy(path, zlib);
                }
                var key = new byte[16];
                Arrays.fill(key, (byte)7);
                var iv = new byte[16];
                Arrays.fill(iv, (byte)11);
                var cipher = encrypt(packed.toByteArray(), key, iv);
                var name = source.relativize(path).toString().replace('\\', '/')
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (version == 2)
                    name = Base64.getEncoder().encode(name);
                out.writeInt(name.length);
                out.write(name);
                out.writeInt(cipher.length);
                if (version == 2) {
                    var digest = MessageDigest.getInstance("MD5").digest(cipher);
                    var passwordKey = new byte[16];
                    new Random(ByteBuffer.wrap(digest, 8, 8).getLong()).nextBytes(passwordKey);
                    var password = encrypt(key, passwordKey, iv);
                    out.writeInt(password.length);
                    out.write(password);
                } else
                    out.write(key);
                out.write(iv);
                out.write(cipher);
            }
        }
        try (var out = new DataOutputStream(Files.newOutputStream(target))) {
            out.writeBytes("YSGP");
            out.writeInt(version);
            out.write(MessageDigest.getInstance("MD5").digest(body.toByteArray()));
            out.write(body.toByteArray());
        }
        return target;
    }

    private static byte[] encrypt(byte[] data, byte[] key, byte[] iv) throws Exception {
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private static boolean traced() {
        return Boolean.getBoolean("ysm.cacheReadProbe");
    }

    private static void reset() throws Exception {
        if (traced()) {
            probe().getMethod("clear").invoke(null);
            probe().getMethod("resetSources").invoke(null);
        }
    }

    private static Class<?> probe() throws Exception {
        return Class.forName("com.elfmcys.ysm.benchmark.ReadHandleProbe");
    }

    private static long calls(int kind) throws Exception {
        return (long)probe().getMethod("sourceCalls", int.class).invoke(null, kind);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> reads() throws Exception {
        return (List<Map<String, Object>>)probe().getMethod("snapshot").invoke(null);
    }

    private static void budget(Path input, String phase, long parses) throws Exception {
        if (!traced())
            return;
        assertEquals(0, calls(0), "Catalog never captures or dry-runs");
        assertEquals(parses, calls(1), phase);
        if (parses == 0)
            assertEquals(0, calls(2), "warm selection never extracts archive members");
        System.out.println("SOURCE_TRACE source=" + input.getFileName() + " phase=" + phase + " capture=" + calls(0)
            + " parse=" + calls(1) + " getFile=" + calls(2) + " openArchive=" + calls(3));
    }
}
