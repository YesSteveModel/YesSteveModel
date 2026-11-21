package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.util.Closeable;

/**
 * One consumer's independent interest in a pending or Ready render target.
 *
 * <p>
 * <b>Execution:</b> The consumer serializes this handle's queries with close. Polling after close observes Ended.
 * Implementations can defer release to their resource owner.
 *
 * <p>
 * <b>Ownership:</b> The receiver closes this lease exactly once in its ownership flow; close itself is idempotent. This
 * open lease retains the borrowed Ready target; the receiver must not close the target directly. Closing this lease
 * releases only its own interest, not another consumer's lease or active playback. Logical release does not promise
 * immediate host destruction or backing reclamation.
 *
 * <p>
 * <b>Seam:</b> [lease ownership and
 * release](docs/architecture/model-management/client-resources/overview.md#引用撤销与物理关闭).
 */
public interface ResourceLease extends Closeable {
    /** Returns an independent, already absent use without starting work. */
    static ResourceLease missing(ResourceRequest request) {
        return new ResourceLease() {
            private boolean closed;

            @Override
            public ModelResourcePurpose purpose() {
                return request.purpose();
            }

            @Override
            public AcquireResult poll() {
                return closed ? new AcquireResult.Ended() : new AcquireResult.Missing();
            }

            @Override
            public boolean isCurrent(ResourceRequest candidate) {
                return !closed && request.equals(candidate);
            }

            @Override
            public void cancelPending() {
                close();
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    ModelResourcePurpose purpose();

    /**
     * Observes this interest without waiting for preparation or host publication.
     *
     * @return a non-null state; Ready borrows the target under this lease, Failed describes the attempt, and Pending
     *         does not establish host installation
     */
    AcquireResult poll();

    /**
     * Tests whether this open lease still qualifies for a new handoff of the requested use. This changing qualification
     * does not invalidate an already admitted use or revalidate immutable content.
     *
     * @param request the receiving use, including binding, purpose and bake profile
     * @return whether this lease currently qualifies for that use
     */
    boolean isCurrent(ResourceRequest request);

    /**
     * Relinquishes this handle, including its Ready reference if completion already won the race. A pending Flight can
     * continue for other consumers. Further polling observes Ended.
     */
    void cancelPending();
}
