package com.elfmcys.ysm.model.catalog.content;

import com.elfmcys.ysm.model.domain.ModelId;

/** Catalog-held strong reference to one current content object. */
public interface ContentBinding {
    ModelId modelId();

    ModelContent content();
}
