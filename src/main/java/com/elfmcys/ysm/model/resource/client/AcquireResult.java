package com.elfmcys.ysm.model.resource.client;

import java.util.Objects;

/**
 * An observation of one lease's acquisition state.
 *
 * <p><b>Ownership:</b> Ready contains a non-null borrowed target, not a new ownership transfer.
 * The associated open {@link ResourceLease} retains the target. Failed contains a non-null failure;
 * callers must not turn Pending, cancellation or local capacity pressure into a content-corruption claim.
 *
 * <p><b>Seam:</b> [acquisition states](docs/architecture/model-management/client-resources/overview.md#seam-contract).
 */
public sealed interface AcquireResult {
    record Ready(ModelRenderTarget target) implements AcquireResult {
        public Ready {
            Objects.requireNonNull(target, "target");
        }
    }

    record Pending() implements AcquireResult {
    }

    record Failed(ResourceFailure failure) implements AcquireResult {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
