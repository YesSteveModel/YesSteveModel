package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.model.storage.CacheFile;
import dev.semaweave.compilation.ArtifactCodecs;
import dev.semaweave.program.ReadOnlyBytes;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;

/** Private AOT-only layout; its exact domain excludes the former combined animation files. */
final class AotArtifactEnvelope {
    private static final long MAGIC = 0x59534d414f540001L;
    static final int HEADER_BYTES = Long.BYTES * 2 + 32 * 3;

    private AotArtifactEnvelope() {}

    static void write(CacheFile file, ExpressionArtifactLookup lookup, ExpressionArtifactIdentity identity,
        ReadOnlyBytes artifact) throws IOException {
        var payload = artifact.toByteArray();
        var header =
            ByteBuffer.allocate(HEADER_BYTES).putLong(MAGIC).put(lookup.fingerprint().value().bytes().toByteArray())
                .put(identity.fingerprint().value().bytes().toByteArray()).putLong(payload.length)
                .put(ExpressionIdentityHash.SHA_256.hashCanonicalPreimage(artifact).bytes().toByteArray());
        header.flip();
        file.position(0).truncate(0);
        while (header.hasRemaining())
            file.write(header);
        file.writeBytes(payload);
        file.force();
    }

    static ReadOnlyBytes read(SeekableByteChannel file, ExpressionArtifactLookup lookup,
        ExpressionArtifactIdentity identity) throws IOException {
        file.position(0);
        var header = ByteBuffer.allocate(HEADER_BYTES);
        readFully(file, header);
        header.flip();
        var lookupHash = new byte[32];
        var artifactIdentityHash = new byte[32];
        var contentHash = new byte[32];
        if (header.getLong() != MAGIC)
            throw new IOException("Unsupported expression artifact envelope");
        header.get(lookupHash).get(artifactIdentityHash);
        var length = header.getLong();
        header.get(contentHash);
        if (!MessageDigest.isEqual(lookupHash, lookup.fingerprint().value().bytes().toByteArray())
            || !MessageDigest.isEqual(artifactIdentityHash, identity.fingerprint().value().bytes().toByteArray()))
            throw new IOException("Expression artifact envelope identity mismatch");
        if (length <= 0 || length > Integer.MAX_VALUE
            || length > ArtifactCodecs.v1().maximumEncodedBytes(lookup.expected())
            || file.size() != HEADER_BYTES + length)
            throw new IOException("Expression artifact exceeds its bounded envelope");
        var payload = ByteBuffer.allocate((int)length);
        readFully(file, payload);
        var bytes = ReadOnlyBytes.copyOf(payload.array());
        if (!MessageDigest.isEqual(contentHash,
            ExpressionIdentityHash.SHA_256.hashCanonicalPreimage(bytes).bytes().toByteArray()))
            throw new IOException("Expression artifact payload hash mismatch");
        return bytes;
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0)
                throw new EOFException("Truncated expression artifact envelope");
        }
    }
}
