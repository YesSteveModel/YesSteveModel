package com.elfmcys.ysm.api.molang;

/**
 * Captures exact effect targets and immutable values before submission.
 *
 * <p>Ownership: an effect must not retain the borrowed context, event, or mutable call inputs.
 * The batch retains this effect until commit or discard but supplies no close protocol for captured resources.
 *
 * <p>Seam: implements the prepared command portion of [invocations and effects](docs/architecture/animation/molang-runtime.md#invocation-与效果).
 */
@FunctionalInterface
public interface PreparedEffect extends EffectRequest {
    /**
     * Checks fallible effect eligibility without externally visible mutation.
     *
     * <p>Execution: validation can run repeatedly before dispatch. Failure rejects the whole uncommitted batch.
     * The effect must retain the exact prepared targets rather than resolve a replacement target at commit.
     */
    default void validate() {}

    /**
     * Dispatches the already prepared effect in source order after the round closes.
     *
     * <p>Failure: an external dispatch failure propagates and can leave earlier effects committed.
     * The caller supplies no cross-effect rollback or automatic retry.
     */
    void commit();
}
