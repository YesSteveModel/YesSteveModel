package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.schema.model.ModelOriginVersion;
import com.elfmcys.ysm.model.domain.Hash256;
import java.util.List;
import java.util.Objects;

public record CapturedModel(Hash256 modelId, CaptureData data, List<RawModelDiagnostic> diagnostics,
    String originVer) implements AutoCloseable {
    public CapturedModel {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(data, "data");
        diagnostics = List.copyOf(diagnostics);
        Objects.requireNonNull(originVer, "originVer");
    }

    public CapturedModel(Hash256 modelId, CaptureData data, List<RawModelDiagnostic> diagnostics) {
        this(modelId, data, diagnostics, ModelOriginVersion.CURRENT_RELEASE);
    }

    public CapturedModel(Hash256 modelId, CaptureData data) {
        this(modelId, data, List.of());
    }

    @Override
    public void close() {
        data.close();
    }
}
