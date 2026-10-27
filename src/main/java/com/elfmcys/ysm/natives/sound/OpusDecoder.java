package com.elfmcys.ysm.natives.sound;

import com.elfmcys.ysm.natives.NativeObject;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.function.Function;

public final class OpusDecoder extends NativeObject {
    public static final int ERROR = -1;
    public static final int NEED_INPUT = -2;

    private static final Function<State, OpusDecoder> ADOPT = OpusDecoder::new;
    private boolean inputEnded;

    private OpusDecoder(State state) {
        super(state);
    }

    public static OpusDecoder create(long expectedFrames) {
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must not be negative");
        }
        var ptr = nCreate(expectedFrames);
        if (ptr == 0) {
            throw new OutOfMemoryError("Failed to allocate Opus decoder");
        }
        return adopt(ptr, ADOPT);
    }

    public int feed(ByteBuffer data) {
        var ptr = get();
        if (inputEnded) {
            throw new IllegalStateException("Opus input has ended");
        }
        if (!data.isDirect()) {
            throw new IllegalArgumentException("input is not direct data");
        }
        try {
            int consumed = nFeed(ptr, data.slice());
            if (consumed < 0 || consumed > data.remaining()) {
                throw new IllegalArgumentException("Native Opus decoder rejected input");
            }
            data.position(data.position() + consumed);
            return consumed;
        } finally {
            Reference.reachabilityFence(this);
            Reference.reachabilityFence(data);
        }
    }

    public void endInput() {
        var ptr = get();
        if (!inputEnded) {
            try {
                nEndInput(ptr);
                inputEnded = true;
            } finally {
                Reference.reachabilityFence(this);
            }
        }
    }

    public int decode(ByteBuffer destination) {
        var ptr = get();
        if (!destination.isDirect()) {
            throw new IllegalArgumentException("output is not direct data");
        }
        try {
            return nDecode(ptr, destination.slice());
        } finally {
            Reference.reachabilityFence(this);
            Reference.reachabilityFence(destination);
        }
    }

    public long checkpointSize() {
        try {
            long bytes = nCheckpointSize(get());
            if (bytes < 0) {
                throw new IllegalStateException("Opus checkpoint requires a drained, unfinished page boundary");
            }
            return bytes;
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public Checkpoint checkpoint() {
        try {
            long bytes = checkpointSize();
            // Owner initialization can fail before adopt takes responsibility for a raw handle.
            var factory = CheckpointOwner.ADOPT;
            long ptr = nCaptureCheckpoint(get());
            if (ptr == 0) {
                throw new OutOfMemoryError("Failed to allocate Opus checkpoint");
            }
            var owner = adopt(ptr, factory);
            try {
                long frames = nCheckpointEmittedFrames(owner.get());
                if (frames < 0) {
                    throw new IllegalStateException("Invalid Opus checkpoint frame count");
                }
                return new Checkpoint(owner, bytes, frames);
            } catch (RuntimeException | Error failure) {
                owner.close();
                throw failure;
            } finally {
                Reference.reachabilityFence(owner);
            }
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Shared state has no closing capability; each restored decoder owns its mutable timeline. */
    public static final class Checkpoint {
        private final CheckpointOwner owner;
        private final long retainedBytes;
        private final long emittedFrames;

        private Checkpoint(CheckpointOwner owner, long retainedBytes, long emittedFrames) {
            this.owner = owner;
            this.retainedBytes = retainedBytes;
            this.emittedFrames = emittedFrames;
        }

        public long retainedBytes() {
            return retainedBytes;
        }

        public long emittedFrames() {
            return emittedFrames;
        }

        public OpusDecoder restore() {
            try {
                long ptr = nRestoreCheckpoint(owner.get());
                if (ptr == 0) {
                    throw new IllegalStateException("Failed to restore Opus checkpoint");
                }
                return adopt(ptr, ADOPT);
            } finally {
                Reference.reachabilityFence(owner);
            }
        }
    }

    private static final class CheckpointOwner extends NativeObject {
        private static final Function<State, CheckpointOwner> ADOPT = CheckpointOwner::new;

        private CheckpointOwner(State state) {
            super(state);
        }

    }

    private static native long nCreate(long expectedFrames);

    private static native int nFeed(long ptr, ByteBuffer data);

    private static native void nEndInput(long ptr);

    private static native int nDecode(long ptr, ByteBuffer destination);

    private static native long nCheckpointSize(long ptr);

    private static native long nCaptureCheckpoint(long ptr);

    private static native long nCheckpointEmittedFrames(long ptr);

    private static native long nRestoreCheckpoint(long ptr);

    private static native long nCreateCount();

    private static native long nRestoreCount();

    private static native void nFailNextRestore();

    private static native void nPauseNextRestore();

    private static native boolean nRestorePaused();

    private static native void nResumeRestore();
}
