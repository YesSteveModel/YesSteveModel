package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;

import java.util.List;

/**
 * Prepares host dispatch after every effect has passed domain validation.
 *
 * <p>Execution: preparation runs synchronously inside the source round and must not publish effects.
 * The transaction closes that round before calling prepared commits in source order.
 * Commit can enter a later owner round only after that closure.
 *
 * <p>Ownership: the sink borrows immutable command lists and provenance. A prepared commit can retain
 * those values for the immediate dispatch phase, but must not retain the source context or event.
 *
 * <p>Seam: implements host dispatch in [invocations and effects](docs/architecture/animation/molang-runtime.md#invocation-与效果).
 */
@FunctionalInterface
public interface EffectSink {
    /**
     * Prepares one contiguous provenance group without external mutation.
     *
     * <p>Failure: host preparation failure rejects the uncommitted batch. The sink trusts domain validation
     * already established by the transaction and checks only additional host dispatch conditions.
     *
     * @param commands the immutable source-ordered command group
     * @param provenance the source identity shared by this group
     * @return a non-null commit for immediate ordered dispatch after all groups prepare successfully
     */
    PreparedCommit prepare(List<PreparedEffect> commands, InvocationProvenance provenance);

    static EffectSink direct() {
        return (effects, provenance) -> () -> effects.forEach(PreparedEffect::commit);
    }

    /**
     * Dispatches a prepared group after the source round releases its borrowed execution state.
     *
     * <p>Failure: dispatch can partially commit external effects. The transaction neither rolls back nor retries this group.
     */
    @FunctionalInterface
    interface PreparedCommit {
        void commit();
    }
}
