package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.schema.model.ModelOriginVersion;
import com.elfmcys.ysm.model.domain.Hash256;
import java.util.List;
import java.util.Objects;

/**
 * Owning result of one raw capture; identity, diagnostics, and origin describe that same frozen input.
 *
 * <p>Requires: direct construction must preserve that association and transfer exclusive close
 * responsibility for data. The constructor checks presence, not that the supplied identity matches data.
 *
 * <p>Ownership: callers close the record after all compilation and borrowed-buffer use ends.
 *
 * <p>Seam: carries the [capture-to-conversion guarantee](docs/architecture/asset-pipeline/overview.md#冻结输入与单向转换) established by {@link ModelParser#capture}.
 */
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
