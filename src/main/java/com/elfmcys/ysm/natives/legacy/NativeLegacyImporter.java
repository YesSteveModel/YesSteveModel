package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.buffer.BufferArgument;

import java.io.EOFException;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

final class NativeLegacyImporter {
    private NativeLegacyImporter() {}

    static NativeLegacyProtocol.Response invoke(Path source) {
        Objects.requireNonNull(source, "source");
        try {
            var size = Files.size(source);
            if (size > UniBuffer.MAX_SIZE) {
                return new NativeLegacyProtocol.Failure(NativeLegacyStatus.RESOURCE_LIMIT,
                    "Legacy source exceeds the Java buffer limit");
            }
            try (var bytes = NativeBuffer.allocate(Math.toIntExact(size));
                var channel = FileChannel.open(source, StandardOpenOption.READ)) {
                var destination = bytes.nio();
                while (destination.hasRemaining()) {
                    if (channel.read(destination) < 0) {
                        throw new EOFException("Legacy source was truncated while reading");
                    }
                }
                return invoke(bytes);
            }
        } catch (IOException | SecurityException failure) {
            return new NativeLegacyProtocol.Failure(NativeLegacyStatus.SOURCE_IO, "Unable to read legacy source");
        }
    }

    private static NativeLegacyProtocol.Response invoke(NativeBuffer source) {
        var input = BufferArgument.packInput(source);
        try {
            var result = NativeLegacyImportResult.read(() -> nImport(input.obj(), input.flags()));
            return NativeLegacyProtocol.accept(result);
        } finally {
            Reference.reachabilityFence(source);
        }
    }

    private static native NativeLegacyImportResult.Output nImport(Object source, long sourceFlags);

    static native long nShare(long handle);
}
