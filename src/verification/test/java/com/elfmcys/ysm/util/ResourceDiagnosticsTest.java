package com.elfmcys.ysm.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceDiagnosticsTest {
    @Test
    void distinguishesEqualOwnersAndUpdatesOneIdentity() {
        var registry = new ResourceDiagnostics.Registry();
        var first = new String("equal");
        var second = new String("equal");
        registry.track(ResourceDiagnostics.Kind.GEO, first, "first");
        registry.track(ResourceDiagnostics.Kind.GEO, second, "second");
        registry.track(ResourceDiagnostics.Kind.GEO, first, "updated");
        assertEquals(2, registry.snapshot(ResourceDiagnostics.Kind.GEO).size());
        assertEquals("updated", registry.untrack(ResourceDiagnostics.Kind.GEO, first));
        assertEquals(1, registry.snapshot(ResourceDiagnostics.Kind.GEO).size());
        assertEquals("second", registry.untrack(ResourceDiagnostics.Kind.GEO, second));
        assertTrue(registry.snapshot(ResourceDiagnostics.Kind.GEO).isEmpty());
        Reference.reachabilityFence(first);
        Reference.reachabilityFence(second);
    }

    @Test
    void doesNotKeepResourcesAlive() throws InterruptedException {
        var registry = new ResourceDiagnostics.Registry();
        var reference = trackTemporaryOwner(registry);
        for (var attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(reference.get(), "Resource diagnostics must not retain an owner");
        assertTrue(registry.snapshot(ResourceDiagnostics.Kind.RENDERTARGET).isEmpty());
    }

    private static WeakReference<Object> trackTemporaryOwner(ResourceDiagnostics.Registry registry) {
        var owner = new Object();
        registry.track(ResourceDiagnostics.Kind.RENDERTARGET, owner, "temporary");
        return new WeakReference<>(owner);
    }

    @Test
    void gatesAutomaticLogsButAllowsExplicitDumpsAndReportsFailures() {
        var logger = (Logger)LogManager.getLogger("ysm");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        var capture = new Capture();
        capture.start();
        logger.addAppender(capture);
        var previous = ResourceDiagnostics.isEnabled();
        try {
            ResourceDiagnostics.setEnabled(false);
            ResourceDiagnostics.log("disabled event");
            try (var operation = ResourceDiagnostics.operation("disabled operation", "test")) {
                operation.succeeded();
            }
            assertTrue(capture.messages.isEmpty());

            ResourceDiagnostics.dump(ResourceDiagnostics.Kind.GEO);
            assertFalse(ResourceDiagnostics.isEnabled());
            assertTrue(capture.messages.stream().anyMatch(message -> message.contains("dump GEO:")));

            ResourceDiagnostics.setEnabled(true);
            try (var operation = ResourceDiagnostics.operation("completed operation", "test")) {
                operation.succeeded();
            }
            assertThrows(IllegalStateException.class, () -> {
                try (var ignored = ResourceDiagnostics.operation("rejected operation", "test")) {
                    throw new IllegalStateException("expected");
                }
            });
            assertTrue(capture.messages.stream().anyMatch(message -> message.contains("completed operation success:")));
            assertTrue(capture.messages.stream().anyMatch(message -> message.contains("rejected operation failed:")));

            var pending = ResourceDiagnostics.operation("pending operation", "test");
            capture.messages.clear();
            ResourceDiagnostics.setEnabled(false);
            pending.close();
            assertTrue(capture.messages.isEmpty());
        } finally {
            ResourceDiagnostics.setEnabled(previous);
            logger.removeAppender(capture);
            logger.setLevel(previousLevel);
            capture.stop();
        }
    }

    private static final class Capture extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();

        private Capture() {
            super("resource-diagnostics-test", null, null, true);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }
}
