package com.elfmcys.ysm.model.resource.client.work;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;

/** Owns execution capacity; resource owners retain cancellation and cleanup responsibility. */
public final class ClientResourceScheduler {
    private static final PromotionLink NO_PROMOTION = () -> {
    };

    private final Object lock = new Object();
    private final ArrayDeque<QueuedTask> backgroundQueue = new ArrayDeque<>();
    private final ArrayDeque<QueuedTask> localPlayerQueue = new ArrayDeque<>();
    private final Thread[] workers;
    private final Executor backgroundExecutor = action -> dispatch(WorkPriority.BACKGROUND).execute(action);
    private boolean shutdown;

    public ClientResourceScheduler(int workerCount) {
        this(workerCount, (policy, body) -> ThreadScheduling.entry(policy == ThreadPolicy.NORMAL
            ? ThreadRole.CLIENT_LOCAL_PLAYER_RESOURCE : ThreadRole.CLIENT_BACKGROUND_RESOURCE, policy, body));
    }

    ClientResourceScheduler(int workerCount, BiFunction<ThreadPolicy, Runnable, Runnable> entries) {
        if (workerCount < 2) {
            throw new IllegalArgumentException("At least two resource workers are required");
        }
        Objects.requireNonNull(entries, "entries");
        workers = new Thread[workerCount];
        for (var index = 0; index < workerCount; index++) {
            var priority = index == 0 ? WorkPriority.LOCAL_PLAYER : WorkPriority.BACKGROUND;
            var policy = index == 0 ? ThreadPolicy.NORMAL : ThreadPolicy.BELOW_NORMAL;
            var thread = new Thread(entries.apply(policy, () -> runWorker(priority)),
                index == 0 ? "YSM Local Player Resource Worker" : "YSM Background Resource Worker " + index);
            thread.setDaemon(true);
            if (policy == ThreadPolicy.NORMAL) {
                thread.setPriority(Thread.NORM_PRIORITY);
            }
            workers[index] = thread;
        }
        try {
            for (var worker : workers) {
                worker.start();
            }
        } catch (Throwable failure) {
            shutdown();
            throw failure;
        }
    }

    public Dispatch dispatch(WorkPriority initial) {
        return new Dispatch(Objects.requireNonNull(initial, "initial"));
    }

    public Executor backgroundExecutor() {
        return backgroundExecutor;
    }

    public void shutdown() {
        synchronized (lock) {
            shutdown = true;
            lock.notifyAll();
        }
    }

    public void awaitTermination() throws InterruptedException {
        for (var worker : workers) {
            worker.join();
        }
    }

    private ArrayDeque<QueuedTask> queue(WorkPriority priority) {
        return priority == WorkPriority.LOCAL_PLAYER ? localPlayerQueue : backgroundQueue;
    }

    private void promoteLocked(Dispatch dispatch) {
        if (dispatch.priority == WorkPriority.LOCAL_PLAYER) {
            return;
        }
        dispatch.priority = WorkPriority.LOCAL_PLAYER;
        for (var task : dispatch.queued) {
            backgroundQueue.remove(task);
            localPlayerQueue.addLast(task);
        }
        for (var link : dispatch.dependencies) {
            promoteLocked(link.dependency);
        }
        lock.notifyAll();
    }

    private void runWorker(WorkPriority priority) {
        var queue = queue(priority);
        var interrupted = false;
        try {
            while (true) {
                QueuedTask task;
                Runnable action;
                int remaining;
                synchronized (lock) {
                    while (queue.isEmpty() && !shutdown) {
                        try {
                            lock.wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    if (queue.isEmpty()) {
                        return;
                    }
                    task = queue.removeFirst();
                    task.queued = false;
                    task.dispatch.queued.remove(task);
                    action = task.action;
                    task.action = null;
                    remaining = queue.size();
                }
                var claimedAt = task.enqueuedAt == 0 ? 0 : System.nanoTime();
                if (claimedAt != 0) {
                    ResourceDiagnostics.log(
                        "resource work claimed: submitted={} worker={} promoted={} waitNanos={} remaining={}",
                        task.submittedPriority, priority, task.submittedPriority != priority,
                        claimedAt - task.enqueuedAt, remaining);
                }
                var started = task.enqueuedAt == 0 ? 0 : System.nanoTime();
                try {
                    action.run();
                } catch (Throwable failure) {
                    YesSteveModel.LOGGER.error("Uncaught client resource worker action failure", failure);
                } finally {
                    if (started != 0) {
                        ResourceDiagnostics.log("resource work completed: worker={} computeNanos={}", priority,
                            System.nanoTime() - started);
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public final class Dispatch implements Executor {
        private WorkPriority priority;
        private final LinkedHashSet<QueuedTask> queued = new LinkedHashSet<>();
        private final LinkedHashSet<Link> dependencies = new LinkedHashSet<>();

        private Dispatch(WorkPriority initial) {
            priority = initial;
        }

        public WorkPriority priority() {
            synchronized (lock) {
                return priority;
            }
        }

        public QueuedTask enqueue(Runnable action) {
            Objects.requireNonNull(action, "action");
            QueuedTask task;
            int backgroundSize;
            int localPlayerSize;
            synchronized (lock) {
                if (shutdown) {
                    throw new RejectedExecutionException("Client resource scheduler is shut down");
                }
                task = new QueuedTask(this, action);
                queued.add(task);
                queue(priority).addLast(task);
                backgroundSize = backgroundQueue.size();
                localPlayerSize = localPlayerQueue.size();
                lock.notifyAll();
            }
            if (task.enqueuedAt != 0) {
                ResourceDiagnostics.log("resource work enqueued: priority={} backgroundQueued={} localPlayerQueued={}",
                    task.submittedPriority, backgroundSize, localPlayerSize);
            }
            return task;
        }

        @Override
        public void execute(Runnable action) {
            enqueue(action);
        }

        public void promoteLocalPlayer() {
            synchronized (lock) {
                // A drained local worker must never receive work after shutdown.
                if (!shutdown) {
                    promoteLocked(this);
                }
            }
        }

        public PromotionLink forwardPromotionTo(Dispatch dependency) {
            Objects.requireNonNull(dependency, "dependency");
            if (dependency == this || dependency.scheduler() != ClientResourceScheduler.this) {
                throw new IllegalArgumentException("Promotion requires a distinct dispatch from the same scheduler");
            }
            synchronized (lock) {
                if (shutdown) {
                    return NO_PROMOTION;
                }
                var link = new Link(this, dependency);
                dependencies.add(link);
                if (priority == WorkPriority.LOCAL_PLAYER) {
                    promoteLocked(dependency);
                }
                return link;
            }
        }

        private ClientResourceScheduler scheduler() {
            return ClientResourceScheduler.this;
        }
    }

    public final class QueuedTask {
        private final Dispatch dispatch;
        private final WorkPriority submittedPriority;
        private final long enqueuedAt;
        private Runnable action;
        private boolean queued = true;

        private QueuedTask(Dispatch dispatch, Runnable action) {
            this.dispatch = dispatch;
            this.action = action;
            submittedPriority = dispatch.priority;
            enqueuedAt = ResourceDiagnostics.isEnabled() ? System.nanoTime() : 0;
        }

        /** A successful withdrawal returns cleanup responsibility to the original owner. */
        public boolean withdraw() {
            synchronized (lock) {
                if (!queued) {
                    return false;
                }
                queue(dispatch.priority).remove(this);
                dispatch.queued.remove(this);
                queued = false;
                action = null;
                return true;
            }
        }
    }

    public interface PromotionLink extends AutoCloseable {
        @Override
        void close();
    }

    private final class Link implements PromotionLink {
        private Dispatch source;
        private Dispatch dependency;

        private Link(Dispatch source, Dispatch dependency) {
            this.source = source;
            this.dependency = dependency;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (source != null) {
                    source.dependencies.remove(this);
                    source = null;
                    dependency = null;
                }
            }
        }
    }
}
