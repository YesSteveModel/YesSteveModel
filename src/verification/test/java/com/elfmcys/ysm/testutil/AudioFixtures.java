package com.elfmcys.ysm.testutil;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

/** Shared encoded contract inputs, with an override for external fixture runs. */
public final class AudioFixtures {
    private AudioFixtures() {}

    public static Path root() {
        var configured = System.getenv("YSM_AUDIO_FIXTURE_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        try {
            return Path
                .of(Objects.requireNonNull(AudioFixtures.class.getResource("/audio-fixtures/manifest.json")).toURI())
                .getParent();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid audio fixture resource path", exception);
        }
    }
}
