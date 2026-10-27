package com.elfmcys.ysm.model.catalog.source;

import java.io.IOException;

public sealed class CatalogBuildException
    extends IOException permits CatalogInfrastructureException, ModelSourceException, ModelPackException {
    CatalogBuildException(String message, Throwable cause) {
        super(message, cause);
    }

    CatalogBuildException(String message) {
        super(message);
    }
}
