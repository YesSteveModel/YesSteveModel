package com.elfmcys.ysm.util;

import com.elfmcys.ysm.natives.NativeRuntime;

import java.util.concurrent.*;

public final class ThreadTools {
    private static final int THREAD_COUNT = Math.max(2, Math.min(8, NativeRuntime.availableProcessors() / 2));
    private static final ThreadPoolExecutor THREAD_POOL = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT, 30,
        TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "YSM Worker");
            thread.setPriority(Thread.NORM_PRIORITY);
            thread.setDaemon(true);
            return thread;
        });

    public static Future<?> submit(Runnable runnable) {
        return THREAD_POOL.submit(runnable);
    }

    public static <T> Future<T> submit(Callable<T> runnable) {
        return THREAD_POOL.submit(runnable);
    }

    public static boolean safeSleep(int millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
