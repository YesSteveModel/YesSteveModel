package com.elfmcys.ysm.util;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.natives.ThreadSchedulingResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Function;

/** Applies one fixed policy at the entry of a dedicated YSM platform thread. */
public final class ThreadScheduling {
    public enum ThreadRole {
        CATALOG_COORDINATOR, CATALOG_WORKER, CLIENT_LOCAL_PLAYER_RESOURCE, CLIENT_BACKGROUND_RESOURCE, SERVER_RESOURCE,
        SERVER_PREVIEW, TRANSIENT_COMPILER, CLIENT_NOTIFICATION_TIMER, MISSING_SERVER_NOTICE, DERIVED_STORAGE
    }

    public enum ThreadPolicy {
        NORMAL(0), BELOW_NORMAL(1), LOW(2);

        private final int nativeCode;

        ThreadPolicy(int nativeCode) {
            this.nativeCode = nativeCode;
        }

        public int nativeCode() {
            return nativeCode;
        }
    }

    private static final Diagnostics DIAGNOSTICS = new Diagnostics();

    private ThreadScheduling() {}

    public static Runnable entry(ThreadRole role, ThreadPolicy policy, Runnable body) {
        return entry(role, policy, body, NativeRuntime::applyCurrentThreadPolicy,
            result -> DIAGNOSTICS.report(role, policy, result, report -> YesSteveModel.LOGGER
                .warn("Thread scheduling: role={}, policy={}, report={}", role, policy, report)));
    }

    static Runnable entry(ThreadRole role, ThreadPolicy policy, Runnable body,
        Function<ThreadPolicy, ThreadSchedulingResult> apply, Consumer<ThreadSchedulingResult> diagnostic) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(body, "body");
        return () -> {
            if (policy != ThreadPolicy.NORMAL) {
                ThreadSchedulingResult result;
                try {
                    result = Objects.requireNonNull(apply.apply(policy), "Scheduling result");
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    result = ThreadSchedulingResult.unavailable(failure.getClass().getName());
                }
                try {
                    diagnostic.accept(result);
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                }
            }
            body.run();
        };
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    static final class Diagnostics {
        private final AtomicReferenceArray<Fingerprint> latest =
            new AtomicReferenceArray<>(ThreadRole.values().length * ThreadPolicy.values().length);

        void report(ThreadRole role, ThreadPolicy policy, ThreadSchedulingResult result,
            Consumer<ThreadSchedulingResult> sink) {
            if (result.outcome() == ThreadSchedulingResult.Outcome.APPLIED
                || result.outcome() == ThreadSchedulingResult.Outcome.UNCHANGED) {
                return;
            }
            var requiredMask = switch (result.platform()) {
                case WINDOWS -> policy == ThreadPolicy.LOW ? 5 : 1;
                case LINUX, ANDROID -> 3;
                case MACOS -> 24;
                case UNKNOWN -> 0;
            };
            var failures =
                result.steps().stream().filter(step -> step.result() == ThreadSchedulingResult.StepResult.FAILED
                    || step.result() == ThreadSchedulingResult.StepResult.UNSUPPORTED).toList();
            var fingerprint = new Fingerprint(result.outcome(), failures, requiredMask & ~result.observations().mask());
            var slot = role.ordinal() * ThreadPolicy.values().length + policy.ordinal();
            if (!fingerprint.equals(latest.getAndSet(slot, fingerprint))) {
                sink.accept(result);
            }
        }
    }

    private record Fingerprint(ThreadSchedulingResult.Outcome outcome, List<ThreadSchedulingResult.Step> failures,
        int missingObservedMask) {
    }
}
