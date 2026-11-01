package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.util.ThreadTools;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Main-thread lifecycle; a pending task remains private until its corresponding get. */
final class EntityExecutionLifecycle {
    private enum Phase { IDLE, CANDIDATE, CALL, DRAW }

    private final Thread mainThread = Thread.currentThread();
    private final EntityExpressionRuntime runtime;
    private final Runnable cleanup;
    private final Consumer<Throwable> taskFailure;
    private final java.util.concurrent.Executor executor;
    private boolean endingUse;
    private boolean retired;
    private Phase phase = Phase.IDLE;
    private FutureTask<Object> asyncTask;

    EntityExecutionLifecycle(EntityExpressionRuntime runtime, Runnable cleanup, Consumer<Throwable> taskFailure) {
        this(runtime, cleanup, taskFailure, task -> ThreadTools.submit(task));
    }

    EntityExecutionLifecycle(EntityExpressionRuntime runtime, Runnable cleanup, Consumer<Throwable> taskFailure,
        java.util.concurrent.Executor executor) {
        this.executor = Objects.requireNonNull(executor);
        this.runtime = Objects.requireNonNull(runtime);
        this.cleanup = Objects.requireNonNull(cleanup);
        this.taskFailure = Objects.requireNonNull(taskFailure);
    }

    boolean isEndingUse() { return endingUse; }
    boolean isRetired() { return retired; }
    boolean hasTask() { return asyncTask != null; }
    boolean canAdoptProjection() { return asyncTask == null && phase == Phase.IDLE && !endingUse && !retired; }

    void assertMainThread() {
        if (Thread.currentThread() != mainThread)
            throw new IllegalStateException("Entity lifecycle requires its main thread");
    }

    void endUse() { endUse(false); }

    void retire() { endUse(true); }

    private void endUse(boolean permanently) {
        rejectCandidateMutation();
        if (permanently && retired)
            return;
        Throwable primary = null;
        try {
            awaitTask();
        } catch (RuntimeException | Error failure) {
            primary = failure;
        }
        try {
            // Sealing discards queued projections, including valid deltas from the joined task.
            runtime.adoptProjections(true);
        } catch (RuntimeException | Error failure) {
            if (primary == null)
                primary = failure;
            else if (failure != primary)
                primary.addSuppressed(failure);
        }
        retired |= permanently;
        endingUse = true;
        runtime.invalidate();
        finishEndedUse(primary);
        if (primary instanceof RuntimeException failure) throw failure;
        if (primary instanceof Error failure) throw failure;
    }

    private void finishEndedUse(Throwable primary) {
        if (!endingUse || phase != Phase.IDLE || asyncTask != null)
            return;
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            if (primary == null)
                throw failure;
            if (failure != primary)
                primary.addSuppressed(failure);
        } finally {
            endingUse = false;
        }
    }

    void rejectCandidateMutation() {
        assertMainThread();
        if (phase == Phase.CANDIDATE || phase == Phase.CALL)
            throw new IllegalStateException("Entity evaluation or candidate assembly cannot mutate its owner");
    }

    void rejectRecursiveCall() {
        assertMainThread();
        if (phase != Phase.IDLE)
            throw new IllegalStateException("Recursive entity evaluation, draw or installation");
    }

    <R> R inCandidate(Supplier<R> operation) {
        rejectRecursiveCall();
        if (retired || asyncTask != null)
            throw new IllegalStateException("Candidate requires an active joined owner");
        phase = Phase.CANDIDATE;
        try {
            return operation.get();
        } finally {
            phase = Phase.IDLE;
        }
    }

    <R> Optional<R> tryInstallation(Supplier<R> operation) {
        assertMainThread();
        if (retired || phase != Phase.IDLE || asyncTask != null || endingUse)
            return Optional.empty();
        return Optional.of(operation.get());
    }

    <R> R inCall(Supplier<R> operation) {
        return scoped(Phase.CALL, operation);
    }

    <R> R inDraw(Supplier<R> operation) {
        return scoped(Phase.DRAW, operation);
    }

    private <R> R scoped(Phase active, Supplier<R> operation) {
        rejectRecursiveCall();
        if (asyncTask != null)
            throw new IllegalStateException("Entity call requires its task get");
        phase = active;
        Throwable primary = null;
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            phase = Phase.IDLE;
            finishEndedUse(primary);
        }
    }

    <R> void submit(Supplier<R> operation) {
        rejectRecursiveCall();
        awaitTask();
        if (retired || endingUse)
            return;
        var task = new FutureTask<Object>(operation::get);
        asyncTask = task;
        try {
            executor.execute(task);
        } catch (RuntimeException | Error failure) {
            asyncTask = null;
            throw failure;
        }
    }

    void awaitTask() {
        assertMainThread();
        if (phase == Phase.CALL || phase == Phase.CANDIDATE)
            throw new IllegalStateException("An entity call cannot join itself");
        var task = asyncTask;
        if (task == null)
            return;
        boolean interrupted = false;
        Throwable failure = null;
        try {
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
            asyncTask = null;
            if (failure != null)
                taskFailure.accept(failure);
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }
}
