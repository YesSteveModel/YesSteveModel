package com.elfmcys.ysm.util;

import com.elfmcys.ysm.natives.ThreadSchedulingResult;
import com.elfmcys.ysm.natives.ThreadSchedulingResult.Observations;
import com.elfmcys.ysm.natives.ThreadSchedulingResult.Outcome;
import com.elfmcys.ysm.natives.ThreadSchedulingResult.Platform;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ThreadSchedulingTest {
    @Test
    void normalEntryDoesNotApplyPolicyOrReport() {
        var bodies = new AtomicInteger();
        var effects = new ArrayList<String>();
        ThreadScheduling.entry(ThreadRole.CATALOG_COORDINATOR, ThreadPolicy.NORMAL, bodies::incrementAndGet, policy -> {
            effects.add("apply");
            return ThreadSchedulingResult.unchanged();
        }, result -> effects.add("diagnostic")).run();
        assertEquals(1, bodies.get());
        assertTrue(effects.isEmpty());
    }

    @Test
    void recoverableSchedulingAndDiagnosticFailuresStillEnterTheBodyExactlyOnce() {
        record Attempt(Supplier<ThreadSchedulingResult> apply, Outcome expected) {
        }
        var failed = new ThreadSchedulingResult(Outcome.FAILED, Platform.LINUX, new Observations(0, 0, 0, 0, 0, 0),
            List.of(), "");
        for (var attempt : List.of(new Attempt(() -> {
            throw new UnsatisfiedLinkError("unbound");
        }, Outcome.UNAVAILABLE), new Attempt(() -> {
            throw new SecurityException("denied");
        }, Outcome.UNAVAILABLE), new Attempt(() -> {
            throw new IllegalStateException("adapter failure");
        }, Outcome.UNAVAILABLE), new Attempt(() -> null, Outcome.UNAVAILABLE),
            new Attempt(() -> failed, Outcome.FAILED))) {
            var bodies = new AtomicInteger();
            var diagnostics = new ArrayList<Outcome>();
            ThreadScheduling.entry(ThreadRole.CATALOG_WORKER, ThreadPolicy.LOW, bodies::incrementAndGet,
                policy -> attempt.apply().get(), result -> {
                    diagnostics.add(result.outcome());
                    throw new IllegalStateException("logger failure");
                }).run();
            assertEquals(1, bodies.get());
            assertEquals(List.of(attempt.expected()), diagnostics);
        }
    }

    @Test
    void everyEntryAppliesPolicyDespiteDiagnosticDeduplication() {
        var applications = new AtomicInteger();
        var reports = new AtomicInteger();
        var bodies = new AtomicInteger();
        var diagnostics = new ThreadScheduling.Diagnostics();
        Function<ThreadPolicy, ThreadSchedulingResult> apply = policy -> {
            applications.incrementAndGet();
            return ThreadSchedulingResult.unavailable("unbound");
        };
        for (var index = 0; index < 3; index++) {
            ThreadScheduling.entry(ThreadRole.SERVER_PREVIEW, ThreadPolicy.BELOW_NORMAL, bodies::incrementAndGet, apply,
                result -> diagnostics.report(ThreadRole.SERVER_PREVIEW, ThreadPolicy.BELOW_NORMAL, result,
                    report -> reports.incrementAndGet()))
                .run();
        }
        assertEquals(3, applications.get());
        assertEquals(3, bodies.get());
        assertEquals(1, reports.get());
        diagnostics.report(ThreadRole.CATALOG_WORKER, ThreadPolicy.LOW, ThreadSchedulingResult.unavailable("unbound"),
            report -> reports.incrementAndGet());
        assertEquals(2, reports.get());
    }

    @Test
    void aChangedMissingReadbackMaskHasANewFingerprint() {
        var diagnostics = new ThreadScheduling.Diagnostics();
        var reports = new AtomicInteger();
        var step = new ThreadSchedulingResult.Step(ThreadSchedulingResult.Operation.SET_MEMORY,
            ThreadSchedulingResult.StepResult.FAILED, ThreadSchedulingResult.ErrorDomain.WIN32, 5);
        for (var mask : new int[] {1, 1, 5}) {
            var result = new ThreadSchedulingResult(Outcome.PARTIAL, Platform.WINDOWS,
                new Observations(mask, -1, 0, mask == 5 ? 5 : 0, 0, 0), List.of(step), "");
            diagnostics.report(ThreadRole.CATALOG_WORKER, ThreadPolicy.LOW, result,
                report -> reports.incrementAndGet());
        }
        assertEquals(2, reports.get());
    }

    @Test
    void fatalFailuresAndBusinessFailuresPropagate() {
        var fatal = new InternalError("fatal");
        assertSame(fatal, assertThrows(InternalError.class, () -> ThreadScheduling
            .entry(ThreadRole.CATALOG_WORKER, ThreadPolicy.LOW, () -> fail("Fatal failure must stop entry"), policy -> {
                throw fatal;
            }, result -> {
            }).run()));
        var business = new IllegalStateException("business");
        assertSame(business, assertThrows(IllegalStateException.class,
            () -> ThreadScheduling.entry(ThreadRole.CATALOG_WORKER, ThreadPolicy.LOW, () -> {
                throw business;
            }, policy -> ThreadSchedulingResult.unchanged(), result -> {
            }).run()));
    }
}
