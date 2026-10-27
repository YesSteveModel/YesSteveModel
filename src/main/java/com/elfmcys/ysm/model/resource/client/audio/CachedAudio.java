package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.natives.sound.OpusDecoder;

import javax.sound.sampled.AudioFormat;
import java.util.Objects;

sealed interface CachedAudio {
    PcmAudio pcm();

    long retainedBytes();

    record Complete(PcmAudio pcm) implements CachedAudio {
        public Complete {
            validatePcm(pcm);
        }

        @Override
        public long retainedBytes() {
            return pcm.size();
        }
    }

    record Prefix(PcmAudio pcm, OpusDecoder.Checkpoint checkpoint) implements CachedAudio {
        public Prefix {
            validatePcm(pcm);
            Objects.requireNonNull(checkpoint, "checkpoint");
            if (pcm.size() / 2L != checkpoint.emittedFrames()) {
                throw new IllegalArgumentException("Cached PCM does not match checkpoint output frames");
            }
        }

        @Override
        public long retainedBytes() {
            return Math.addExact(pcm.size(), checkpoint.retainedBytes());
        }
    }

    private static void validatePcm(PcmAudio pcm) {
        Objects.requireNonNull(pcm, "pcm");
        var format = pcm.format();
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getSampleRate() != 48_000
            || format.getSampleSizeInBits() != 16 || format.getChannels() != 1 || format.getFrameSize() != 2
            || format.getFrameRate() != 48_000 || format.isBigEndian() || pcm.size() % 2 != 0) {
            throw new IllegalArgumentException("Cached PCM must be frame-aligned 48 kHz signed 16-bit little-endian mono");
        }
    }
}
