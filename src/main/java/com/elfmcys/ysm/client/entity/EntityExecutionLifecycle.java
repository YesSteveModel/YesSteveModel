package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.util.ThreadTools;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Uses the expression barrier so execution, installation and effect revocation share one order. */
final class EntityExecutionLifecycle {
    private enum Phase {
        IDLE, CANDIDATE, CALL
    }

    private final EntityExpressionRuntime runtime;
    private final Runnable cleanup;
    private final Consumer<Throwable> taskFailure;
    private boolean endingUse;
    private Phase phase = Phase.IDLE;
    private AnimationTask asyncTask;

    EntityExecutionLifecycle(EntityExpressionRuntime runtime, Runnable cleanup, Consumer<Throwable> taskFailure) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.taskFailure = Objects.requireNonNull(taskFailure, "taskFailure");
    }

    boolean isEndingUse() {
        return endingUse;
    }

    boolean hasTask() {
        return asyncTask != null;
    }

    void endUse() {
        rejectCandidateMutation();
        runtime.serialized(() -> {
            endingUse = true;
            runtime.invalidate();
            return null;
        });
        if (phase == Phase.IDLE)
            awaitTask();
    }

    private void finishEndedUse() {
        if (!endingUse || phase != Phase.IDLE || asyncTask != null)
            return;
        runtime.serialized(() -> {
            if (!endingUse || phase != Phase.IDLE || asyncTask != null)
                return null;
            try {
                cleanup.run();
            } finally {
                endingUse = false;
            }
            return null;
        });
    }

    private void finishEndedUse(Throwable primary) {
        try {
            finishEndedUse();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary == null)
                throw cleanupFailure;
            if ((cleanupFailure instanceof VirtualMachineError && !(cleanupFailure instanceof OutOfMemoryError)
                || cleanupFailure instanceof ThreadDeath)
                && !(primary instanceof VirtualMachineError || primary instanceof ThreadDeath)) {
                cleanupFailure.addSuppressed(primary);
                throw cleanupFailure;
            }
            if (cleanupFailure != primary)
                primary.addSuppressed(cleanupFailure);
        }
    }

    void rejectCandidateMutation() {
        if (phase == Phase.CANDIDATE) {
            throw new IllegalStateException("Candidate assembly cannot mutate its owner");
        }
    }

    void rejectRecursiveCall() {
        if (phase != Phase.IDLE && runtime.isOwnerThread()) {
            throw new IllegalStateException("Recursive entity evaluation or installation");
        }
    }

    <R> R inCandidate(Supplier<R> operation) {
        return runtime.serialized(() -> {
            if (phase != Phase.IDLE)
                throw new IllegalStateException("Recursive candidate preparation");
            phase = Phase.CANDIDATE;
            try {
                return operation.get();
            } finally {
                phase = Phase.IDLE;
            }
        });
    }

    <R> java.util.Optional<R> tryInstallation(Supplier<R> operation) {
        return runtime.trySerialized(() -> {
            if (phase != Phase.IDLE || asyncTask != null && !asyncTask.isDone())
                return java.util.Optional.<R>empty();
            var completed = asyncTask;
            if (completed != null) {
                asyncTask = null;
                finishEndedUse(completed.failure);
                if (completed.failure != null)
                    taskFailure.accept(completed.failure);
            }
            finishEndedUse();
            if (endingUse)
                return java.util.Optional.<R>empty();
            return java.util.Optional.of(operation.get());
        }).orElseGet(java.util.Optional::empty);
    }

    <R> R inCall(Supplier<R> operation) {
        return runtime.serialized(() -> {
            rejectRecursiveCall();
            phase = Phase.CALL;
            Throwable primary = null;
            try {
                return operation.get();
            } catch (RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                phase = Phase.IDLE;
                if (asyncTask == null)
                    finishEndedUse(primary);
            }
        });
    }

    <R> void submit(Supplier<R> operation) {
        rejectRecursiveCall();
        awaitTask();
        if (endingUse)
            return;
        var task = new AnimationTask(() -> inCall(operation));
        asyncTask = task;
        try {
            ThreadTools.submit(task);
        } catch (RuntimeException | Error failure) {
            asyncTask = null;
            throw failure;
        }
    }

    void awaitTask() {
        if (phase != Phase.IDLE && runtime.isOwnerThread()) {
            throw new IllegalStateException("An owner call cannot join itself");
        }
        var task = asyncTask;
        if (task == null) {
            finishEndedUse();
            return;
        }
        boolean interrupted = false;
        Throwable failure = null;
        try {
            // Waiting under the expression barrier would prevent the worker from reaching its terminal state.
            for (;;) {
                try {
                    task.get();
                    break;
                } catch (InterruptedException pending) {
                    interrupted = true;
                } catch (ExecutionException failed) {
                    failure = failed.getCause();
                    break;
                }
            }
        } finally {
            asyncTask = null;
            try {
                finishEndedUse(failure);
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }
        if (failure != null)
            taskFailure.accept(failure);
    }

    private static final class AnimationTask extends FutureTask<Object> {
        private volatile Throwable failure;

        private AnimationTask(Supplier<?> operation) {
            super(operation::get);
        }

        @Override
        protected void setException(Throwable failure) {
            this.failure = failure;
            super.setException(failure);
        }
    }

}
