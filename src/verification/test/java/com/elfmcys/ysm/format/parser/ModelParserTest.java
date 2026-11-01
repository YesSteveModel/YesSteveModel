package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.elfmcys.ysm.expression.host.BuiltinDeclarations;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.format.vfs.VirtualFileSystem;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.proto.mixel.common.StringPairProto;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelParserTest {
    @ParameterizedTest
    @MethodSource("sourcesWithoutWheelAnimations")
    void conversionMaterializesDefaultWheelAnimations(String metadataFile, String metadata, @TempDir Path output)
        throws Exception {
        try (var vfs = wheelFixture(metadataFile, metadata)) {
            var converted =
                ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll(), (source, mode, location, result) -> {
                });
            try (var channel = FileChannel.open(converted)) {
                var view = new ModelFileView(channel);
                var expected = IntStream.range(0, 8)
                    .mapToObj(index -> StringPairProto.newBuilder().setKey("extra" + index).setValue("").build())
                    .toList();
                assertEquals(expected, view.getManifest().info().settings().extraAnimation());
            }
        }
    }

    private static Stream<Arguments> sourcesWithoutWheelAnimations() {
        var files = "\"files\":{\"player\":{\"model\":{\"main\":\"main.json\",\"arm\":\"arm.json\"},"
            + "\"texture\":[\"skin.png\"]}}";
        return Stream.of(Arguments.of("", ""), Arguments.of("info.json", "{}"),
            Arguments.of("info.json", "{\"extra_animation_names\":[]}"), Arguments.of("ysm.json", "{" + files + "}"),
            Arguments.of("ysm.json", "{" + files + ",\"properties\":{\"extra_animation\":{}}}"),
            Arguments.of("ysm.json", "{" + files + ",\"properties\":{\"extra_animation\":null}}"),
            Arguments.of("ysm.json", "{" + files + ",\"properties\":{\"extra_animation_classify\":["
                + "{\"id\":\"poses\",\"extra_animation\":{\"pose\":\"Pose\"}}]}}"));
    }

    @ParameterizedTest
    @MethodSource("sourcesWithWheelAnimations")
    void conversionPreservesExplicitWheelAnimations(String metadataFile, String metadata, @TempDir Path output)
        throws Exception {
        try (var vfs = wheelFixture(metadataFile, metadata)) {
            var converted =
                ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll(), (source, mode, location, result) -> {
                });
            try (var channel = FileChannel.open(converted)) {
                var settings = new ModelFileView(channel).getManifest().info().settings();
                assertEquals(List.of(StringPairProto.newBuilder().setKey("extra0").setValue("Wave").build()),
                    settings.extraAnimation());
            }
        }
    }

    private static Stream<Arguments> sourcesWithWheelAnimations() {
        return Stream.of(Arguments.of("info.json", "{\"extra_animation_names\":[\"Wave\"]}"),
            Arguments.of("ysm.json", """
                {"files":{"player":{"model":{"main":"main.json","arm":"arm.json"},"texture":["skin.png"]}},
                 "properties":{"extra_animation":{"extra0":"Wave"}}}
                """));
    }

    private static MemoryVfs wheelFixture(String metadataFile, String metadata) throws IOException {
        var vfs = new MemoryVfs();
        var geometry = "{\"minecraft:geometry\":[{\"bones\":[]}]}";
        vfs.add("main.json", geometry);
        vfs.add("arm.json", geometry);
        try (var image = Objects.requireNonNull(
            ModelParserTest.class.getResourceAsStream("/assets/ysm/builtin/default/textures/default.png"))) {
            vfs.add("skin.png", image.readAllBytes());
        }
        if (!metadataFile.isEmpty()) {
            vfs.add(metadataFile, metadata);
        }
        return vfs;
    }

    @Test
    void intrinsicDefaultRejectsExternalCallsAndReadsBeforePublishingAContainer(@TempDir Path outputDirectory)
        throws IOException {
        for (var expression : List.of("projection.value()", "projection.value", "ysm.unpublished")) {
            try (var vfs = expressionFixture("return " + expression + ";")) {
                var memoryFailure =
                    assertThrows(IllegalArgumentException.class, () -> ModelParser.parseBuiltinDefaultMemory(vfs));
                var diskFailure = assertThrows(IllegalArgumentException.class,
                    () -> ModelParser.parseBuiltinDefault(vfs, outputDirectory, (source, mode, location, result) -> {
                    }));
                var symbol = expression.startsWith("ysm.") ? "ysm:unpublished" : "projection:value";
                for (var failure : List.of(memoryFailure, diskFailure)) {
                    assertTrue(failure.getMessage().contains("functions/admission.molang"));
                    assertTrue(failure.getMessage().contains(symbol));
                }
            }
        }
        try (var outputs = Files.list(outputDirectory)) {
            assertEquals(0, outputs.count());
        }
    }

    @Test
    void intrinsicDefaultAcceptsUnavailableOptionalBuiltinAndOrdinarySourceKeepsExternalSymbols(
        @TempDir Path outputDirectory) throws IOException {
        var optional = BuiltinDeclarations.group("ctrl:parcool_state");
        assertFalse(HostAvailability.none().versions().containsKey(optional.dependencies().get(0).modId()));
        try (var vfs = expressionFixture("return ctrl.parcool_state;")) {
            var container = ModelParser.parseBuiltinDefaultMemory(vfs);
            var bytes = container.bytes();
            assertTrue(bytes.size() > 0);
        }
        var observed = new AtomicInteger();
        try (var vfs = expressionFixture("return projection.value() + projection.readonly;")) {
            var output = ModelParser.parse(vfs, outputDirectory, DefaultAnimationFilter.keepAll(),
                (source, mode, location, result) -> {
                    if (location.value().equals("functions/admission.molang")) {
                        assertEquals(Set.of("projection:value", "projection:readonly"), result.referencedSymbols()
                            .stream().map(symbol -> symbol.name()).collect(java.util.stream.Collectors.toSet()));
                        observed.incrementAndGet();
                    }
                });
            assertTrue(Files.isRegularFile(output));
            assertEquals(1, observed.get());
        }
    }

    @Test
    void hashOnlyScanReadsTheParseResourceSetWithoutBuildingAssets() {
        try (var vfs = new MemoryVfs()) {
            vfs.add("ysm.json", """
                {
                  "metadata": {
                    "authors": [
                      {"name": "test", "avatar": "avatars/test.png"}
                    ]
                  },
                  "properties": {
                    "gui_foreground": "gui/foreground.png",
                    "gui_background": "gui/background.png",
                    "icon": "icon.bin",
                    "thumbnail": "thumbnail.bin"
                  },
                  "files": {
                    "player": {
                      "model": {
                        "main": "models/main.json",
                        "arm": "models/arm.json"
                      },
                      "animation": {
                        "main": "animations/main.json",
                        "unsupported": "animations/ignored.json"
                      },
                      "animation_controllers": ["controllers/player.json"],
                      "texture": [
                        {
                          "uv": "textures/player.png",
                          "normal": "textures/player_n.png",
                          "specular": "textures/player_s.png"
                        }
                      ]
                    },
                    "projectiles": [
                      {
                        "match": ["minecraft:arrow"],
                        "model": "projectile/model.json",
                        "animation": "projectile/animation.json",
                        "controller": "projectile/controller.json",
                        "texture": "projectile/texture.png"
                      },
                      {
                        "match": [],
                        "model": "ignored/model.json",
                        "texture": "ignored/texture.png"
                      }
                    ],
                    "vehicles": [
                      {
                        "match": ["minecraft:boat"],
                        "model": "vehicle/model.json",
                        "texture": "vehicle/texture.png"
                      }
                    ],
                    "sound_path": "sounds",
                    "function_path": "functions",
                    "language_path": "lang"
                  }
                }
                """);

            var expectedTypes = new LinkedHashMap<String, String>();
            expectedTypes.put("ysm.json", "manifest");
            expectedTypes.put("models/main.json", "model");
            expectedTypes.put("models/arm.json", "model");
            expectedTypes.put("animations/main.json", "animation");
            expectedTypes.put("controllers/player.json", "controller");
            expectedTypes.put("textures/player.png", "texture");
            expectedTypes.put("textures/player_n.png", "texture/normal");
            expectedTypes.put("textures/player_s.png", "texture/specular");
            expectedTypes.put("gui/foreground.png", "gui-foreground");
            expectedTypes.put("gui/background.png", "gui-background");
            expectedTypes.put("avatars/test.png", "avatar");
            expectedTypes.put("projectile/model.json", "model");
            expectedTypes.put("projectile/animation.json", "animation");
            expectedTypes.put("projectile/controller.json", "controller");
            expectedTypes.put("projectile/texture.png", "texture");
            expectedTypes.put("vehicle/model.json", "model");
            expectedTypes.put("vehicle/texture.png", "texture");
            expectedTypes.put("functions/main.molang", "molang-func");
            expectedTypes.put("functions/nested/extra.molang", "molang-func");
            expectedTypes.put("lang/en_us.json", "language");
            expectedTypes.put("lang/nested/zh_cn.json", "language");
            expectedTypes.put("sounds/ignored.ogg", "sound");
            expectedTypes.put("icon.bin", "icon");
            expectedTypes.put("thumbnail.bin", "thumbnail");

            for (var path : expectedTypes.keySet()) {
                if (!path.equals("ysm.json")) {
                    vfs.add(path, "invalid asset: " + path);
                }
            }
            vfs.add("animations/ignored.json", "not read");
            vfs.add("ignored/model.json", "not read");
            vfs.add("ignored/texture.png", "not read");
            vfs.add("functions/ignored.txt", "not read");
            vfs.add("lang/ignored.lang", "not read");
            vfs.add("sounds/ignored.ogg", "unknown audio");

            var actual = ModelParser.scanModelHash(vfs);

            assertEquals(expectedTypes.keySet(), vfs.readFiles());
            assertEquals(expectedHash(vfs, expectedTypes), actual);
        }
    }

    @Test
    void hashOnlyScanSupportsLegacyLayoutWithoutParsingAssets() {
        try (var vfs = new MemoryVfs()) {
            var expectedTypes = new LinkedHashMap<String, String>();
            expectedTypes.put("info.json", "info");
            expectedTypes.put("main.json", "model");
            expectedTypes.put("arm.json", "model");
            expectedTypes.put("main.animation.json", "animation");
            expectedTypes.put("skin.png", "texture");

            vfs.add("info.json", "{}");
            vfs.add("main.json", "invalid model");
            vfs.add("arm.json", "invalid model");
            vfs.add("main.animation.json", "invalid animation");
            vfs.add("skin.png", "invalid image");

            var actual = ModelParser.scanModelHash(vfs);

            assertEquals(expectedTypes.keySet(), vfs.readFiles());
            assertEquals(expectedHash(vfs, expectedTypes), actual);
        }
    }

    @Test
    void scanAndParseProduceTheSameHash(@TempDir Path outputDirectory) throws URISyntaxException {
        var manifest =
            Objects.requireNonNull(ModelParserTest.class.getResource("/assets/ysm/builtin/wine_fox/22_elf/ysm.json"));
        var sourceDirectory = Path.of(manifest.toURI()).getParent();

        CapturedModel captured;
        try (var vfs = new Directory(sourceDirectory)) {
            captured = ModelParser.capture(vfs);
        }

        try (captured) {
            var result = ModelParser.compile(captured.data().asVirtualFileSystem(), outputDirectory,
                DefaultAnimationFilter.keepAll());

            assertEquals(captured.modelId(), result.modelHash());
            assertEquals(captured.modelId() + ".mxc", result.stagedContainer().getFileName().toString());
        }
    }

    @Test
    void rawUnknownAudioWarnsAndCorruptSupportedAudioIsRejected() throws IOException {
        var validAudio = Files.readAllBytes(fixtureRoot().resolve("opus-under.ogg"));
        var invalidAudio = validAudio.clone();
        invalidAudio[invalidAudio.length - 1] ^= 1;
        try (var firstVfs = legacyRawFixture(new byte[] {1, 2, 3});
            var secondVfs = legacyRawFixture(new byte[] {1, 2, 4}); var invalidVfs = legacyRawFixture(invalidAudio);
            var supportedVfs = legacyRawFixture(validAudio); var first = ModelParser.capture(firstVfs);
            var second = ModelParser.capture(secondVfs); var supported = ModelParser.capture(supportedVfs)) {
            assertEquals(List.of(new RawModelDiagnostic(RawModelDiagnostic.Kind.UNKNOWN_AUDIO)), first.diagnostics());
            assertEquals(first.diagnostics(), second.diagnostics());
            assertFalse(first.modelId().equals(second.modelId()));
            var failure = assertThrows(java.io.UncheckedIOException.class, () -> ModelParser.capture(invalidVfs));
            assertTrue(failure.getCause().getMessage().contains("Ogg page checksum mismatch"));
            assertTrue(supported.diagnostics().isEmpty());
        }
    }

    @Test
    void captureCopiesBorrowedFilesFreezesDirectoriesAndNeverFallsBack(@TempDir Path sourceDirectory) throws Exception {
        var original = new LinkedHashMap<String, byte[]>();
        original.put("info.json", "{}".getBytes(StandardCharsets.UTF_8));
        original.put("main.json", "original main".getBytes(StandardCharsets.UTF_8));
        original.put("arm.json", "original arm".getBytes(StandardCharsets.UTF_8));
        original.put("main.animation.json", "original animation".getBytes(StandardCharsets.UTF_8));
        original.put("skin.png", new byte[] {1, 2, 3, 4});
        original.put("unused.bin", new byte[] {5, 6, 7, 8});
        for (var entry : original.entrySet()) {
            Files.write(sourceDirectory.resolve(entry.getKey()), entry.getValue());
        }

        try (var source = new Directory(sourceDirectory)) {
            var captured = ModelParser.capture(source);
            var capturedVfs = captured.data().asVirtualFileSystem();
            assertTrue(source.hasFile("info.json"));

            Files.writeString(sourceDirectory.resolve("main.json"), "changed");
            Files.writeString(sourceDirectory.resolve("new.json"), "new");
            Files.createDirectory(sourceDirectory.resolve("new-directory"));
            Files.delete(sourceDirectory.resolve("unused.bin"));

            assertFalse(Arrays.asList(capturedVfs.listFiles()).contains("new.json"));
            assertFalse(Arrays.asList(capturedVfs.listDirectories()).contains("new-directory"));
            assertArrayEquals(original.get("main.json"), bytes(capturedVfs.getFile("main.json")));
            assertTrue(Objects.requireNonNull(capturedVfs.getFile("main.json")).nio().isReadOnly());
            assertEquals(captured.modelId(), ModelParser.scanModelHash(capturedVfs));
            assertThrows(IllegalStateException.class, () -> capturedVfs.getFile("unused.bin"));
            assertNull(capturedVfs.getFile("new.json"));

            captured.close();
            assertDoesNotThrow(captured::close);
            assertThrows(IllegalStateException.class, captured.data()::asVirtualFileSystem);
            assertTrue(source.hasFile("info.json"));
        }
    }

    @Test
    void captureClosesEveryOwnedCopyExactlyOnce() {
        try (var delegate = legacyMemoryVfs()) {
            var source = new TrackingCopyVfs(delegate);
            var captured = ModelParser.capture(source);
            var copied = source.copyCount.get();
            assertTrue(copied > 0);

            captured.close();
            captured.close();

            assertEquals(copied, source.closeCount.get());
            assertDoesNotThrow(() -> Objects.requireNonNull(delegate.getFile("info.json")).size());
        }
    }

    @Test
    void canonicalInputIsSortedUnsignedUtf8AndEncodedLittleEndian() {
        var canonicalizer = new ModelHashCanonicalizer();
        canonicalizer.add("model", "é.json", new byte[] {3});
        canonicalizer.add("animation", "a.json", new byte[] {1});
        canonicalizer.add("model", "z.json", new byte[] {2});

        var expected = ByteBuffer.allocate(4 + recordSize("animation", "a.json", 1) + recordSize("model", "z.json", 1)
            + recordSize("model", "é.json", 1)).order(ByteOrder.LITTLE_ENDIAN);
        expected.putInt(3);
        putRecord(expected, "animation", "a.json", new byte[] {1});
        putRecord(expected, "model", "z.json", new byte[] {2});
        putRecord(expected, "model", "é.json", new byte[] {3});

        try (var encoded = ArrayBuffer.move(expected.array())) {
            assertArrayEquals(Blake3.computeHash(encoded), canonicalizer.aggregate());
        }

        assertDoesNotThrow(() -> canonicalizer.add("model", "z.json", new byte[] {2}));
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.add("model", "z.json", new byte[] {9}));
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.add("mødel", "valid.json", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.add("model", "../invalid.json", new byte[0]));
    }

    private static Hash256 expectedHash(MemoryVfs vfs, Map<String, String> expectedTypes) {
        var canonicalizer = new ModelHashCanonicalizer();
        for (var entry : expectedTypes.entrySet()) {
            canonicalizer.add(entry.getValue(), entry.getKey(), vfs.file(entry.getKey()));
        }
        return new Hash256(canonicalizer.aggregate());
    }

    private static MemoryVfs legacyMemoryVfs() {
        var vfs = new MemoryVfs();
        vfs.add("info.json", "{}");
        vfs.add("main.json", "invalid model");
        vfs.add("arm.json", "invalid model");
        vfs.add("main.animation.json", "invalid animation");
        vfs.add("skin.png", "invalid image");
        return vfs;
    }

    private static MemoryVfs expressionFixture(String source) {
        var vfs = new MemoryVfs();
        vfs.add("ysm.json", """
            {"files":{"player":{"model":{"main":"main.json","arm":"arm.json"}}}}
            """);
        var geometry = "{\"minecraft:geometry\":[{\"bones\":[]}]}";
        vfs.add("main.json", geometry);
        vfs.add("arm.json", geometry);
        vfs.add("functions/admission.molang", source);
        return vfs;
    }

    private static MemoryVfs legacyRawFixture(byte[] sound) {
        var vfs = legacyMemoryVfs();
        vfs.add("sounds/model-audio.bin", sound);
        return vfs;
    }

    private static byte[] bytes(NativeBuffer buffer) {
        var result = new byte[Objects.requireNonNull(buffer, "buffer").size()];
        buffer.nio().get(result);
        return result;
    }

    private static Path fixtureRoot() {
        return com.elfmcys.ysm.testutil.AudioFixtures.root();
    }

    private static int recordSize(String role, String path, int contentSize) {
        return Integer.BYTES + role.getBytes(StandardCharsets.UTF_8).length + Integer.BYTES
            + path.getBytes(StandardCharsets.UTF_8).length + Long.BYTES + contentSize;
    }

    private static void putRecord(ByteBuffer target, String role, String path, byte[] content) {
        var roleBytes = role.getBytes(StandardCharsets.UTF_8);
        var pathBytes = path.getBytes(StandardCharsets.UTF_8);
        target.putInt(roleBytes.length).put(roleBytes);
        target.putInt(pathBytes.length).put(pathBytes);
        target.putLong(content.length).put(content);
    }

    private static final class TrackingCopyVfs implements VirtualFileSystem {
        private final MemoryVfs delegate;
        private final AtomicInteger copyCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();

        private TrackingCopyVfs(MemoryVfs delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] listFiles(String path) {
            return delegate.listFiles(path);
        }

        @Override
        public String[] listDirectories(String path) {
            return delegate.listDirectories(path);
        }

        @Override
        public boolean hasFile(String fileName) {
            return delegate.hasFile(fileName);
        }

        @Override
        public NativeBuffer getFile(String fileName) {
            var file = delegate.getFile(fileName);
            return file == null ? null : new TrackingBorrow(file, copyCount, closeCount);
        }
    }

    private record TrackingBorrow(NativeBuffer underlying, AtomicInteger copyCount,
        AtomicInteger closeCount) implements NativeBuffer {
        @Override
        public long ptr() {
            return underlying.ptr();
        }

        @Override
        public BorrowedNativeBuffer slice(int offset, int size) {
            return underlying.slice(offset, size);
        }

        @Override
        public NativeStorage copy() {
            copyCount.incrementAndGet();
            return new TrackingOwner(underlying.copy(), closeCount);
        }

        @Override
        public ByteBuffer nio() {
            return underlying.nio();
        }

        @Override
        public int size() {
            return underlying.size();
        }

    }

    private record TrackingOwner(NativeStorage underlying, AtomicInteger closeCount) implements NativeStorage {
        @Override
        public long ptr() {
            return underlying.ptr();
        }

        @Override
        public BorrowedNativeBuffer slice(int offset, int size) {
            return underlying.slice(offset, size);
        }

        @Override
        public ByteBuffer nio() {
            return underlying.nio();
        }

        @Override
        public int size() {
            return underlying.size();
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            underlying.close();
        }
    }

    private static final class MemoryVfs implements VirtualFileSystem, AutoCloseable {
        private final Map<String, NativeStorage> files = new LinkedHashMap<>();
        private final Set<String> readFiles = new LinkedHashSet<>();

        void add(String path, String content) {
            add(path, content.getBytes(StandardCharsets.UTF_8));
        }

        void add(String path, byte[] data) {
            var buffer = NativeBuffer.allocate(data.length);
            buffer.nio().put(data);
            files.put(normalize(path), buffer);
        }

        NativeBuffer file(String path) {
            return files.get(normalize(path));
        }

        Set<String> readFiles() {
            return readFiles;
        }

        @Override
        public String[] listFiles(String path) {
            var prefix = directoryPrefix(path);
            return files.keySet().stream().filter(file -> file.startsWith(prefix))
                .map(file -> file.substring(prefix.length())).filter(file -> !file.contains("/")).sorted()
                .toArray(String[]::new);
        }

        @Override
        public String[] listDirectories(String path) {
            var prefix = directoryPrefix(path);
            return files.keySet().stream().filter(file -> file.startsWith(prefix))
                .map(file -> file.substring(prefix.length())).filter(file -> file.contains("/"))
                .map(file -> file.substring(0, file.indexOf('/'))).distinct().sorted().toArray(String[]::new);
        }

        @Override
        public boolean hasFile(String fileName) {
            return files.containsKey(normalize(fileName));
        }

        @Override
        public NativeBuffer getFile(String fileName) {
            var normalized = normalize(fileName);
            var file = files.get(normalized);
            if (file != null) {
                readFiles.add(normalized);
            }
            return file;
        }

        @Override
        public void close() {
            files.values().forEach(NativeStorage::close);
        }

        private static String directoryPrefix(String path) {
            var normalized = normalize(path);
            return normalized.isEmpty() ? "" : normalized + "/";
        }

        private static String normalize(String path) {
            if (path == null) {
                return "";
            }
            var normalized = path.replace('\\', '/');
            return Arrays.stream(normalized.split("/")).filter(part -> !part.isEmpty())
                .reduce((left, right) -> left + "/" + right).orElse("");
        }
    }
}
