package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import dev.semaweave.compilation.EngineMetrics;
import dev.semaweave.compilation.LoaderCollectionProbe;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ExpressionEngineMetricsTest {
    @Test
    void loaderDiagnosticsAreGatedAndDoNotRetainTheLoader() throws Exception {
        var logger = (Logger)LogManager.getLogger("ysm");
        var previousLevel = logger.getLevel();
        var previousEnabled = ResourceDiagnostics.isEnabled();
        var capture = new Capture();
        capture.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(capture);
        try {
            var metrics = new ExpressionEngineMetrics();
            ResourceDiagnostics.setEnabled(false);
            var ignoredProbe = registerTemporaryLoader(metrics);
            metrics.changeActive(EngineMetrics.Owner.RESOURCES, 1);
            awaitCollection(ignoredProbe);
            ExpressionEngineMetrics.pollCollectedLoaders();
            assertTrue(capture.messages.isEmpty());

            ResourceDiagnostics.setEnabled(true);
            var trackedProbe = registerTemporaryLoader(metrics);
            assertTrue(capture.messages.stream().anyMatch(message -> message.contains("Molang loader track:")));
            metrics.changeActive(EngineMetrics.Owner.RESOURCES, -1);
            assertTrue(capture.messages.stream().anyMatch(message -> message.contains("kind=RESOURCES delta=-1 active=0")));
            // The monitor retains the opaque probe, never its loader.
            for (var attempt = 0; attempt < 50 && !trackedProbe.get().collected(); attempt++) {
                System.gc();
                Thread.sleep(20);
            }
            assertTrue(trackedProbe.get().collected());
            ExpressionEngineMetrics.pollCollectedLoaders();
            assertTrue(capture.messages.stream().anyMatch(message -> message.contains("Molang loader collected:")));
            awaitCollection(trackedProbe);
            Reference.reachabilityFence(metrics);
        } finally {
            ResourceDiagnostics.setEnabled(previousEnabled);
            logger.removeAppender(capture);
            logger.setLevel(previousLevel);
            capture.stop();
        }
    }

    private static WeakReference<LoaderCollectionProbe> registerTemporaryLoader(ExpressionEngineMetrics metrics)
        throws Exception {
        var constructor = LoaderCollectionProbe.class.getDeclaredConstructor(ClassLoader.class);
        constructor.setAccessible(true);
        var probe = constructor.newInstance(new ClassLoader() {});
        metrics.trackLoader(probe);
        return new WeakReference<>(probe);
    }

    private static void awaitCollection(WeakReference<?> reference) throws InterruptedException {
        for (var attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(reference.get());
    }

    private static final class Capture extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();

        private Capture() {
            super("expression-resource-diagnostics-test", null, null, true);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }

    @Test
    void analysisSummariesAreBoundedSeparatedAndSnapshotsAreIndependent() {
        var metrics = new ExpressionEngineMetrics();
        var counter = EngineMetrics.AnalysisCounter.EXPECTED_EVALUATIONS;
        metrics.recordAnalysisWork(EngineMetrics.CompilationKind.PERSISTENT, counter, 3);
        metrics.recordAnalysisWork(EngineMetrics.CompilationKind.PERSISTENT, counter, 4);
        metrics.recordAnalysisWork(EngineMetrics.CompilationKind.TRANSIENT, counter, 2);
        metrics.recordAnalysisWork(null, counter, 8);
        metrics.recordAnalysisWork(EngineMetrics.CompilationKind.PERSISTENT, counter, -1);
        var snapshot = metrics.snapshot();
        assertEquals(7, snapshot.analysisWork(EngineMetrics.CompilationKind.PERSISTENT, counter));
        assertEquals(2, snapshot.analysisWork(EngineMetrics.CompilationKind.TRANSIENT, counter));
        snapshot.analysisWork()[counter.ordinal()] = 999;
        metrics.recordAnalysisWork(EngineMetrics.CompilationKind.PERSISTENT, counter, 1);
        assertEquals(7, snapshot.analysisWork(EngineMetrics.CompilationKind.PERSISTENT, counter));
        assertEquals(8, metrics.snapshot().analysisWork(EngineMetrics.CompilationKind.PERSISTENT, counter));
    }
}
