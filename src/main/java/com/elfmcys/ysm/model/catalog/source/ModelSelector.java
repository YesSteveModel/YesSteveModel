package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.ModelPath;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves exact identities and published positions before any command eligibility check.
 *
 * <p>
 * Seam: establishes common target resolution for [command
 * selection](docs/architecture/model-management/sessions/overview.md#新使用与选择资格).
 */
public final class ModelSelector {
    private ModelSelector() {}

    public static Result resolve(String input, Map<CatalogLocation, ModelId> positions) {
        return resolve(input, positions, false);
    }

    /** Allows explicit historical identities for preference clearing; paths still require current positions. */
    public static Result resolveHistorical(String input, Map<CatalogLocation, ModelId> positions) {
        return resolve(input, positions, true);
    }

    private static Result resolve(String input, Map<CatalogLocation, ModelId> positions, boolean historical) {
        if ("default".equals(input))
            return new Result(Status.FOUND, PublishedModelId.INTRINSIC_DEFAULT);
        if (input != null && input.startsWith("id:")) {
            final ModelId id;
            try {
                id = ModelId.parse(input.substring(3));
            } catch (IllegalArgumentException invalid) {
                return new Result(Status.INVALID, null);
            }
            return historical || positions.containsValue(id) ? new Result(Status.FOUND, PublishedModelId.model(id))
                : new Result(Status.NOT_FOUND, null);
        }
        final CatalogLocation absolute;
        final ModelPath relative;
        try {
            absolute = input.indexOf(':') >= 0 || input.startsWith("$") ? CatalogLocation.parse(input) : null;
            relative = absolute == null ? new ModelPath(input) : null;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return new Result(Status.INVALID, null);
        }
        ModelId found = null;
        for (var entry : positions.entrySet()) {
            if (!(absolute == null ? entry.getKey().entry().equals(relative.value()) : entry.getKey().equals(absolute)))
                continue;
            if (found != null)
                return new Result(Status.AMBIGUOUS, null);
            found = entry.getValue();
        }
        return new Result(found == null ? Status.NOT_FOUND : Status.FOUND,
            found == null ? null : PublishedModelId.model(found));
    }

    public static java.util.List<String> suggestions(Map<CatalogLocation, ModelId> positions) {
        return java.util.stream.Stream
            .concat(java.util.stream.Stream.of("default"),
                positions.entrySet().stream()
                    .flatMap(entry -> java.util.stream.Stream.of(entry.getKey().value(), "id:" + entry.getValue())))
            .distinct().sorted().toList();
    }

    public static final class Failure extends java.io.IOException {
        private final Status status;

        public Failure(Status status) {
            super(new Result(status, null).message());
            if (status == Status.FOUND)
                throw new IllegalArgumentException("A found address is not a failure");
            this.status = status;
        }

        public Status status() {
            return status;
        }
    }

    public enum Status {
        INVALID, NOT_FOUND, AMBIGUOUS, FOUND
    }

    public record Result(Status status, PublishedModelId modelId) {
        public Optional<PublishedModelId> found() {
            return Optional.ofNullable(modelId);
        }

        public String message() {
            return switch (status) {
                case INVALID -> "Invalid model selector";
                case NOT_FOUND -> "Model selector not found";
                case AMBIGUOUS -> "Ambiguous model address; use a complete catalog address";
                case FOUND -> "";
            };
        }
    }
}
