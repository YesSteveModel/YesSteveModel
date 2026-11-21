package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.format.schema.file.ChannelReadPhase;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Classifies only failures of an optional cache read; production and business callbacks stay outside the catch. */
public final class CacheReadFailure {
    private CacheReadFailure() {}

    public static void miss(Object source, IOException failure) throws IOException {
        if (!recoverable(failure))
            throw failure;
        YesSteveModel.LOGGER.debug("Cache read missed: {}", source, failure);
    }

    /** Close failures can be suppressed by a simultaneous invalid read, so the complete failure tree matters. */
    public static boolean recoverable(IOException failure) {
        return recoverable(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean recoverable(Throwable failure, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure))
            return true;
        if (failure instanceof Error error)
            throw error;
        if (failure instanceof CancellationException cancelled)
            throw cancelled;
        boolean result = !(failure instanceof InterruptedIOException || failure instanceof ClosedChannelException
            || failure instanceof ChannelReadPhase.CloseFailure);
        result &= recoverable(failure.getCause(), seen);
        for (var suppressed : failure.getSuppressed())
            result &= recoverable(suppressed, seen);
        return result;
    }
}
