package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Immutable decoded audio retention with access-ordered eviction. Playback cursors never live in this cache. */
final class AudioRetentionCache implements AutoCloseable {
    static final long DEFAULT_BUDGET = 64L * 1024 * 1024;
    static final long DEFAULT_TTL_NANOS = 30_000_000_000L;

    private final long budget;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final Map<AudioCacheKey, Entry> entries = new HashMap<>();
    private final Map<AudioCacheKey, Receipt> latestReceipts = new HashMap<>();
    private long retainedBytes;
    private long sequence;
    private boolean closed;

    AudioRetentionCache() {
        this(DEFAULT_BUDGET, DEFAULT_TTL_NANOS, System::nanoTime);
    }

    AudioRetentionCache(long budget, long ttlNanos, LongSupplier clock) {
        if (budget < 0 || ttlNanos < 0) {
            throw new IllegalArgumentException("Audio retention limits must not be negative");
        }
        this.budget = budget;
        this.ttlNanos = ttlNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized Lookup begin(AudioCacheKey key) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        long now = clock.getAsLong();
        expire(now);
        var receipt = new Receipt(now, ++sequence);
        latestReceipts.put(key, receipt);
        var entry = entries.get(key);
        if (entry == null) {
            return new Lookup(null, receipt);
        }
        entry.receipt = receipt;
        return new Lookup(entry.audio, receipt);
    }

    synchronized boolean publish(AudioCacheKey key, CachedAudio audio, Receipt candidateReceipt) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(candidateReceipt, "candidateReceipt");
        long now = clock.getAsLong();
        expire(now);
        if (!candidateReceipt.equals(latestReceipts.get(key))) {
            return false;
        }
        var existing = entries.get(key);
        if (existing != null) {
            return existing.audio == audio;
        }
        if (expired(candidateReceipt, now) || audio.retainedBytes() > budget) {
            return false;
        }
        entries.put(key, new Entry(audio, candidateReceipt));
        retainedBytes = Math.addExact(retainedBytes, audio.retainedBytes());
        evictToBudget();
        ResourceDiagnostics.log("audio decoded retain: {} bytes={} entries={} retainedBytes={}", key, audio.retainedBytes(),
            entries.size(), retainedBytes);
        return entries.get(key) != null && entries.get(key).audio == audio;
    }

    synchronized void maintain() {
        if (!closed) {
            expire(clock.getAsLong());
        }
    }

    synchronized long retainedBytes() {
        return retainedBytes;
    }

    synchronized int entryCount() {
        return entries.size();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        entries.clear();
        latestReceipts.clear();
        retainedBytes = 0;
    }

    private void expire(long now) {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!expired(entry.getValue().receipt, now)) {
                continue;
            }
            retainedBytes -= entry.getValue().audio.retainedBytes();
            latestReceipts.remove(entry.getKey(), entry.getValue().receipt);
            iterator.remove();
            ResourceDiagnostics.log("audio decoded expire: {} entries={} retainedBytes={}", entry.getKey(), entries.size(),
                retainedBytes);
        }
        latestReceipts.entrySet()
            .removeIf(entry -> !entries.containsKey(entry.getKey()) && expired(entry.getValue(), now));
    }

    private boolean expired(Receipt receipt, long now) {
        return now - receipt.acquiredAtNanos >= ttlNanos;
    }

    private void evictToBudget() {
        while (retainedBytes > budget) {
            Map.Entry<AudioCacheKey, Entry> oldest = null;
            for (var entry : entries.entrySet()) {
                if (oldest == null || entry.getValue().receipt.sequence < oldest.getValue().receipt.sequence) {
                    oldest = entry;
                }
            }
            if (oldest == null) {
                throw new IllegalStateException("Audio retention ledger is inconsistent");
            }
            entries.remove(oldest.getKey());
            latestReceipts.remove(oldest.getKey(), oldest.getValue().receipt);
            retainedBytes -= oldest.getValue().audio.retainedBytes();
            ResourceDiagnostics.log("audio decoded evict: {} entries={} retainedBytes={}", oldest.getKey(), entries.size(),
                retainedBytes);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Audio runtime is closed");
        }
    }

    record Receipt(long acquiredAtNanos, long sequence) {
    }

    record Lookup(@Nullable CachedAudio audio, Receipt receipt) {
    }

    private static final class Entry {
        private final CachedAudio audio;
        private Receipt receipt;

        private Entry(CachedAudio audio, Receipt receipt) {
            this.audio = audio;
            this.receipt = receipt;
        }
    }
}
