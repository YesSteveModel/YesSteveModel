package com.elfmcys.ysm.capability;

/**
 * Connection-owned accounting in the client owner window; selection owners retain and release the accepted events.
 * Closing seals admission without releasing events still owned by selections.
 */
public final class RemoteSyncBudget implements AutoCloseable {
    public static final int MAX_PENDING_SYNCS = 4096;

    private int pendingSyncs;
    private long droppedSyncs;
    private boolean active = true;

    public boolean tryReserve() {
        if (!active)
            return false;
        if (pendingSyncs == MAX_PENDING_SYNCS) {
            recordDroppedSync();
            return false;
        }
        pendingSyncs++;
        return true;
    }

    public void release() {
        if (pendingSyncs == 0)
            throw new IllegalStateException("Remote sync capacity was already released");
        pendingSyncs--;
    }

    public void recordDroppedSync() {
        droppedSyncs++;
    }

    public int pendingSyncs() {
        return pendingSyncs;
    }

    public long droppedSyncs() {
        return droppedSyncs;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public void close() {
        active = false;
    }
}
