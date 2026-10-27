package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.Hash256;

import java.io.IOException;

/** Indicates that packaged raw builtin content no longer matches its generated index. */
public final class ModelHashMismatchException extends IOException {
    public ModelHashMismatchException(CatalogLocation location, Hash256 expected, Hash256 actual) {
        super("Builtin model hash mismatch at " + location.path() + ": expected=" + expected + ", actual=" + actual);
    }
}
