package com.elfmcys.ysm.model.domain;

import com.elfmcys.ysm.format.AssetLoadException;
import java.nio.file.FileSystemException;

/** Failure categories suitable for a publication, without host exception text. */
public final class PublicFailure {
    private PublicFailure() {}

    public static String summary(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.elfmcys.ysm.model.catalog.source.CatalogAddressResolver.Failure address)
                return new com.elfmcys.ysm.model.catalog.source.CatalogAddressResolver.Result(address.status(), null)
                    .message();
            if (cause instanceof AssetLoadException asset)
                return asset.reason() == AssetLoadException.Reason.ACCESS ? "Source is inaccessible"
                    : "Content validation failed";
            if (cause instanceof FileSystemException || cause instanceof SecurityException
                || cause instanceof com.elfmcys.ysm.model.catalog.source.CatalogInfrastructureException)
                return "Source is inaccessible";
            if (cause.getCause() == cause)
                break;
        }
        return "Content validation failed";
    }
}
