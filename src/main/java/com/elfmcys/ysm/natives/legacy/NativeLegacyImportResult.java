package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.natives.NativeObject;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.function.Function;
import java.util.function.Supplier;

final class NativeLegacyImportResult extends NativeObject {
    private final Output output;

    private NativeLegacyImportResult(State state, Output output) {
        super(state);
        this.output = output;
    }

    static NativeLegacyImportResult read(Supplier<Output> producer) {
        var adoption = new Adoption();
        adoption.output = producer.get();
        if (adoption.output == null) {
            throw new NativeLegacyProtocol.ProtocolException("Native legacy result is null");
        }
        return adopt(adoption.output.ownerHandle(), adoption);
    }

    NativeLegacyImportResult acquire() {
        Function<State, NativeLegacyImportResult> factory = state -> new NativeLegacyImportResult(state, output);
        try {
            var ptr = get();
            if (ptr == 0) {
                throw new IllegalStateException("Native legacy result has no payload owner");
            }
            var shared = NativeLegacyImporter.nShare(ptr);
            if (shared == 0) {
                throw new OutOfMemoryError("Failed to share native legacy result");
            }
            return adopt(shared, factory);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    int statusCode() {
        return output.statusCode();
    }

    String diagnostic() {
        return output.diagnostic();
    }

    byte[] descriptor() {
        return output.descriptor();
    }

    ByteBuffer[] payloads() {
        return output.payloads();
    }

    private static final class Adoption implements Function<State, NativeLegacyImportResult> {
        private Output output;

        @Override
        public NativeLegacyImportResult apply(State state) {
            return new NativeLegacyImportResult(state, output);
        }
    }

    /** JNI construction stays native-owned until this carrier is complete. */
    record Output(int statusCode, String diagnostic, long ownerHandle, byte[] descriptor, ByteBuffer[] payloads) {
        Output {
            descriptor = descriptor == null ? null : descriptor.clone();
            payloads = payloads == null ? null : payloads.clone();
        }
    }
}
