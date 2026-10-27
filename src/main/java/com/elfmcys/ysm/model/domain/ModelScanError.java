package com.elfmcys.ysm.model.domain;

import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Objects;

public record ModelScanError(Instant occurredAt, CatalogRootKind rootKind, String source, Category category,
    String code, String message, String detail) {
    public ModelScanError(Instant occurredAt, CatalogRootKind rootKind, String source, String message, String detail) {
        this(occurredAt, rootKind, source, Category.MODEL_SOURCE, "UNCLASSIFIED", message, detail);
    }

    public ModelScanError {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(rootKind, "rootKind");
        source = Objects.requireNonNullElse(source, "");
        Objects.requireNonNull(category, "category");
        code = Objects.requireNonNullElse(code, "UNCLASSIFIED");
        message = Objects.requireNonNullElse(message, "Unknown model scan error");
        detail = Objects.requireNonNullElse(detail, "");
    }

    public static ModelScanError from(CatalogRootKind rootKind, String source, Throwable error) {
        com.elfmcys.ysm.YesSteveModel.LOGGER.error("Model source failed location={}", source, error);
        var text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return new ModelScanError(Instant.now(), rootKind, source, PublicFailure.summary(error), text.toString());
    }

    public static ModelScanError infrastructure(CatalogRootKind rootKind, String source, String code, Throwable error) {
        com.elfmcys.ysm.YesSteveModel.LOGGER.error("Model infrastructure failed location={} code={}", source, code,
            error);
        var text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return new ModelScanError(Instant.now(), rootKind, source, Category.INFRASTRUCTURE, code,
            "Source is inaccessible", text.toString());
    }

    public static ModelScanError conflict(CatalogRootKind rootKind, String source) {
        return new ModelScanError(Instant.now(), rootKind, source, Category.CONFLICT, "DUPLICATE_IDENTITY",
            "Duplicate model identity", "");
    }

    public enum Category {
        MODEL_SOURCE, MODEL_PACK, CONFLICT, CACHE_HINT, INFRASTRUCTURE
    }
}
