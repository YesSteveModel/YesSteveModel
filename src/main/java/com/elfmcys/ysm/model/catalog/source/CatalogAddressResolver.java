package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import java.util.Map;
import java.util.Optional;

/** Resolves only published positions, before any access-policy filtering. */
public final class CatalogAddressResolver {
    private CatalogAddressResolver() {}

    public static Result resolve(String input, Map<CatalogLocation, Hash256> positions) {
        final CatalogLocation absolute;
        final ModelPath relative;
        try {
            absolute = input.equals(CatalogLocation.DEFAULT.value()) || input.indexOf(':') >= 0 || input.startsWith("$")
                ? CatalogLocation.parse(input) : null;
            relative = absolute == null ? new ModelPath(input) : null;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return new Result(Status.INVALID, null);
        }
        Hash256 found = null;
        for (var entry : positions.entrySet()) {
            if (!(absolute == null ? entry.getKey().entry().equals(relative.value()) : entry.getKey().equals(absolute)))
                continue;
            if (found != null)
                return new Result(Status.AMBIGUOUS, null);
            found = entry.getValue();
        }
        return new Result(found == null ? Status.NOT_FOUND : Status.FOUND, found);
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

    public record Result(Status status, Hash256 modelId) {
        public Optional<Hash256> found() {
            return Optional.ofNullable(modelId);
        }

        public String message() {
            return switch (status) {
                case INVALID -> "Invalid model address";
                case NOT_FOUND -> "Model address not found";
                case AMBIGUOUS -> "Ambiguous model address; use a complete catalog address";
                case FOUND -> "";
            };
        }
    }
}
