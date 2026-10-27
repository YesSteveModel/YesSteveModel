package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.model.domain.Hash256;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Result of compiling one raw source before the converted object store validates it. */
public record RawCompileResult(Hash256 modelHash, Path stagedContainer, List<RawModelDiagnostic> diagnostics) {
    public RawCompileResult {
        Objects.requireNonNull(modelHash, "modelHash");
        stagedContainer = Objects.requireNonNull(stagedContainer, "stagedContainer").toAbsolutePath().normalize();
        diagnostics = List.copyOf(diagnostics);
    }

    public RawCompileResult(Hash256 modelHash, Path stagedContainer) {
        this(modelHash, stagedContainer, List.of());
    }
}
