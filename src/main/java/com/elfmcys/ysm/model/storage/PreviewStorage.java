package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.catalog.content.ModelContent;
import java.util.Objects;

/** Selects a fixed store from provenance captured by the admitted content. */
@FunctionalInterface
public interface PreviewStorage {
    PreviewStore forContent(ModelContent content);

    static PreviewStorage routed() {
        return content -> Objects.requireNonNull(content.storageBinding(), "Storage provenance").region().previews();
    }
}
