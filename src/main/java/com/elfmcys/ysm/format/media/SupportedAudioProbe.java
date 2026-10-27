package com.elfmcys.ysm.format.media;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/** Interprets the complete Ogg media profiles supported by model audio. */
public final class SupportedAudioProbe {
    private static final byte[] OGG = {'O', 'g', 'g', 'S'};
    private static final byte[] OPUS_HEAD = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
    private static final byte[] OPUS_TAGS = {'O', 'p', 'u', 's', 'T', 'a', 'g', 's'};
    private static final byte[] VORBIS_IDENTIFICATION = {1, 'v', 'o', 'r', 'b', 'i', 's'};
    private static final byte[] VORBIS_COMMENT = {3, 'v', 'o', 'r', 'b', 'i', 's'};
    private static final byte[] VORBIS_SETUP = {5, 'v', 'o', 'r', 'b', 'i', 's'};
    private static final int[] OGG_CRC_TABLE = createCrcTable();

    private SupportedAudioProbe() {}

    public static Inspection inspect(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        var parser = new Parser(source.duplicate());
        try {
            return Inspection.playable(parser.parse());
        } catch (UnknownMedia exception) {
            return Inspection.failure(Disposition.UNKNOWN, exception.getMessage());
        } catch (InvalidMedia | ArithmeticException exception) {
            var disposition = parser.encoding == null ? Disposition.UNKNOWN : Disposition.CORRUPT_SUPPORTED;
            return Inspection.failure(disposition, exception.getMessage());
        }
    }

    public static Inspection admit(ByteBuffer source, Encoding encoding, int channels, long sampleRate, long frames) {
        Objects.requireNonNull(encoding, "encoding");
        var inspection = inspect(source);
        if (!inspection.playable()) {
            return inspection;
        }
        var media = inspection.media();
        if (media.encoding() != encoding || media.channels() != channels || media.sampleRate() != sampleRate
            || media.frames() != frames) {
            return Inspection.failure(Disposition.CORRUPT_SUPPORTED,
                "declared audio metadata does not match the encoded stream");
        }
        return inspection;
    }

    public static Optional<Encoding> detect(ByteBuffer source) {
        var inspection = inspect(source);
        return inspection.playable() ? Optional.of(inspection.media().encoding()) : Optional.empty();
    }

    public enum Encoding {
        OGG_VORBIS, OGG_OPUS
    }

    public enum Disposition {
        PLAYABLE, UNKNOWN, CORRUPT_SUPPORTED
    }

    public record MediaInfo(Encoding encoding, int channels, long sampleRate, long frames, int preSkip,
        int outputGain) {
        public MediaInfo {
            Objects.requireNonNull(encoding, "encoding");
            if ((channels != 1 && channels != 2) || sampleRate <= 0 || frames < 0 || preSkip < 0
                || outputGain < Short.MIN_VALUE || outputGain > Short.MAX_VALUE) {
                throw new IllegalArgumentException("invalid audio metadata");
            }
        }

        public boolean shortEligible() {
            return frames < Math.multiplyExact(4L, sampleRate);
        }
    }

    public record Inspection(Disposition disposition, @Nullable MediaInfo media, String diagnostic) {
        public Inspection {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(diagnostic, "diagnostic");
            if ((disposition == Disposition.PLAYABLE) != (media != null)) {
                throw new IllegalArgumentException("playable inspection requires media metadata");
            }
        }

        public boolean playable() {
            return disposition == Disposition.PLAYABLE;
        }

        private static Inspection playable(MediaInfo media) {
            return new Inspection(Disposition.PLAYABLE, media, "");
        }

        private static Inspection failure(Disposition disposition, String diagnostic) {
            return new Inspection(disposition, null, diagnostic == null ? "invalid media" : diagnostic);
        }
    }

    private static final class Parser {
        private final ByteBuffer source;
        private final ByteArrayOutputStream packet = new ByteArrayOutputStream();
        private @Nullable Timeline timeline;
        private @Nullable Encoding encoding;
        private long serial;
        private long expectedSequence;
        private boolean firstPage = true;
        private boolean sawEos;

        private Parser(ByteBuffer source) {
            this.source = source;
        }

        private MediaInfo parse() throws InvalidMedia, UnknownMedia {
            while (source.hasRemaining()) {
                parsePage();
            }
            require(!firstPage, "empty Ogg stream");
            require(sawEos, "Ogg stream has no EOS page");
            require(packet.size() == 0, "Ogg stream ends in a partial packet");
            if (timeline == null) {
                throw new UnknownMedia("Ogg stream has no supported identification packet");
            }
            return timeline.finish();
        }

        private void parsePage() throws InvalidMedia, UnknownMedia {
            int pageOffset = source.position();
            require(source.remaining() >= 27, "truncated Ogg page header");
            require(matches(source, pageOffset, OGG), "invalid Ogg capture pattern");
            require(unsignedByte(pageOffset + 4) == 0, "unsupported Ogg version");

            int flags = unsignedByte(pageOffset + 5);
            require((flags & ~0x07) == 0, "invalid Ogg page flags");
            boolean continued = (flags & 0x01) != 0;
            boolean bos = (flags & 0x02) != 0;
            boolean eos = (flags & 0x04) != 0;
            long granule = littleLong(pageOffset + 6);
            long pageSerial = unsignedInt(pageOffset + 14);
            long sequence = unsignedInt(pageOffset + 18);
            int segmentCount = unsignedByte(pageOffset + 26);
            require(source.remaining() >= 27 + segmentCount, "truncated Ogg segment table");

            int bodySize = 0;
            for (int index = 0; index < segmentCount; index++) {
                bodySize += unsignedByte(pageOffset + 27 + index);
            }
            int pageSize = 27 + segmentCount + bodySize;
            require(source.remaining() >= pageSize, "truncated Ogg page body");
            if (firstPage) {
                rememberSupportedEncoding(pageOffset + 27 + segmentCount);
            }
            require(checksum(pageOffset, pageSize) == unsignedInt(pageOffset + 22), "Ogg page checksum mismatch");

            if (firstPage) {
                require(bos && !continued && sequence == 0, "invalid initial Ogg page");
                serial = pageSerial;
                firstPage = false;
            } else {
                require(!bos, "multiple Ogg logical streams are not supported");
                require(pageSerial == serial, "multiple Ogg serial numbers are not supported");
                require(sequence == expectedSequence, "non-contiguous Ogg page sequence");
                require(continued == (packet.size() != 0), "invalid Ogg packet continuation");
            }
            require(!sawEos, "bytes follow the Ogg EOS page");
            expectedSequence = (sequence + 1) & 0xffff_ffffL;

            int cursor = pageOffset + 27 + segmentCount;
            for (int index = 0; index < segmentCount; index++) {
                int size = unsignedByte(pageOffset + 27 + index);
                for (int byteIndex = 0; byteIndex < size; byteIndex++) {
                    packet.write(source.get(cursor + byteIndex));
                }
                cursor += size;
                if (size < 255) {
                    acceptPacket(packet.toByteArray());
                    packet.reset();
                }
            }
            require(!eos || packet.size() == 0, "EOS page ends in a partial packet");
            if (timeline != null) {
                timeline.endPage(granule, eos);
            }
            sawEos = eos;
            source.position(pageOffset + pageSize);
        }

        private void rememberSupportedEncoding(int packetOffset) {
            if (matches(source, packetOffset, OPUS_HEAD)) {
                encoding = Encoding.OGG_OPUS;
            } else if (matches(source, packetOffset, VORBIS_IDENTIFICATION)) {
                encoding = Encoding.OGG_VORBIS;
            }
        }

        private void acceptPacket(byte[] bytes) throws InvalidMedia, UnknownMedia {
            if (timeline == null) {
                if (matches(bytes, OPUS_HEAD)) {
                    encoding = Encoding.OGG_OPUS;
                    timeline = new OpusTimeline();
                } else if (matches(bytes, VORBIS_IDENTIFICATION)) {
                    encoding = Encoding.OGG_VORBIS;
                    timeline = new VorbisTimeline();
                } else {
                    throw new UnknownMedia("Ogg stream uses an unsupported codec");
                }
            }
            timeline.acceptPacket(bytes);
        }

        private int unsignedByte(int index) {
            return Byte.toUnsignedInt(source.get(index));
        }

        private long unsignedInt(int index) {
            return Integer.toUnsignedLong(unsignedByte(index) | unsignedByte(index + 1) << 8
                | unsignedByte(index + 2) << 16 | unsignedByte(index + 3) << 24);
        }

        private long littleLong(int index) {
            long result = 0;
            for (int shift = 0; shift < Long.SIZE; shift += 8) {
                result |= (long)unsignedByte(index + shift / 8) << shift;
            }
            return result;
        }

        private long checksum(int offset, int size) {
            int crc = 0;
            for (int index = 0; index < size; index++) {
                int value = index >= 22 && index < 26 ? 0 : unsignedByte(offset + index);
                crc = crc << 8 ^ OGG_CRC_TABLE[(crc >>> 24 ^ value) & 0xff];
            }
            return Integer.toUnsignedLong(crc);
        }
    }

    private interface Timeline {
        void acceptPacket(byte[] packet) throws InvalidMedia;

        void endPage(long granule, boolean eos) throws InvalidMedia;

        MediaInfo finish() throws InvalidMedia;
    }

    private static final class VorbisTimeline implements Timeline {
        private int packetIndex;
        private int channels;
        private long sampleRate;
        private long finalGranule = -1;

        @Override
        public void acceptPacket(byte[] packet) throws InvalidMedia {
            if (packetIndex == 0) {
                require(packet.length >= 30 && matches(packet, VORBIS_IDENTIFICATION),
                    "invalid Vorbis identification header");
                require(littleUnsignedInt(packet, 7) == 0, "unsupported Vorbis version");
                channels = Byte.toUnsignedInt(packet[11]);
                require(channels == 1 || channels == 2, "unsupported Vorbis channel count");
                sampleRate = littleUnsignedInt(packet, 12);
                require(sampleRate != 0, "invalid Vorbis sample rate");
                require((packet[29] & 1) != 0, "invalid Vorbis identification framing bit");
            } else if (packetIndex == 1) {
                require(matches(packet, VORBIS_COMMENT), "invalid Vorbis comment header");
            } else if (packetIndex == 2) {
                require(matches(packet, VORBIS_SETUP), "invalid Vorbis setup header");
            }
            packetIndex++;
        }

        @Override
        public void endPage(long granule, boolean eos) throws InvalidMedia {
            if (eos) {
                require(granule >= 0, "invalid Vorbis final granule");
                finalGranule = granule;
            }
        }

        @Override
        public MediaInfo finish() throws InvalidMedia {
            require(packetIndex >= 3, "incomplete Vorbis headers");
            require(finalGranule >= 0, "Vorbis stream has no final granule");
            return new MediaInfo(Encoding.OGG_VORBIS, channels, sampleRate, finalGranule, 0, 0);
        }
    }

    private static final class OpusTimeline implements Timeline {
        private int packetIndex;
        private int channels;
        private int preSkip;
        private int outputGain;
        private long decodedFrames;
        private long pageFrames;
        private long origin = Long.MIN_VALUE;
        private long finalGranule = -1;

        @Override
        public void acceptPacket(byte[] packet) throws InvalidMedia {
            if (packetIndex == 0) {
                require(packet.length == 19 && matches(packet, OPUS_HEAD), "invalid Opus identification header");
                int version = Byte.toUnsignedInt(packet[8]);
                require(version <= 15, "unsupported Opus version");
                channels = Byte.toUnsignedInt(packet[9]);
                require(channels == 1 || channels == 2, "unsupported Opus channel count");
                preSkip = littleUnsignedShort(packet, 10);
                outputGain = (short)littleUnsignedShort(packet, 16);
                require(Byte.toUnsignedInt(packet[18]) == 0, "unsupported Opus channel mapping family");
            } else if (packetIndex == 1) {
                require(matches(packet, OPUS_TAGS), "invalid Opus tags header");
            } else {
                long frames = opusPacketFrames(packet);
                decodedFrames = Math.addExact(decodedFrames, frames);
                pageFrames = Math.addExact(pageFrames, frames);
            }
            packetIndex++;
        }

        @Override
        public void endPage(long granule, boolean eos) throws InvalidMedia {
            if (packetIndex <= 2 || granule == -1) {
                pageFrames = 0;
                return;
            }
            require(granule >= 0, "invalid Opus granule");
            if (origin == Long.MIN_VALUE) {
                if (eos && granule < decodedFrames) {
                    origin = 0;
                } else {
                    require(granule >= decodedFrames, "Opus granule precedes decoded packets");
                    origin = granule - decodedFrames;
                }
            }
            long decodedGranule = Math.addExact(origin, decodedFrames);
            if (eos) {
                require(granule <= decodedGranule, "Opus final granule exceeds decoded packets");
                require(decodedGranule - granule <= pageFrames, "Opus end trimming exceeds the final page");
                finalGranule = granule;
            } else {
                require(granule == decodedGranule, "inconsistent Opus page granule");
            }
            pageFrames = 0;
        }

        @Override
        public MediaInfo finish() throws InvalidMedia {
            require(packetIndex >= 2, "incomplete Opus headers");
            require(origin != Long.MIN_VALUE && finalGranule >= 0, "Opus stream has no playable timeline");
            long start = Math.addExact(origin, preSkip);
            require(finalGranule >= start, "Opus final granule precedes pre-skip");
            return new MediaInfo(Encoding.OGG_OPUS, channels, 48_000, finalGranule - start, preSkip, outputGain);
        }
    }

    private static long opusPacketFrames(byte[] packet) throws InvalidMedia {
        require(packet.length > 0, "empty Opus packet");
        int toc = Byte.toUnsignedInt(packet[0]);
        int config = toc >>> 3;
        int samplesPerFrame;
        if (config < 12) {
            samplesPerFrame = new int[] {480, 960, 1920, 2880}[config & 3];
        } else if (config < 16) {
            samplesPerFrame = new int[] {480, 960}[config & 1];
        } else {
            samplesPerFrame = new int[] {120, 240, 480, 960}[config & 3];
        }
        int frameCount = switch (toc & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            case 3 -> {
                require(packet.length >= 2, "truncated Opus frame-count byte");
                yield Byte.toUnsignedInt(packet[1]) & 0x3f;
            }
            default -> throw new AssertionError();
        };
        require(frameCount > 0 && (long)samplesPerFrame * frameCount <= 5_760, "invalid Opus packet duration");
        return (long)samplesPerFrame * frameCount;
    }

    private static int littleUnsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static long littleUnsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8
            | Byte.toUnsignedInt(bytes[offset + 2]) << 16 | Byte.toUnsignedInt(bytes[offset + 3]) << 24);
    }

    private static boolean matches(ByteBuffer bytes, int offset, byte[] expected) {
        if (offset < bytes.position() || offset > bytes.limit() - expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes.get(offset + index) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(byte[] bytes, byte[] expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static int[] createCrcTable() {
        int[] table = new int[256];
        for (int index = 0; index < table.length; index++) {
            int value = index << 24;
            for (int bit = 0; bit < 8; bit++) {
                value = value << 1 ^ ((value & 0x8000_0000) != 0 ? 0x04c1_1db7 : 0);
            }
            table[index] = value;
        }
        return table;
    }

    private static void require(boolean condition, String message) throws InvalidMedia {
        if (!condition) {
            throw new InvalidMedia(message);
        }
    }

    private static final class InvalidMedia extends Exception {
        private InvalidMedia(String message) {
            super(message);
        }
    }

    private static final class UnknownMedia extends Exception {
        private UnknownMedia(String message) {
            super(message);
        }
    }
}
