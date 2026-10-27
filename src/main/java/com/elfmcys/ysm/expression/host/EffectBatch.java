package com.elfmcys.ysm.expression.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Invocation-local ordered effect batch with one terminal commit or discard. */
public final class EffectBatch {
    public static final int DEFAULT_MAX_COMMANDS = 256;

    public enum State {
        OPEN, COMMITTED, DISCARDED
    }

    private final int maximumCommands;
    private final InvocationProvenance provenance;
    private final ArrayList<EffectCommand> commands = new ArrayList<>();
    private final ArrayList<InvocationProvenance> sources = new ArrayList<>();
    private State state = State.OPEN;

    public EffectBatch(int maximumCommands, InvocationProvenance provenance) {
        if (maximumCommands <= 0) {
            throw new IllegalArgumentException("maximumCommands must be positive");
        }
        this.maximumCommands = maximumCommands;
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    void append(EffectCommand command, InvocationProvenance source) {
        requireOpen();
        if (commands.size() == maximumCommands) {
            throw failure(ExecutionFailure.Reason.EFFECT_BATCH_LIMIT,
                "effect command count exceeds " + maximumCommands);
        }
        commands.add(Objects.requireNonNull(command, "command"));
        sources.add(Objects.requireNonNull(source, "source"));
    }

    void commit(EffectSink sink, Runnable eligibilityCheck) {
        requireOpen();
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(eligibilityCheck, "eligibilityCheck");
        eligibilityCheck.run();
        var prepared = new ArrayList<EffectSink.PreparedCommit>();
        if (commands.isEmpty()) {
            prepared.add(Objects.requireNonNull(sink.prepare(List.of(), provenance), "prepared commit"));
        }
        for (int start = 0; start < commands.size();) {
            int end = start + 1;
            var source = sources.get(start);
            while (end < commands.size() && source.equals(sources.get(end)))
                end++;
            prepared.add(Objects.requireNonNull(sink.prepare(List.copyOf(commands.subList(start, end)), source),
                "prepared commit"));
            start = end;
        }
        eligibilityCheck.run();
        state = State.COMMITTED;
        prepared.forEach(EffectSink.PreparedCommit::commit);
        commands.clear();
        sources.clear();
    }

    void discard() {
        if (state == State.OPEN) {
            state = State.DISCARDED;
            commands.clear();
            sources.clear();
        }
    }

    public State state() {
        return state;
    }

    public int size() {
        return commands.size();
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw failure(ExecutionFailure.Reason.EFFECT_BATCH_STATE, "effect batch is already " + state);
        }
    }

    private ExecutionFailure failure(ExecutionFailure.Reason reason, String message) {
        return new ExecutionFailure(reason, provenance, message);
    }
}
