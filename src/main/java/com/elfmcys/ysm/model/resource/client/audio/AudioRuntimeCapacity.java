package com.elfmcys.ysm.model.resource.client.audio;

/** One capacity authority; decoded data includes ready PCM, candidates and shared PCM/checkpoints held by playback. */
final class AudioRuntimeCapacity {
    static final int DEFAULT_ACTIVE = 32;
    static final int DEFAULT_PREPARING = 8;
    static final long DEFAULT_ENCODED_BYTES = 32L * 1024 * 1024;
    static final long DEFAULT_DECODED_BYTES = 32L * 1024 * 1024;

    private final int activeLimit;
    private final int preparingLimit;
    private final long encodedLimit;
    private final long decodedLimit;
    private int active;
    private int preparing;
    private long encodedBytes;
    private long decodedBytes;

    AudioRuntimeCapacity() {
        this(DEFAULT_ACTIVE, DEFAULT_PREPARING, DEFAULT_ENCODED_BYTES, DEFAULT_DECODED_BYTES);
    }

    AudioRuntimeCapacity(int activeLimit, int preparingLimit, long encodedLimit, long decodedLimit) {
        if (activeLimit < 0 || preparingLimit < 0 || encodedLimit < 0 || decodedLimit < 0) {
            throw new IllegalArgumentException("Audio capacity must not be negative");
        }
        this.activeLimit = activeLimit;
        this.preparingLimit = preparingLimit;
        this.encodedLimit = encodedLimit;
        this.decodedLimit = decodedLimit;
    }

    synchronized boolean acquireActive() {
        if (active >= activeLimit) {
            return false;
        }
        active++;
        return true;
    }

    synchronized void releaseActive() {
        if (active <= 0) {
            throw new IllegalStateException("Active audio capacity underflow");
        }
        active--;
    }

    synchronized boolean acquirePreparing() {
        if (preparing >= preparingLimit) {
            return false;
        }
        preparing++;
        return true;
    }

    synchronized void releasePreparing() {
        if (preparing <= 0) {
            throw new IllegalStateException("Preparing audio capacity underflow");
        }
        preparing--;
    }

    synchronized boolean reserveEncoded(long bytes) {
        if (bytes < 0 || bytes > encodedLimit - encodedBytes) {
            return false;
        }
        encodedBytes += bytes;
        return true;
    }

    synchronized void releaseEncoded(long bytes) {
        if (bytes < 0 || bytes > encodedBytes) {
            throw new IllegalStateException("Encoded audio capacity underflow");
        }
        encodedBytes -= bytes;
    }

    synchronized boolean reserveDecoded(long bytes) {
        if (bytes < 0 || bytes > decodedLimit - decodedBytes) {
            return false;
        }
        decodedBytes += bytes;
        return true;
    }

    synchronized void releaseDecoded(long bytes) {
        if (bytes < 0 || bytes > decodedBytes) {
            throw new IllegalStateException("Decoded audio capacity underflow");
        }
        decodedBytes -= bytes;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(active, preparing, encodedBytes, decodedBytes);
    }

    record Snapshot(int active, int preparing, long encodedBytes, long decodedBytes) {
    }
}
