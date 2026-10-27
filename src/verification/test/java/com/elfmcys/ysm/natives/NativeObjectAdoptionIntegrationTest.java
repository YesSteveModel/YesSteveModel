package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.natives.sound.OpusDecoder;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeObjectAdoptionIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void archiveUsesTheCommonOwnerAndRejectsUseAfterClose() throws Exception {
        var archive = NativeArchive.open(archive().toString());
        assertTrue(archive.hasFile("entry"));
        var bytes = new byte[3];
        archive.getFile("entry").nio().get(bytes);
        assertArrayEquals(new byte[] {1, 2, 3}, bytes);
        archive.close();
        archive.close();
        assertThrows(IllegalStateException.class, () -> archive.hasFile("entry"));
    }

    @Test
    void decoderUsesTheCommonOwnerAndRejectsUseAfterClose() {
        var decoder = OpusDecoder.create(0);
        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, decoder::endInput);
        assertThrows(IllegalStateException.class, () -> decoder.decode(ByteBuffer.allocateDirect(4)));
        assertThrows(IllegalArgumentException.class, () -> OpusDecoder.create(-1));
    }

    @Test
    void archiveRollsBackBeforeAndAfterWrapperConstruction() throws Exception {
        assertAdoptionRollback(NativeArchive.class, String.class, archive().toString());
    }

    @Test
    void decoderRollsBackBeforeAndAfterWrapperConstruction() throws Exception {
        assertAdoptionRollback(OpusDecoder.class, long.class, 0L);
    }

    @Test
    void checkpointCaptureAndRestoreRollBackUnadoptedOwners() throws Exception {
        try (var decoder = prefixDecoder()) {
            var capture = OpusDecoder.class.getDeclaredMethod("nCaptureCheckpoint", long.class);
            capture.setAccessible(true);
            var ownerType = Class.forName(OpusDecoder.class.getName() + "$CheckpointOwner")
                .asSubclass(NativeObject.class);
            assertAdoptionRollback(ownerType, () -> (long)capture.invoke(null, decoder.get()));

            var checkpoint = decoder.checkpoint();
            var ownerField = checkpoint.getClass().getDeclaredField("owner");
            ownerField.setAccessible(true);
            var owner = (NativeObject)ownerField.get(checkpoint);
            var restore = OpusDecoder.class.getDeclaredMethod("nRestoreCheckpoint", long.class);
            restore.setAccessible(true);
            assertAdoptionRollback(OpusDecoder.class, () -> (long)restore.invoke(null, owner.get()));
            try (var first = checkpoint.restore(); var second = checkpoint.restore()) {
                assertEquals(OpusDecoder.NEED_INPUT, first.decode(ByteBuffer.allocateDirect(64)));
                assertEquals(OpusDecoder.NEED_INPUT, second.decode(ByteBuffer.allocateDirect(64)));
            }
            assertTrue(checkpoint.retainedBytes() > 0);
            assertTrue(checkpoint.emittedFrames() > 0);
        }
    }

    private static void assertAdoptionRollback(Class<? extends NativeObject> type, Class<?> argumentType,
        Object argument) throws Exception {
        var create = type.getDeclaredMethod("nCreate", argumentType);
        create.setAccessible(true);
        assertAdoptionRollback(type, () -> (long)create.invoke(null, argument));
    }

    private static void assertAdoptionRollback(Class<? extends NativeObject> type, Callable<Long> create)
        throws Exception {
        var adopt = NativeObject.class.getDeclaredMethod("adopt", long.class, Function.class);
        adopt.setAccessible(true);
        var stateType = Class.forName(NativeObject.class.getName() + "$State");
        var constructor = type.getDeclaredConstructor(stateType);
        constructor.setAccessible(true);
        var open = stateType.getDeclaredField("open");
        open.setAccessible(true);
        for (boolean constructWrapper : new boolean[] {false, true}) {
            var state = new AtomicReference<Object>();
            var wrapper = new AtomicReference<NativeObject>();
            var failure = new OutOfMemoryError("Injected wrapper allocation or construction failure");
            Function<Object, NativeObject> factory = adopted -> {
                state.set(adopted);
                if (constructWrapper) {
                    try {
                        wrapper.set(constructor.newInstance(adopted));
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError(exception);
                    }
                }
                throw failure;
            };
            var ptr = create.call();
            assertNotEquals(0, ptr);
            var thrown = assertThrows(InvocationTargetException.class, () -> adopt.invoke(null, ptr, factory));
            assertSame(failure, thrown.getCause());
            assertFalse(((AtomicBoolean)open.get(state.get())).get());
            if (wrapper.get() != null) {
                assertThrows(IllegalStateException.class, wrapper.get()::get);
                wrapper.get().close();
            }
        }
    }

    private static OpusDecoder prefixDecoder() throws Exception {
        byte[] encoded;
        try (var resource = NativeObjectAdoptionIntegrationTest.class
            .getResourceAsStream("/audio-fixtures/opus-long.ogg")) {
            encoded = resource.readAllBytes();
        }
        int end = 0;
        for (int page = 0; page < 3; page++) {
            int segments = Byte.toUnsignedInt(encoded[end + 26]);
            int length = 27 + segments;
            for (int i = 0; i < segments; i++) {
                length += Byte.toUnsignedInt(encoded[end + 27 + i]);
            }
            end += length;
        }
        var decoder = OpusDecoder.create(12L * 48_000);
        try {
            var input = ByteBuffer.allocateDirect(end).put(encoded, 0, end).flip();
            var output = ByteBuffer.allocateDirect(64 * 1024);
            for (int i = 0; i < 10_000; i++) {
                int decoded = decoder.decode(output);
                if (decoded > 0) {
                    continue;
                }
                assertEquals(OpusDecoder.NEED_INPUT, decoded);
                if (!input.hasRemaining()) {
                    return decoder;
                }
                assertTrue(decoder.feed(input) > 0);
            }
            throw new AssertionError("Prefix decoder did not reach a drained page boundary");
        } catch (Throwable failure) {
            decoder.close();
            throw failure;
        }
    }

    private Path archive() throws Exception {
        var file = temp.resolve("archive.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new ZipEntry("entry"));
            output.write(new byte[] {1, 2, 3});
        }
        return file;
    }
}
