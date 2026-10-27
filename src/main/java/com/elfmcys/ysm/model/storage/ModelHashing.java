package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.Blake3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ModelHashing {
    private ModelHashing() {}

    public static Hash256 blake3(byte[] data) {
        return new Hash256(Blake3.computeHash(ArrayBuffer.borrow(data)));
    }

    public static Hash256 blake3(ByteBuffer data) {
        try (var copy = ArrayBuffer.copyOf(data)) {
            return new Hash256(Blake3.computeHash(copy));
        }
    }

    public static Hash256 blake3(UniBuffer data) {
        return new Hash256(Blake3.computeHash(data));
    }

    public static Hash256 blake3(Path file) throws IOException {
        var size = Files.size(file);
        if (size < 0 || size > UniBuffer.MAX_SIZE) {
            throw new IOException("File is too large to hash: " + file);
        }
        try (var source = NativeBuffer.allocate(Math.toIntExact(size));
            var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            var target = source.nio();
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) {
                    throw new IOException("File changed while hashing: " + file);
                }
            }
            return new Hash256(Blake3.computeHash(source));
        }
    }

}
