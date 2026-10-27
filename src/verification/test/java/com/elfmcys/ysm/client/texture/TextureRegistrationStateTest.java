package com.elfmcys.ysm.client.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureRegistrationStateTest {
    @Test
    void replacementBeforeOldCleanupKeepsTheRegisteredIdAndIgnoresCleanup() {
        var id = new Object();
        var state = new TextureRegistrationState<>(id);
        var old = state.activate(1);
        assertTrue(state.markRegistered(old.token()));

        var replacement = state.activate(1);

        assertSame(id, state.id());
        assertTrue(replacement.registered());
        assertEquals(TextureRegistrationState.ReleaseResult.IGNORED, state.release(old.token()));
        assertTrue(state.tickRemoval().isEmpty());
        assertTrue(state.isActive(replacement.token()));
    }

    @Test
    void currentCleanupReleasesOnlyAfterTheConfiguredDelay() {
        var state = new TextureRegistrationState<>(new Object());
        var current = state.activate(1);
        assertTrue(state.markRegistered(current.token()));

        assertEquals(TextureRegistrationState.ReleaseResult.DELAYED, state.release(current.token()));
        assertTrue(state.tickRemoval().isEmpty());
        var removal = state.tickRemoval().orElseThrow();
        assertTrue(state.shouldRelease(removal));
        assertTrue(state.markReleased(removal));
        assertFalse(state.shouldRelease(removal));
    }

    @Test
    void staleRemovalCannotAffectReplacement() {
        var state = new TextureRegistrationState<>(new Object());
        var registered = state.activate(0);
        assertTrue(state.markRegistered(registered.token()));

        assertEquals(TextureRegistrationState.ReleaseResult.DELAYED, state.release(registered.token()));
        var pendingRemoval = state.tickRemoval().orElseThrow();
        var replacement = state.activate(0);

        assertFalse(state.shouldRelease(pendingRemoval));
        assertTrue(replacement.registered());
        assertTrue(state.isActive(replacement.token()));
    }
}
