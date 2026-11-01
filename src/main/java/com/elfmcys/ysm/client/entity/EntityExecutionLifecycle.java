package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.util.ResponsiveExecutor;

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
    private volatile Thread asyncExecutor;
    private volatile GuiTask<?> guiTask;
    private int guiInternalDepth;

    EntityExecutionLifecycle(EntityExpressionRuntime runtime, Runnable cleanup, Consumer<Throwable> taskFailure) {
        this(runtime, cleanup, taskFailure, task -> ResponsiveExecutor.submit(task));
    }

    EntityExecutionLifecycle(EntityExpressionRuntime runtime, Runnable cleanup, Consumer<Throwable> taskFailure,
        java.util.concurrent.Executor executor) {
        this.executor = Objects.requireNonNull(executor);
        this.runtime = Objects.requireNonNull(runtime);
        this.cleanup = Objects.requireNonNull(cleanup);
        this.taskFailure = Objects.requireNonNull(taskFailure);
        runtime.bindExecutionOwner(this::assertRuntimePublicAccess, this::assertStateAccess,
            this::assertInstallationAccess);
    }

    boolean isEndingUse() { return endingUse; }
    boolean isRetired() { return retired; }
    boolean hasTask() { return asyncTask != null || guiTask != null; }
    boolean canAdoptProjection() { return !hasTask() && phase == Phase.IDLE && !endingUse && !retired; }

    void assertMainThread() {
        if (Thread.currentThread() != mainThread)
            throw new IllegalStateException("Entity lifecycle requires its main thread");
    }

    void assertPublicAccess() {
        assertMainThread();
        if (guiTask != null)
            throw new IllegalStateException("Entity GUI task requires its explicit join");
    }

    void assertStateAccess() {
        var task = guiTask;
        if (task != null) {
            task.assertExecuting();
        } else if (asyncTask != null) {
            if (Thread.currentThread() != asyncExecutor)
                throw new IllegalStateException("Entity state requires its task get");
        } else {
            assertMainThread();
        }
    }

    void assertSubjectAccess() {
        // World renderers inspect the fixed subject before joining; GUI inputs remain task-owned.
        if (guiTask != null || Thread.currentThread() != mainThread)
            assertStateAccess();
    }

    private void assertRuntimePublicAccess() {
        assertPublicAccess();
        if (asyncTask != null)
            throw new IllegalStateException("Expression lifecycle requires its entity task get");
    }

    private void assertInstallationAccess() {
        if (guiTask == null) {
            assertRuntimePublicAccess();
        } else {
            guiTask.assertExecuting();
            if (guiInternalDepth == 0)
                throw new IllegalStateException("GUI installation requires its internal execution entry");
        }
    }

    private void rejectInternalReentry() {
        assertInstallationAccess();
        if (phase != Phase.IDLE)
            throw new IllegalStateException("Recursive entity evaluation, draw or installation");
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
            primary = appendFailure(primary, failure, false);
        }
        retired |= permanently;
        endingUse = true;
        runtime.invalidate();
        finishEndedUse(primary);
        if (primary instanceof RuntimeException failure) throw failure;
        if (primary instanceof Error failure) throw failure;
    }

    private void finishEndedUse(Throwable primary) {
        if (!endingUse || phase != Phase.IDLE || hasTask())
            return;
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            var combined = appendFailure(primary, failure, false);
            if (combined != primary) {
                if (combined instanceof RuntimeException runtime) throw runtime;
                if (combined instanceof Error error) throw error;
            }
        } finally {
            endingUse = false;
        }
    }

    void rejectCandidateMutation() {
        assertPublicAccess();
        if (phase == Phase.CANDIDATE || phase == Phase.CALL)
            throw new IllegalStateException("Entity evaluation or candidate assembly cannot mutate its owner");
    }

    void rejectRecursiveCall() {
        assertPublicAccess();
        if (phase != Phase.IDLE)
            throw new IllegalStateException("Recursive entity evaluation, draw or installation");
    }

    <R> R inCandidate(Supplier<R> operation) {
        rejectInternalReentry();
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
        if (retired || phase != Phase.IDLE || hasTask() || endingUse)
            return Optional.empty();
        return Optional.of(operation.get());
    }

    <R> R inCall(Supplier<R> operation) {
        return scoped(Phase.CALL, operation);
    }

    <R> R inDraw(Supplier<R> operation) {
        assertPublicAccess();
        return scoped(Phase.DRAW, operation);
    }

    private <R> R scoped(Phase active, Supplier<R> operation) {
        rejectInternalReentry();
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
        var task = new FutureTask<Object>(() -> {
            asyncExecutor = Thread.currentThread();
            try {
                return operation.get();
            } finally {
                asyncExecutor = null;
            }
        });
        asyncTask = task;
        try {
            executor.execute(task);
        } catch (RuntimeException | Error failure) {
            asyncTask = null;
            throw failure;
        }
    }

    void awaitTask() {
        assertPublicAccess();
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

    <R> GuiTask<R> reserveGuiTask(boolean permitted, Supplier<R> operation) {
        rejectRecursiveCall();
        if (!permitted || retired || endingUse || hasTask())
            throw new IllegalStateException("GUI reservation requires an active idle GUI owner");
        var task = new GuiTask<R>(Objects.requireNonNull(operation));
        guiTask = task;
        return task;
    }

    <R> R inGuiExecution(GuiTask<?> permit, Supplier<R> operation) {
        var task = guiTask;
        if (task == null || task != permit)
            throw new IllegalStateException("GUI execution belongs to another task");
        task.assertExecuting();
        if (phase != Phase.IDLE || guiInternalDepth != 0)
            throw new IllegalStateException("Recursive GUI task execution");
        guiInternalDepth++;
        try {
            return operation.get();
        } finally {
            guiInternalDepth--;
        }
    }

    void endInstallationUse() {
        if (guiTask == null) {
            endUse();
        } else {
            assertInstallationAccess();
            endingUse = true;
        }
    }

    static Throwable appendGuiFailure(Throwable primary, Throwable next) {
        return appendFailure(primary, next, true);
    }

    private static Throwable appendFailure(Throwable primary, Throwable next, boolean promoteOutOfMemory) {
        if (primary == null)
            return next;
        if (fatal(next, promoteOutOfMemory) && !fatal(primary, promoteOutOfMemory)) {
            next.addSuppressed(primary);
            return next;
        }
        if (next != primary)
            primary.addSuppressed(next);
        return primary;
    }

    private static boolean fatal(Throwable failure, boolean promoteOutOfMemory) {
        return failure instanceof ThreadDeath || failure instanceof VirtualMachineError
            && (promoteOutOfMemory || !(failure instanceof OutOfMemoryError));
    }

    final class GuiTask<R> implements Runnable {
        private final FutureTask<R> result;
        private volatile Thread executing;
        private boolean started;

        private GuiTask(Supplier<R> operation) {
            result = new FutureTask<>(() -> {
                executing = Thread.currentThread();
                try {
                    return operation.get();
                } finally {
                    executing = null;
                }
            });
        }

        private void assertExecuting() {
            if (guiTask != this || executing != Thread.currentThread())
                throw new IllegalStateException("Entity access requires its exact executing GUI task");
        }

        @Override
        public void run() {
            synchronized (this) {
                if (guiTask != this || started)
                    throw new IllegalStateException("GUI task can enter only once");
                started = true;
            }
            result.run();
        }

        void reject() {
            assertMainThread();
            synchronized (this) {
                if (guiTask != this || started)
                    throw new IllegalStateException("Only an unaccepted GUI task can be rejected");
                guiTask = null;
            }
        }

        R join() {
            assertMainThread();
            synchronized (this) {
                if (guiTask != this || executing == Thread.currentThread())
                    throw new IllegalStateException("GUI task requires its corresponding join");
            }
            boolean interrupted = false;
            Throwable primary = null;
            R value = null;
            try {
                for (;;) {
                    try {
                        value = result.get();
                        break;
                    } catch (InterruptedException pending) {
                        interrupted = true;
                    } catch (ExecutionException failure) {
                        primary = failure.getCause();
                        break;
                    }
                }
                guiTask = null;
                if (primary != null) {
                    try {
                        taskFailure.accept(primary);
                    } catch (RuntimeException | Error failure) {
                        primary = appendGuiFailure(primary, failure);
                    }
                }
                if (endingUse) {
                    try {
                        endUse();
                    } catch (RuntimeException | Error failure) {
                        primary = appendGuiFailure(primary, failure);
                    }
                }
                if (primary instanceof RuntimeException failure) throw failure;
                if (primary instanceof Error failure) throw failure;
                if (primary != null) throw new IllegalStateException("GUI task failed", primary);
                return value;
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }
    }
}
