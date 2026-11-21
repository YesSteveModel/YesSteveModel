package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.network.protocol.RoamingDictionary;
import com.elfmcys.ysm.network.protocol.RoamingProjection;
import com.elfmcys.ysm.network.protocol.RoamingValues;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Selection-owned inputs. A sync separates state segments whose writes cannot be merged. */
final class PendingRemoteReplay implements AutoCloseable {
    static final int MAX_PENDING_SYNCS = 64;

    private final RoamingDictionary dictionary;
    private final RemoteSyncBudget budget;
    private final ArrayDeque<Input> inputs = new ArrayDeque<>();
    private RoamingProjection baseline;
    private int syncs;
    private EntityExpressionRuntime.Installation installation;

    PendingRemoteReplay(RoamingDictionary dictionary, RoamingValues full, RemoteSyncBudget budget) {
        this.dictionary = dictionary;
        this.budget = budget;
        baseline = new RoamingProjection(dictionary, full);
    }

    RoamingValues baseline() {
        return baseline.snapshot();
    }

    boolean started() {
        return installation != null;
    }

    boolean belongsTo(EntityExpressionRuntime.Installation current) {
        return installation == current;
    }

    boolean active() {
        return budget.isActive();
    }

    void start(EntityExpressionRuntime.Installation current) {
        installation = current;
        baseline = null;
    }

    void state(RoamingValues values, boolean full) {
        if (baseline != null && inputs.isEmpty()) {
            baseline.apply(values, full);
            return;
        }
        if (inputs.peekLast() instanceof State tail) {
            tail.apply(values, full);
        } else {
            inputs.addLast(new State(dictionary.size(), values, full));
        }
    }

    boolean sync(EventArguments arguments) {
        if (!budget.isActive())
            return false;
        if (syncs == MAX_PENDING_SYNCS) {
            budget.recordDroppedSync();
            return false;
        }
        var input = new Sync(EventArguments.sync(arguments));
        if (!budget.tryReserve())
            return false;
        try {
            inputs.addLast(input);
        } catch (OutOfMemoryError failure) {
            budget.release();
            throw failure;
        }
        syncs++;
        return true;
    }

    Input poll() {
        var input = inputs.pollFirst();
        if (input instanceof Sync) {
            syncs--;
            budget.release();
        }
        return input;
    }

    @Override
    public void close() {
        inputs.clear();
        baseline = null;
        while (syncs > 0) {
            syncs--;
            budget.release();
        }
    }

    sealed interface Input permits State, Sync {}

    static final class State implements Input {
        private final float[] values;
        // A same-value network write must still overwrite any preceding sync side effect.
        private long written;
        private boolean full;

        private State(int fieldCount, RoamingValues payload, boolean full) {
            values = new float[fieldCount];
            apply(payload, full);
        }

        private void apply(RoamingValues payload, boolean full) {
            if (full) {
                Arrays.fill(values, 0.0f);
                written = 0;
                this.full = true;
            }
            for (int index = 0; index < payload.ids().length; index++) {
                int id = payload.ids()[index];
                values[id] = payload.values()[index];
                written |= 1L << id;
            }
        }

        RoamingValues values() {
            int count = full ? values.length : Long.bitCount(written);
            var ids = new short[count];
            var snapshot = new float[count];
            int next = 0;
            for (short id = 0; id < values.length; id++) {
                if (full || (written & 1L << id) != 0) {
                    ids[next] = id;
                    snapshot[next++] = values[id];
                }
            }
            return new RoamingValues(ids, snapshot);
        }

        boolean full() {
            return full;
        }
    }

    record Sync(EventArguments arguments) implements Input {
    }
}
