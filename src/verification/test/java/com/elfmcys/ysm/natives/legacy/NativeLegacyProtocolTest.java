package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.natives.NativeObject;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeLegacyProtocolTest {
    @Test
    void validatesDescriptorAndReleasesResultExactlyOnce() {
        var payloads = minimalPayloads();
        var raw = result(0, "", true, descriptor(payloads, 17, 1234), payloads);

        var response = NativeLegacyProtocol.accept(raw);
        var success = assertInstanceOf(NativeLegacyProtocol.Success.class, response);
        assertEquals(17, success.descriptor().innerVersion());
        assertEquals(1234, success.descriptor().sourceSize());
        assertArrayEquals(new byte[32], success.descriptor().modelId());
        assertEquals(3, success.descriptor().records().size());
        assertTrue(success.payloads().get(0).nio().isReadOnly());

        success.close();
        success.close();
        assertThrows(IllegalStateException.class, raw::get);
        assertThrows(IllegalStateException.class, success::payloads);
    }

    @Test
    void retainedWriterBufferOutlivesThePublicResultGate() {
        var payloads = minimalPayloads();
        var raw = result(0, "", true, descriptor(payloads, 1, 64), payloads);
        var success = assertInstanceOf(NativeLegacyProtocol.Success.class, NativeLegacyProtocol.accept(raw));
        var publicView = success.payloads().get(2);
        var writerOwned = publicView.acquire();
        var alias = writerOwned.slice(0, writerOwned.size());

        success.close();
        assertThrows(IllegalStateException.class, publicView::nio);
        assertEquals("model", StandardCharsets.UTF_8.decode(writerOwned.nio()).toString());
        var retainedAgain = writerOwned.acquire();

        writerOwned.close();
        writerOwned.close();
        assertThrows(IllegalStateException.class, alias::nio);
        assertThrows(IllegalStateException.class, writerOwned::nio);
        assertEquals("model", StandardCharsets.UTF_8.decode(retainedAgain.nio()).toString());
        retainedAgain.close();
    }

    @Test
    void closingBorrowedViewDoesNotCloseItsParent() {
        var payloads = minimalPayloads();
        var raw = result(0, "", true, descriptor(payloads, 1, 64), payloads);
        var success = assertInstanceOf(NativeLegacyProtocol.Success.class, NativeLegacyProtocol.accept(raw));
        var publicView = success.payloads().get(2);
        var borrowed = publicView.borrow();

        borrowed.close();
        assertThrows(IllegalStateException.class, borrowed::nio);
        assertEquals("model", StandardCharsets.UTF_8.decode(publicView.nio()).toString());

        success.close();
    }

    @Test
    void rejectsNonDirectPayloadAndClosesTheNativeOwner() {
        var payloads = minimalPayloads();
        payloads[2] = ByteBuffer.wrap(new byte[5]).asReadOnlyBuffer();
        assertInvalidBuffer(payloads);
    }

    @Test
    void rejectsWritablePayloadAndClosesTheNativeOwner() {
        var payloads = minimalPayloads();
        payloads[2] = ByteBuffer.allocateDirect(5);
        assertInvalidBuffer(payloads);
    }

    @Test
    void rejectsPayloadWithNonzeroPositionAndClosesTheNativeOwner() {
        var payloads = minimalPayloads();
        var positioned = ByteBuffer.allocateDirect(6).asReadOnlyBuffer();
        positioned.position(1);
        payloads[2] = positioned;
        assertInvalidBuffer(payloads);
    }

    private static void assertInvalidBuffer(ByteBuffer[] payloads) {
        var raw = result(0, "", true, descriptor(payloads, 1, 64), payloads);

        var error = assertThrows(NativeLegacyProtocol.ProtocolException.class, () -> NativeLegacyProtocol.accept(raw));
        assertEquals(NativeLegacyStatus.RESULT_PROTOCOL, error.status());
        assertThrows(IllegalStateException.class, raw::get);
    }

    @Test
    void rejectsAliasedPayloadBackingReservedAndUnknownStatus() {
        var shared = ByteBuffer.allocateDirect(5);
        var payloads =
            new ByteBuffer[] {shared.asReadOnlyBuffer(), shared.asReadOnlyBuffer(), shared.asReadOnlyBuffer(),};
        var aliased = result(0, "", true, descriptor(payloads, 1, 64), payloads);
        assertThrows(NativeLegacyProtocol.ProtocolException.class, () -> NativeLegacyProtocol.accept(aliased));

        var reserved = result(7, "", true, new byte[0], new ByteBuffer[0]);
        var reservedError =
            assertThrows(NativeLegacyProtocol.ProtocolException.class, () -> NativeLegacyProtocol.accept(reserved));
        assertTrue(reservedError.getMessage().contains("7"));

        var unknown = result(99, "", true, new byte[0], new ByteBuffer[0]);
        var unknownError =
            assertThrows(NativeLegacyProtocol.ProtocolException.class, () -> NativeLegacyProtocol.accept(unknown));
        assertTrue(unknownError.getMessage().contains("99"));
    }

    @Test
    void acceptsClosedFailureShapeWithoutOwnership() {
        var response = NativeLegacyProtocol.accept(result(3, "invalid", false, new byte[0], new ByteBuffer[0]));
        var failure = assertInstanceOf(NativeLegacyProtocol.Failure.class, response);
        assertEquals(NativeLegacyStatus.INVALID_CONTENT, failure.status());
        assertEquals("invalid", failure.diagnostic());
    }

    @Test
    void acceptsTargetRepresentationFailureAndClosesDescriptorLayout() {
        var failure = assertInstanceOf(NativeLegacyProtocol.Failure.class,
            NativeLegacyProtocol.accept(result(11, "not representable", false, new byte[0], new ByteBuffer[0])));
        assertEquals(NativeLegacyStatus.TARGET_REPRESENTATION, failure.status());

        var payloads = minimalPayloads();
        var exact = descriptor(payloads, 1, 64);
        var old = oldVersionedDescriptor(payloads, 1, 64);
        for (var invalid : new byte[][] {Arrays.copyOf(exact, exact.length - 1), Arrays.copyOf(exact, exact.length + 1),
            old}) {
            assertThrows(NativeLegacyProtocol.ProtocolException.class,
                () -> NativeLegacyProtocol.accept(result(0, "", true, invalid, payloads)));
        }
    }

    @Test
    void rejectsNonzeroLegacyModelIdTailAndClosesTheNativeOwner() {
        var payloads = minimalPayloads();
        var invalid = descriptor(payloads, 1, 64);
        invalid[28 + 16] = 1;

        var failure = assertThrows(NativeLegacyProtocol.ProtocolException.class,
            () -> NativeLegacyProtocol.accept(result(0, "", true, invalid, payloads)));

        assertEquals(NativeLegacyStatus.RESULT_PROTOCOL, failure.status());
    }

    @Test
    void rejectsNonzeroDescriptorReservedFieldAndClosesTheNativeOwner() {
        var payloads = minimalPayloads();
        var invalid = descriptor(payloads, 1, 64);
        ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1);

        var failure = assertThrows(NativeLegacyProtocol.ProtocolException.class,
            () -> NativeLegacyProtocol.accept(result(0, "", true, invalid, payloads)));

        assertEquals(NativeLegacyStatus.RESULT_PROTOCOL, failure.status());
    }

    @Test
    void requiresSequentialSoundIdsAndUnsignedUtf8NameOrder() {
        var payloads = payloadsWithSounds();
        var accepted = assertInstanceOf(NativeLegacyProtocol.Success.class,
            NativeLegacyProtocol.accept(result(0, "", true, descriptorWithSounds(payloads, 1, "a", 2, "b"), payloads)));
        accepted.close();

        for (var invalid : new byte[][] {descriptorWithSounds(payloads, 2, "a", 3, "b"),
            descriptorWithSounds(payloads, 1, "b", 2, "a")}) {
            assertThrows(NativeLegacyProtocol.ProtocolException.class,
                () -> NativeLegacyProtocol.accept(result(0, "", true, invalid, payloads)));
        }
    }

    @Test
    void recognizesZtxButNeverRgbaAsACompressedImageEncoding() {
        assertEquals(NativeLegacyProtocol.PayloadEncoding.ZTX, NativeLegacyProtocol.PayloadEncoding.fromCode(8));
        assertTrue(NativeLegacyProtocol.PayloadEncoding.ZTX.isImage());
        assertFalse(NativeLegacyProtocol.PayloadEncoding.RGBA.isImage());
    }

    @Test
    void explicitCloseAndLateCleanerShareOneReleaseGate() throws Exception {
        var payloads = minimalPayloads();
        var owner = result(0, "", true, descriptor(payloads, 1, 64), payloads);
        var lease = owner.acquire();
        var cleaner = NativeObject.class.getDeclaredField("cleanable");
        cleaner.setAccessible(true);
        owner.close();
        ((Cleaner.Cleanable)cleaner.get(owner)).clean();
        assertThrows(IllegalStateException.class, owner::get);
        assertTrue(lease.get() != 0);
        lease.close();
        lease.close();
        assertThrows(IllegalStateException.class, lease::get);
    }

    @Test
    void rejectsMissingNativeOutputAsAProtocolFailure() {
        assertThrows(NativeLegacyProtocol.ProtocolException.class, () -> NativeLegacyImportResult.read(() -> null));
        assertThrows(NativeLegacyProtocol.ProtocolException.class, () -> NativeLegacyProtocol.accept(null));
    }

    private static NativeLegacyImportResult result(int status, String diagnostic, boolean owns, byte[] descriptor,
        ByteBuffer[] payloads) {
        // Protocol fixtures need real opaque handles, but do not inspect their payload type.
        try (var backing = NativeModelState.create()) {
            return NativeLegacyImportResult.read(() -> new NativeLegacyImportResult.Output(status, diagnostic,
                owns ? NativeLegacyImporter.nShare(backing.get()) : 0, descriptor, payloads));
        }
    }

    private static ByteBuffer[] minimalPayloads() {
        return new ByteBuffer[] {directReadOnly("manifest"), ByteBuffer.allocateDirect(0).asReadOnlyBuffer(),
            directReadOnly("model"),};
    }

    private static ByteBuffer[] payloadsWithSounds() {
        return new ByteBuffer[] {directReadOnly("manifest"), ByteBuffer.allocateDirect(0).asReadOnlyBuffer(),
            directReadOnly("model"), directReadOnly("vorbis"), directReadOnly("opus"),};
    }

    private static ByteBuffer directReadOnly(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return buffer.asReadOnlyBuffer();
    }

    private static byte[] descriptor(ByteBuffer[] payloads, int innerVersion, long sourceSize) {
        byte[] player = "player".getBytes(StandardCharsets.UTF_8);
        int size = 60 + 40 * 3 + player.length;
        var output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        output.put(new byte[] {'Y', 'S', 'M', 'L', 'E', 'G', 'I', 0});
        output.putInt(innerVersion);
        output.putInt(3);
        output.putInt(0);
        output.putLong(sourceSize);
        output.put(new byte[32]);
        putRecord(output, 1, 0, 0, "", payloads[0].capacity());
        putRecord(output, 2, 0, 1, "", payloads[1].capacity());
        putRecord(output, 3, 0, 2, "player", payloads[2].capacity());
        return output.array();
    }

    private static byte[] oldVersionedDescriptor(ByteBuffer[] payloads, int innerVersion, long sourceSize) {
        byte[] player = "player".getBytes(StandardCharsets.UTF_8);
        int size = 64 + 40 * 3 + player.length;
        var output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        output.put(new byte[] {'Y', 'S', 'M', 'L', 'E', 'G', 'I', 0});
        output.putShort((short)1).putShort((short)0);
        output.putInt(innerVersion).putInt(3).putInt(0).putLong(sourceSize);
        output.put(new byte[32]);
        putRecord(output, 1, 0, 0, "", payloads[0].capacity());
        putRecord(output, 2, 0, 1, "", payloads[1].capacity());
        putRecord(output, 3, 0, 2, "player", payloads[2].capacity());
        return output.array();
    }

    private static byte[] descriptorWithSounds(ByteBuffer[] payloads, int firstId, String firstName, int secondId,
        String secondName) {
        byte[] player = "player".getBytes(StandardCharsets.UTF_8);
        int size = 60 + 40 * payloads.length + player.length + firstName.getBytes(StandardCharsets.UTF_8).length
            + secondName.getBytes(StandardCharsets.UTF_8).length;
        var output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        output.put(new byte[] {'Y', 'S', 'M', 'L', 'E', 'G', 'I', 0});
        output.putInt(1);
        output.putInt(payloads.length);
        output.putInt(0);
        output.putLong(64);
        output.put(new byte[32]);
        putRecord(output, 1, 0, 0, "", payloads[0].capacity());
        putRecord(output, 2, 0, 1, "", payloads[1].capacity());
        putRecord(output, 3, 0, 2, "player", payloads[2].capacity());
        putRecord(output, 6, 6, firstId, firstName, 2, 44_100, 960, payloads[3].capacity());
        putRecord(output, 6, 7, secondId, secondName, 2, 48_000, 960, payloads[4].capacity());
        return output.array();
    }

    private static void putRecord(ByteBuffer output, int kind, int encoding, int logicalId, String name,
        int payloadSize) {
        putRecord(output, kind, encoding, logicalId, name, 0, 0, 0, payloadSize);
    }

    private static void putRecord(ByteBuffer output, int kind, int encoding, int logicalId, String name, int meta0,
        int meta1, long meta2, int payloadSize) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        output.putShort((short)kind);
        output.putShort((short)encoding);
        output.putInt(0);
        output.putInt(logicalId);
        output.putInt(nameBytes.length);
        output.putInt(meta0);
        output.putInt(meta1);
        output.putLong(meta2);
        output.putLong(payloadSize);
        output.put(nameBytes);
    }
}
