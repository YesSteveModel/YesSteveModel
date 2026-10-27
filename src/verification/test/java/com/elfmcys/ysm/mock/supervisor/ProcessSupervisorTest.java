package com.elfmcys.ysm.mock.supervisor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSupervisorTest {
    @TempDir
    Path temporary;

    @Test
    void readyTimeoutDoesNotCreateOrTouchTheMarker() throws Exception {
        var marker = temporary.resolve("missing.ready");
        assertThrows(IOException.class, () -> ProcessSupervisor.awaitFile(marker, null, Duration.ofMillis(25)));
        assertFalse(Files.exists(marker));
    }

    @Test
    void partialStartFailureReportsTheExactExit() throws Exception {
        var process = new ProcessBuilder(DummyProcess.javaCommand("exit", "23")).start();
        var failure = assertThrows(IOException.class,
            () -> ProcessSupervisor.awaitFile(temporary.resolve("never.ready"), process, Duration.ofSeconds(5)));
        assertTrue(failure.getMessage().contains("exited with 23"));
    }

    @Test
    void normalExitRequiresNoForcedCleanup() throws Exception {
        var marker = temporary.resolve("ready.json");
        var process = new ProcessBuilder(DummyProcess.javaCommand("ready", marker.toString(), "1")).start();
        ProcessSupervisor.awaitFile(marker, process, Duration.ofSeconds(5));
        assertEquals(0, process.waitFor());
        var cleanup = ProcessSupervisor.terminateExact(process, Duration.ofSeconds(1));
        assertTrue(cleanup.clean());
        assertFalse(cleanup.forced());
    }

    @Test
    void cleanupTargetsOnlyTheRecordedProcessTree() throws Exception {
        var childPid = temporary.resolve("child.pid");
        var childReady = temporary.resolve("child.ready");
        var unrelatedReady = temporary.resolve("unrelated.ready");
        var parent =
            new ProcessBuilder(DummyProcess.javaCommand("tree", childPid.toString(), childReady.toString())).start();
        var unrelated =
            new ProcessBuilder(DummyProcess.javaCommand("ready", unrelatedReady.toString(), "600000")).start();
        try {
            ProcessSupervisor.awaitFile(childPid, parent, Duration.ofSeconds(5));
            ProcessSupervisor.awaitFile(unrelatedReady, unrelated, Duration.ofSeconds(5));
            var child = Long.parseLong(Files.readString(childPid));
            var cleanup = ProcessSupervisor.terminateExact(parent, Duration.ofSeconds(1));
            assertTrue(cleanup.clean());
            assertTrue(cleanup.targeted().contains(parent.pid()));
            assertTrue(cleanup.targeted().contains(child));
            assertTrue(unrelated.isAlive());
        } finally {
            ProcessSupervisor.terminateExact(unrelated, Duration.ofSeconds(1));
        }
    }
}
