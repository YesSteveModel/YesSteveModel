package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import com.elfmcys.ysm.natives.NativeArchive;
import com.elfmcys.ysm.format.legacy.LegacyContainerHeader;

/** Change hints, never a proof of source payload integrity. */
final class SourceReuseObservation {
    private SourceReuseObservation() {}

    static Optional<SharedArrayBuffer> capture(SourceObservation source) throws IOException {
        if (standardArchive(source)) {
            try (var archive = NativeArchive.open(source.logicalPath().toString())) {
                return capture(source, archive);
            }
        }
        return capture(source, null);
    }

    static boolean standardArchive(SourceObservation source) {
        return source.key().sourceKind() == ModelSourceKind.CURRENT_RAW_ARCHIVE
            && !source.logicalPath().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".ysm");
    }

    static Optional<SharedArrayBuffer> capture(SourceObservation source, NativeArchive archive) throws IOException {
        if (source.key().sourceKind() != ModelSourceKind.LEGACY_ARCHIVE
            && source.key().sourceKind() != ModelSourceKind.CURRENT_RAW_ARCHIVE
            && (source.key().sourceKind() != ModelSourceKind.CURRENT_RAW_DIRECTORY
                || !Files.isRegularFile(source.absolutePath().resolve("ysm.json"), LinkOption.NOFOLLOW_LINKS)))
            return Optional.empty();
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            // Version 3 uses length-prefixed UTF-8 for archive member names.
            out.writeInt(3);
            out.writeUTF(source.logicalPath().toString());
            out.writeUTF(source.absolutePath().toString());
            out.writeUTF(source.key().sourceKind().name());
            if (source.key().sourceKind() == ModelSourceKind.LEGACY_ARCHIVE) {
                try (var channel = FileChannel.open(source.absolutePath(), StandardOpenOption.READ)) {
                    if (channel.size() < 8)
                        throw new IOException("Legacy container has no checksum footer");
                    var footer = ByteBuffer.allocate(8);
                    channel.position(channel.size() - 8);
                    while (footer.hasRemaining())
                        if (channel.read(footer) < 0)
                            throw new IOException("Legacy footer changed while reading");
                    out.write(footer.array());
                }
            } else if (source.key().sourceKind() == ModelSourceKind.CURRENT_RAW_ARCHIVE) {
                if (standardArchive(source)) {
                    var members = archive.members();
                    if (members.stream().anyMatch(member -> member.name() == null || member.crc().isEmpty()))
                        return Optional.empty();
                    out.writeInt(members.size());
                    for (var member : members.stream()
                        .sorted(java.util.Comparator.comparing(NativeArchive.Member::name)).toList()) {
                        var name = member.name().getBytes(StandardCharsets.UTF_8);
                        out.writeInt(name.length);
                        out.write(name);
                        out.writeLong(member.size());
                        out.writeInt((int)member.crc().orElseThrow());
                    }
                } else {
                    out.write(LegacyContainerHeader.rawDigest(source.absolutePath()));
                }
            } else {
                var stamp = (SourceStamp.RawDirectory)source.stamp();
                out.write(stamp.metadataDigest().bytes());
                out.writeInt(stamp.fileCount());
                out.writeLong(stamp.totalBytes());
            }
        }
        return Optional.of(ArrayBuffer.move(bytes.toByteArray()).shareArray());
    }
}
