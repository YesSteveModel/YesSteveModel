package com.elfmcys.ysm.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Accepted jobs always return ownership before this render invocation exits. */
public final class GuiFrameTasks {
    private GuiFrameTasks() {}

    public interface Job extends Runnable {
        void join();
        void reject();
        void publish();
        void finish();
    }

    public record Admission(boolean[] admitted, int nextCursor) {}

    public static Admission admit(boolean[] pending, int cursor, int maximumPoolSize) {
        var admitted = new boolean[pending.length];
        if (pending.length == 0)
            return new Admission(admitted, 0);
        int checked = 0;
        int count = 0;
        int index = Math.floorMod(cursor, pending.length);
        while (checked < pending.length && count < maximumPoolSize + 1) {
            if (pending[index]) {
                admitted[index] = true;
                count++;
            }
            index = (index + 1) % pending.length;
            checked++;
        }
        return new Admission(admitted, index);
    }

    public static Throwable execute(List<? extends Job> jobs, Executor executor) {
        if (jobs.isEmpty())
            return null;
        var accepted = new ArrayList<Job>(jobs.size());
        Throwable failure = null;
        try {
            for (int index = 1; index < jobs.size(); index++) {
                var job = jobs.get(index);
                try {
                    executor.execute(job);
                    accepted.add(job);
                } catch (Throwable error) {
                    failure = append(failure, error);
                    try { job.reject(); } catch (Throwable cleanup) { failure = append(failure, cleanup); }
                }
            }
            var first = jobs.get(0);
            try { first.run(); } catch (Throwable error) { failure = append(failure, error); }
            try { first.join(); } catch (Throwable error) { failure = append(failure, error); }
            try { first.publish(); } catch (Throwable error) { failure = append(failure, error); }
        } finally {
            for (var job : accepted) {
                try { job.join(); } catch (Throwable error) { failure = append(failure, error); }
            }
            for (var job : accepted) {
                try { job.publish(); } catch (Throwable error) { failure = append(failure, error); }
            }
            for (var job : jobs) {
                try { job.finish(); } catch (Throwable error) { failure = append(failure, error); }
            }
        }
        return failure;
    }

    public static Throwable append(Throwable primary, Throwable next) {
        if (primary == null)
            return next;
        if ((next instanceof VirtualMachineError || next instanceof ThreadDeath)
            && !(primary instanceof VirtualMachineError || primary instanceof ThreadDeath)) {
            next.addSuppressed(primary);
            return next;
        }
        if (primary != next)
            primary.addSuppressed(next);
        return primary;
    }
}
