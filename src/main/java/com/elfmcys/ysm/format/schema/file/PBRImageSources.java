package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.natives.image.ImageSource;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record PBRImageSources(ImageSource uv, @Nullable ImageSource normal, @Nullable ImageSource specular) {
    public PBRImageSources {
        Objects.requireNonNull(uv, "uv");
    }
}
