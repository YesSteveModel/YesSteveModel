package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.image.ImageSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PreviewStoreTest {
    private static final byte[] PNG = Base64.getDecoder()
        .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @TempDir
    Path temp;

    @Test
    void qualificationCannotEscapeThroughMutableInputOrOutputAliases() throws Exception {
        var input = PNG.clone();
        try (var preview = PreviewStore.decode(input)) {
            java.util.Arrays.fill(input, (byte)0);
            try (var exposed = preview.acquireBytes(); var opened = preview.open()) {
                exposed.nio().put(0, (byte)0);
                opened.data().nio().put(0, (byte)0);
            }
            var returned = preview.bytes();
            returned[0] = 0;
            assertArrayEquals(PNG, preview.bytes());
            var store = new PreviewStore(temp.resolve("sealed"));
            store.accept(hash(20), preview);
            store.commit(hash(20), preview, AtomicSharedCache.AtomicReplacement::run);
            assertArrayEquals(PNG, Files.readAllBytes(preview(temp.resolve("sealed"), hash(20))));
            assertTrue(java.util.Arrays.stream(PreviewStore.EncodedPreview.class.getDeclaredConstructors())
                .allMatch(constructor -> java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ysm.previewDecodeProbe", matches = "true")
    void realCodecRunsOncePerNewInputAndNeverForQualifiedStoreHandoff() throws Exception {
        PreviewDecodeProbe.reset();
        var root = temp.resolve("decode-count");
        var store = new PreviewStore(root);
        try (var preview = PreviewStore.decode(PNG)) {
            assertEquals(1, PreviewDecodeProbe.count());
            store.accept(hash(21), preview);
            store.commit(hash(21), preview, AtomicSharedCache.AtomicReplacement::run);
            assertEquals(1, PreviewDecodeProbe.count());
        }
        for (var read = 0; read < 2; read++) {
            try (var preview = store.load(hash(21)).orElseThrow()) {
                assertArrayEquals(PNG, preview.bytes());
            }
        }
        assertEquals(3, PreviewDecodeProbe.count());
        try (var raw = PreviewStore.decode(PNG)) {
            assertEquals(1, raw.width());
        }
        assertEquals(4, PreviewDecodeProbe.count());
        var truncated = java.util.Arrays.copyOf(PNG, 40);
        assertThrows(IOException.class, () -> PreviewStore.decode(truncated));
        assertEquals(5, PreviewDecodeProbe.count());
        System.out.println("PREVIEW_CODEC raw=1 accept=0 commit=0 diskReads=2 external=1 truncated=1");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ysm.previewDecodeProbe", matches = "true")
    void tamperedRealStagingFailsItsCopyCheckAndPreservesTheOldFile() throws Exception {
        var root = temp.resolve("tampered-staging");
        var store = new PreviewStore(root);
        try (var encoded = PreviewStore.decode(PNG)) {
            store.accept(hash(22), encoded);
            PreviewDecodeProbe.reset();
            PreviewDecodeProbe.tamperNextWrite();
            assertThrows(IOException.class,
                () -> store.commit(hash(22), encoded, AtomicSharedCache.AtomicReplacement::run));
            assertEquals(0, PreviewDecodeProbe.count());
        }
        assertArrayEquals(PNG, Files.readAllBytes(preview(root, hash(22))));
        try (var files = Files.walk(root)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
        System.out.println("PREVIEW_STAGING tampered=true committed=false oldPreserved=true mediaDecodes=0");
    }

    @Test
    void invalidReadsAreNonDestructiveAndValidReplacementUsesOnlyContainerId() throws Exception {
        var cacheRoot = temp.resolve("cache");
        var store = new PreviewStore(cacheRoot);
        var id = hash(1);
        Files.createDirectories(cacheRoot.resolve("previews"));
        var invalid = new byte[] {1, 2, 3};
        Files.write(preview(cacheRoot, id), invalid);

        assertTrue(store.load(id).isEmpty());
        assertArrayEquals(invalid, Files.readAllBytes(preview(cacheRoot, id)));

        var accepted = store.accept(id, PNG);
        assertArrayEquals(PNG, accepted.bytes());
        assertArrayEquals(PNG, store.load(id).orElseThrow().bytes());

        var replacement = Arrays.copyOf(PNG, PNG.length / 2);
        assertThrows(Exception.class, () -> store.accept(id, replacement));
        assertArrayEquals(PNG, Files.readAllBytes(preview(cacheRoot, id)));
    }

    @Test
    void differentContainerIdsNeverShareOneCacheEntry() throws Exception {
        var cacheRoot = temp.resolve("separate");
        var store = new PreviewStore(cacheRoot);
        store.accept(hash(1), PNG);

        assertTrue(store.load(hash(2)).isEmpty());
    }

    @Test
    void strictCommitFailurePreservesOldImageAndNeverReportsOptionalSuccess() throws Exception {
        var root = temp.resolve("strict");
        new PreviewStore(root).accept(hash(4), PNG);
        var observed = new AtomicInteger();
        var store = new PreviewStore(root, new AtomicSharedCache(root, replacement -> {
            throw new IOException("Atomic replacement failed");
        }), ignored -> observed.incrementAndGet());
        try (var bytes = com.elfmcys.ysm.buffer.ArrayBuffer.move(PNG.clone())) {
            assertThrows(IOException.class, () -> store.commit(hash(4), bytes, replacement -> replacement.run()));
        }
        assertArrayEquals(PNG, Files.readAllBytes(preview(root, hash(4))));
        assertEquals(0, observed.get());
        try (var files = Files.walk(root)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void strictCommitRequiresTheEffectGateEvenWhenBytesMatchExistingImage() throws Exception {
        var root = temp.resolve("gate");
        new PreviewStore(root).accept(hash(5), PNG);
        var observed = new AtomicInteger();
        var store = new PreviewStore(root, ignored -> observed.incrementAndGet());
        var called = new AtomicBoolean();
        try (var bytes = com.elfmcys.ysm.buffer.ArrayBuffer.move(PNG.clone())) {
            assertThrows(IOException.class, () -> store.commit(hash(5), bytes, replacement -> {
                called.set(true);
                throw new IOException("Effect retired");
            }));
        }
        assertTrue(called.get());
        assertArrayEquals(PNG, Files.readAllBytes(preview(root, hash(5))));
        assertEquals(0, observed.get());
    }

    @Test
    void persistenceFailureDoesNotRevokeTheValidatedInMemoryImage() throws Exception {
        var cacheRoot = temp.resolve("failed-write");
        var observed = new AtomicInteger();
        var cache = new AtomicSharedCache(cacheRoot, replacement -> {
            throw new IOException("injected commit failure");
        });

        var accepted = new PreviewStore(cacheRoot, cache, ignored -> observed.incrementAndGet()).accept(hash(3), PNG);

        assertArrayEquals(PNG, accepted.bytes());
        try (var image = accepted.open(); var pixels = image.decodeToBuffer()) {
            assertEquals(4, pixels.size());
        }
        assertFalse(Files.exists(preview(cacheRoot, hash(3))));
        assertEquals(0, observed.get());
    }

    @Test
    void decodingPreservesAnExistingNativeBufferWithoutAnArrayRoundTrip() throws Exception {
        try (var source = NativeBuffer.allocate(PNG.length)) {
            source.nio().put(PNG);

            var preview = PreviewStore.decode(source);
            source.close();

            try (var image = preview.open()) {
                assertEquals(BufferType.NATIVE, image.data().type());
                assertArrayEquals(PNG, preview.bytes());
            }
        }
    }

    @Test
    void placeholderIsAUsableBlankImageButRemainsExplicitlyNamedAsASubstitute() throws Exception {
        var preview = TestPreviews.blank();

        assertEquals(1, preview.width());
        assertEquals(1, preview.height());
        try (var image = preview.open(); var pixels = image.decodeToBuffer()) {
            assertEquals(4, pixels.size());
        }
    }

    @Test
    void rejectsAnOversizedSourceBeforeOpeningIt() {
        var opened = new AtomicBoolean();
        ImageSource source = () -> {
            opened.set(true);
            throw new AssertionError("oversized source must not be opened");
        };

        assertThrows(IOException.class, () -> PreviewStore.read(source, PreviewStore.MAX_STORED_BYTES + 1));
        assertFalse(opened.get());
    }

    @Test
    void explicitAcceptanceAtomicallyReplacesAnOversizedInvalidEntry() throws Exception {
        var cacheRoot = temp.resolve("oversized-replacement");
        var id = hash(4);
        Files.createDirectories(cacheRoot.resolve("previews"));
        try (var file =
            FileChannel.open(preview(cacheRoot, id), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            file.position(PreviewStore.MAX_STORED_BYTES);
            file.write(ByteBuffer.wrap(new byte[] {1}));
        }

        new PreviewStore(cacheRoot).accept(id, PNG);

        assertArrayEquals(PNG, Files.readAllBytes(preview(cacheRoot, id)));
    }

    @Test
    void encodedReadReturnsInvalidMediaWithoutDecodingOrMutatingIt() throws Exception {
        var root = temp.resolve("encoded");
        var id = hash(11);
        var invalid = new byte[] {1, 2, 3};
        Files.createDirectories(root.resolve("previews"));
        Files.write(preview(root, id), invalid);
        var store = new PreviewStore(root);
        try (var encoded = store.readEncoded(id).orElseThrow()) {
            var actual = new byte[encoded.size()];
            encoded.nio().get(actual);
            assertArrayEquals(invalid, actual);
        }
        assertTrue(store.probe(id).isEmpty());
        assertArrayEquals(invalid, Files.readAllBytes(preview(root, id)));
    }

    @Test
    void encodedReadConfirmsMissingNonRegularAndInvalidLengthsBeforeAllocating() throws Exception {
        var root = temp.resolve("bounds");
        var id = hash(12);
        var path = preview(root, id);
        var store = new PreviewStore(root);
        assertTrue(store.readEncoded(id).isEmpty());
        Files.createDirectories(path);
        assertTrue(store.readEncoded(id).isEmpty());
        Files.delete(path);
        Files.createFile(path);
        assertTrue(store.readEncoded(id).isEmpty());
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) {
            file.position(PreviewStore.MAX_STORED_BYTES - 1);
            file.write(ByteBuffer.wrap(new byte[] {7}));
        }
        try (var bytes = store.readEncoded(id).orElseThrow()) {
            assertEquals(PreviewStore.MAX_STORED_BYTES, bytes.size());
            assertEquals(7, bytes.nio().get(bytes.size() - 1));
        }
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) {
            file.position(PreviewStore.MAX_STORED_BYTES);
            file.write(ByteBuffer.wrap(new byte[] {8}));
        }
        assertTrue(store.readEncoded(id).isEmpty());
        assertEquals(PreviewStore.MAX_STORED_BYTES + 1L, Files.size(path));
    }

    @Test
    void encodedReadRejectsTargetAndParentLinks() throws Exception {
        var outside = temp.resolve("outside.image");
        Files.write(outside, new byte[] {1});
        var root = temp.resolve("linked-target");
        Files.createDirectories(root.resolve("previews"));
        var target = preview(root, hash(13));
        try {
            Files.createSymbolicLink(target, outside);
        } catch (IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable: " + unavailable);
        }
        assertThrows(AssetLoadException.class, () -> new PreviewStore(root).readEncoded(hash(13)));
        var parentRoot = temp.resolve("linked-parent");
        Files.createDirectory(parentRoot);
        Files.createSymbolicLink(parentRoot.resolve("previews"), root.resolve("previews"));
        assertThrows(AssetLoadException.class, () -> new PreviewStore(parentRoot).readEncoded(hash(13)));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(outside));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void encodedReadAllowsFixedRootJunctionButRejectsInternalJunctions() throws Exception {
        var outside = temp.resolve("outside-junction");
        var id = hash(14);
        Files.createDirectories(outside.resolve("previews"));
        var bytes = new byte[] {91, 92, 93};
        Files.write(preview(outside, id), bytes);
        for (var linkRoot : new boolean[] {false, true}) {
            var root = temp.resolve(linkRoot ? "junction-root" : "junction-parent");
            var link = linkRoot ? root : Files.createDirectory(root).resolve("previews");
            var destination = linkRoot ? outside : outside.resolve("previews");
            var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), destination.toString())
                .redirectErrorStream(true).start();
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
            try {
                assertFalse(Files.isSymbolicLink(link));
                if (linkRoot) {
                    try (var opened = new PreviewStore(root).readEncoded(id).orElseThrow()) {
                        assertArrayEquals(bytes, opened.array());
                    }
                    continue;
                }
                if (linkRoot) {
                    try (var opened = new PreviewStore(root).readEncoded(id).orElseThrow()) {
                        assertArrayEquals(bytes, opened.array());
                    }
                } else {
                    var failure = assertThrows(AssetLoadException.class, () -> new PreviewStore(root).readEncoded(id));
                    assertEquals(AssetLoadException.Reason.ACCESS, failure.reason());
                }
                assertArrayEquals(bytes, Files.readAllBytes(preview(outside, id)));
            } finally {
                Files.delete(link);
            }
        }
    }

    @Test
    void encodedSnapshotRejectsRealFileShrinkAndGrowthInsteadOfReturningPartialData() throws Exception {
        var path = temp.resolve("changing.image");
        Files.write(path, new byte[] {1, 2, 3});
        try (var input = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertThrows(IOException.class,
                () -> PreviewStore.readSnapshot(new MutatingChannel(input, () -> input.truncate(1))));
        }
        Files.write(path, new byte[] {1, 2, 3});
        try (var input = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertThrows(IOException.class, () -> PreviewStore.readSnapshot(new MutatingChannel(input, () -> {
                try (var append = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    append.position(3);
                    append.write(ByteBuffer.wrap(new byte[] {4}));
                }
            })));
        }
    }

    @Test
    void encodedSnapshotPreservesActualReadFailures() throws Exception {
        var path = temp.resolve("read-failure.image");
        Files.write(path, new byte[] {1, 2, 3});
        try (var input = FileChannel.open(path, StandardOpenOption.READ)) {
            assertThrows(java.nio.channels.ClosedChannelException.class,
                () -> PreviewStore.readSnapshot(new MutatingChannel(input, input::close)));
        }
    }

    @Test
    void encodedReadAndManagementProbePreserveWindowsMandatoryLockFailures() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"),
            "This fixture requires Windows mandatory file locks");
        var root = temp.resolve("locked-read");
        var id = hash(17);
        Files.createDirectories(root.resolve("previews"));
        var path = preview(root, id);
        Files.write(path, PNG);
        var store = new PreviewStore(root);
        try (var input = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            var ignored = input.lock()) {
            var failure = assertThrows(IOException.class, () -> store.readEncoded(id));
            assertEquals(AssetLoadException.Reason.ACCESS,
                assertInstanceOf(AssetLoadException.class, failure).reason());
            assertInstanceOf(IOException.class, failure.getCause());
            assertThrows(IOException.class, () -> store.probe(id));
        }
        try (var encoded = store.readEncoded(id).orElseThrow()) {
            assertEquals(PNG.length, encoded.size());
        }
        try (var decoded = store.probe(id).orElseThrow()) {
            assertArrayEquals(PNG, decoded.bytes());
        }
    }

    @Test
    void deterministicMediaRejectionCarriesContentProvenance() {
        for (var bytes : List.of(new byte[] {1, 2, 3}, new byte[0], Arrays.copyOf(PNG, PNG.length / 2))) {
            var failure = assertThrows(AssetLoadException.class, () -> PreviewStore.decode(bytes));
            assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
        }
    }

    @Test
    void observerRunsAfterEverySuccessfulPersistenceIncludingMatchingBytes() throws Exception {
        var root = temp.resolve("observed-accept");
        var id = hash(14);
        var observed = new ArrayList<Hash256>();
        var store = new PreviewStore(root, committed -> {
            observed.add(committed);
            try {
                assertArrayEquals(PNG, Files.readAllBytes(preview(root, committed)));
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });
        try (var first = store.accept(id, PNG); var second = store.accept(id, PNG)) {
            assertArrayEquals(PNG, first.bytes());
            assertArrayEquals(PNG, second.bytes());
        }
        assertEquals(List.of(id, id), observed);
    }

    @Test
    void observerRunsInsideStrictCommitAfterTheDurableEffectAndBeforeReturn() throws Exception {
        var root = temp.resolve("observed-commit");
        var id = hash(15);
        var events = new ArrayList<String>();
        var store = new PreviewStore(root, committed -> {
            assertEquals(id, committed);
            assertEquals(List.of("gate", "persistent"), events);
            events.add("observer");
        });
        try (var bytes = com.elfmcys.ysm.buffer.ArrayBuffer.move(PNG.clone())) {
            store.commit(id, bytes, replacement -> {
                events.add("gate");
                replacement.run();
                assertArrayEquals(PNG, Files.readAllBytes(preview(root, id)));
                events.add("persistent");
            });
        }
        events.add("returned");
        assertEquals(List.of("gate", "persistent", "observer", "returned"), events);
    }

    private static final class MutatingChannel implements SeekableByteChannel {
        private final SeekableByteChannel source;
        private final AtomicSharedCache.AtomicReplacement mutation;
        private boolean mutated;

        private MutatingChannel(SeekableByteChannel source, AtomicSharedCache.AtomicReplacement mutation) {
            this.source = source;
            this.mutation = mutation;
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!mutated) {
                mutated = true;
                mutation.run();
            }
            return source.read(target);
        }

        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() throws IOException {
            return source.position();
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            source.position(position);
            return this;
        }

        @Override
        public long size() throws IOException {
            return source.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return source.isOpen();
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    private static Path preview(Path cacheRoot, Hash256 containerId) {
        return cacheRoot.resolve("previews").resolve(containerId + ".image");
    }

    private static Hash256 hash(int value) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte)value);
        return new Hash256(bytes);
    }
}
