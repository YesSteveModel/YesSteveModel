package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.natives.image.ImageSource;
import java.util.Objects;

/** A validated image or the current server's successful empty preview result. */
public sealed interface PreviewResult permits PreviewResult.Data, PreviewResult.Absent {
    record Data(ImageSource image) implements PreviewResult {
        public Data {
            Objects.requireNonNull(image, "image");
        }
    }

    enum Absent implements PreviewResult {
        INSTANCE
    }
}
