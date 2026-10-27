package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRetentionCacheTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 16, 1, true, false);

    @Test
    void ttlStartsAgainAtTheLatestLookup() {
        var clock = new AtomicLong();
        try (var cache = new AudioRetentionCache(64, 30, clock::get)) {
            var key = key(1);
            var initial = cache.begin(key);
            var pcm = pcm(10);
            assertTrue(cache.publish(key, pcm, initial.receipt()));

            clock.set(29);
            assertSame(pcm, cache.begin(key).audio());
            clock.set(58);
            assertSame(pcm, cache.begin(key).audio());
            clock.set(88);
            assertNull(cache.begin(key).audio());
            assertEquals(0, cache.retainedBytes());
        }
    }

    @Test
    void decodedBudgetEvictsTheLeastRecentlyUsedEntry() {
        try (var cache = new AudioRetentionCache(64, 1_000, () -> 0)) {
            var a = key(1);
            var b = key(2);
            var c = key(3);
            assertTrue(cache.publish(a, pcm(24), cache.begin(a).receipt()));
            assertTrue(cache.publish(b, pcm(24), cache.begin(b).receipt()));
            assertTrue(cache.begin(a).audio() != null);
            assertTrue(cache.publish(c, pcm(24), cache.begin(c).receipt()));

            assertNull(cache.begin(b).audio());
            assertTrue(cache.begin(a).audio() != null);
            assertTrue(cache.begin(c).audio() != null);
            assertEquals(48, cache.retainedBytes());
        }
    }

    @Test
    void onlyTheLatestColdLookupMayPublish() {
        try (var cache = new AudioRetentionCache(64, 30, () -> 0)) {
            var key = key(1);
            var stale = cache.begin(key).receipt();
            var current = cache.begin(key).receipt();

            assertFalse(cache.publish(key, pcm(12), stale));
            var retained = pcm(12);
            assertTrue(cache.publish(key, retained, current));
            assertSame(retained, cache.begin(key).audio());
        }
    }

    @Test
    void oversizedPcmIsNotRetained() {
        try (var cache = new AudioRetentionCache(64, 30, () -> 0)) {
            var key = key(1);
            assertFalse(cache.publish(key, pcm(66), cache.begin(key).receipt()));
            assertNull(cache.begin(key).audio());
            assertEquals(0, cache.retainedBytes());
        }
    }

    @Test
    void segmentedPcmFreezesOwnershipAndReadsOnlyTheRequestedRange() {
        var segments = new ArrayList<byte[]>();
        segments.add(new byte[] {1, 2});
        segments.add(new byte[0]);
        segments.add(new byte[] {3, 4, 5, 6});
        segments.add(new byte[] {7, 8});
        var pcm = new PcmAudio(segments, FORMAT);
        segments.clear();

        assertEquals(8, pcm.size());
        assertArrayEquals(new byte[] {2, 3, 4, 5, 6, 7}, bytes(pcm.read(1, 6)));
        assertArrayEquals(new byte[] {3, 4}, bytes(pcm.read(2, 2)));
        assertArrayEquals(new byte[] {7, 8}, bytes(pcm.read(6, 2)));
        assertEquals(0, pcm.read(8, 0).remaining());
        assertThrows(ReadOnlyBufferException.class, () -> pcm.read(1, 6).put(0, (byte)0));
        assertThrows(ReadOnlyBufferException.class, () -> pcm.read(2, 2).put(0, (byte)0));
        assertThrows(IndexOutOfBoundsException.class, () -> pcm.read(7, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> pcm.read(-1, 1));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, bytes(pcm.read(0, pcm.size())));
    }

    @Test
    void retainedPlaybackReferenceSurvivesEvictionAndCacheClose() {
        var original = new CachedAudio.Complete(new PcmAudio(List.of(new byte[] {1, 2}, new byte[] {3, 4}), FORMAT));
        var cache = new AudioRetentionCache(4, 30, () -> 0);
        var a = key(1);
        assertTrue(cache.publish(a, original, cache.begin(a).receipt()));
        var active = cache.begin(a).audio();
        var b = key(2);
        assertTrue(cache.publish(b, pcm(4), cache.begin(b).receipt()));
        assertNull(cache.begin(a).audio());
        cache.close();

        assertEquals(0, cache.retainedBytes());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes(active.pcm().read(0, 4)));
    }

    @Test
    void completeRequiresWholeFramesInTheDefaultMonoFormat() {
        assertThrows(IllegalArgumentException.class, () -> new CachedAudio.Complete(new PcmAudio(new byte[3], FORMAT)));
        assertThrows(IllegalArgumentException.class, () -> new CachedAudio.Complete(
            new PcmAudio(new byte[4], new AudioFormat(48_000, 16, 2, true, false))));
        assertThrows(IllegalArgumentException.class, () -> new CachedAudio.Complete(
            new PcmAudio(new byte[4], new AudioFormat(48_000, 16, 1, true, true))));
        assertThrows(IllegalArgumentException.class, () -> new CachedAudio.Complete(
            new PcmAudio(new byte[4], new AudioFormat(44_100, 16, 1, true, false))));
    }

    @Test
    void decodedReservationFailurePreservesExistingReservations() {
        var capacity = new AudioRuntimeCapacity(1, 1, 8, 10);
        assertTrue(capacity.reserveDecoded(6));
        assertFalse(capacity.reserveDecoded(5));
        assertEquals(6, capacity.snapshot().decodedBytes());
        assertTrue(capacity.reserveDecoded(4));
        assertFalse(capacity.reserveDecoded(1));
        capacity.releaseDecoded(6);
        assertEquals(4, capacity.snapshot().decodedBytes());
        capacity.releaseDecoded(4);
        assertEquals(0, capacity.snapshot().decodedBytes());
        assertThrows(IllegalStateException.class, () -> capacity.releaseDecoded(1));
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    @ExtendWith(NativeLibraryExtension.class)
    class PrefixRetention {
        @TempDir
        Path temp;

        @Test
        void checkpointBytesParticipateInBudgetAndFrameValidation() throws Exception {
            var prefix = prefix();
            assertTrue(prefix.checkpoint().retainedBytes() > 0);
            assertEquals(prefix.pcm().size() + prefix.checkpoint().retainedBytes(), prefix.retainedBytes());
            assertThrows(IllegalArgumentException.class, () -> new CachedAudio.Prefix(
                new PcmAudio(new byte[prefix.pcm().size() + 2], FORMAT), prefix.checkpoint()));
            try (var cache = new AudioRetentionCache(prefix.pcm().size(), 30, () -> 0)) {
                var key = key(1);
                assertFalse(cache.publish(key, prefix, cache.begin(key).receipt()));
                assertEquals(0, cache.retainedBytes());
                assertEquals(0, cache.entryCount());
            }
            var capacity = new AudioRuntimeCapacity(1, 1, 0, prefix.retainedBytes());
            assertTrue(capacity.reserveDecoded(prefix.retainedBytes()));
            assertFalse(capacity.reserveDecoded(2));
            capacity.releaseDecoded(prefix.retainedBytes());
            assertEquals(0, capacity.snapshot().decodedBytes());
        }

        @Test
        void prefixEvictionAndExpiryReleaseTheWholeEntry() throws Exception {
            var prefix = prefix();
            var clock = new AtomicLong();
            try (var cache = new AudioRetentionCache(prefix.retainedBytes(), 30, clock::get)) {
                var a = key(1);
                assertTrue(cache.publish(a, prefix, cache.begin(a).receipt()));
                assertEquals(prefix.retainedBytes(), cache.retainedBytes());
                var active = cache.begin(a).audio();
                var b = key(2);
                assertTrue(cache.publish(b, pcm(2), cache.begin(b).receipt()));
                assertNull(cache.begin(a).audio());
                assertEquals(2, cache.retainedBytes());
                assertSame(prefix, active);
                assertEquals(prefix.pcm().size(), active.pcm().read(0, active.pcm().size()).remaining());
                assertTrue(cache.publish(a, prefix, cache.begin(a).receipt()));
                assertEquals(prefix.retainedBytes(), cache.retainedBytes());
                clock.set(29);
                assertSame(prefix, cache.begin(a).audio());
                clock.set(58);
                cache.maintain();
                assertEquals(prefix.retainedBytes(), cache.retainedBytes());
                clock.set(59);
                cache.maintain();
                assertEquals(0, cache.retainedBytes());
                assertEquals(0, cache.entryCount());
            }
        }

        private CachedAudio.Prefix prefix() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-long.ogg")) {
                return fixture.prefix();
            }
        }
    }

    private static CachedAudio.Complete pcm(int size) {
        return new CachedAudio.Complete(new PcmAudio(new byte[size], FORMAT));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static AudioCacheKey key(int value) {
        var model = new byte[Hash256.SIZE];
        var container = new byte[Hash256.SIZE];
        var stream = new byte[Hash256.SIZE];
        model[0] = (byte)value;
        container[0] = (byte)(value + 1);
        stream[0] = (byte)(value + 2);
        return new AudioCacheKey(new ModelFileIdentity(new Hash256(model), new Hash256(container)), new Hash256(stream),
            2, 1_000, 312, 0, 1);
    }
}
