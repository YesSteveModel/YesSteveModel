package com.elfmcys.ysm.capability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteSyncBudgetTest {
    @Test
    void capacityBelongsToTheConnectionAndCloseDoesNotReleaseSelectionEvents() {
        var budget = new RemoteSyncBudget();
        for (int index = 0; index < RemoteSyncBudget.MAX_PENDING_SYNCS; index++)
            assertTrue(budget.tryReserve());
        assertFalse(budget.tryReserve());
        assertEquals(1, budget.droppedSyncs());
        budget.recordDroppedSync();
        assertEquals(2, budget.droppedSyncs());
        budget.release();
        assertTrue(budget.tryReserve());
        budget.close();
        assertFalse(budget.isActive());
        assertFalse(budget.tryReserve());
        assertEquals(2, budget.droppedSyncs());
        assertEquals(RemoteSyncBudget.MAX_PENDING_SYNCS, budget.pendingSyncs());
        for (int index = 0; index < RemoteSyncBudget.MAX_PENDING_SYNCS; index++)
            budget.release();
        assertEquals(0, budget.pendingSyncs());
        assertThrows(IllegalStateException.class, budget::release);
    }
}
