package com.elfmcys.ysm.expression.oracle;

import com.elfmcys.ysm.expression.host.EffectCommand;
import com.elfmcys.ysm.expression.host.EffectSink;
import com.elfmcys.ysm.expression.host.EffectValidator;
import com.elfmcys.ysm.expression.host.InvocationProvenance;
import com.elfmcys.ysm.expression.host.MolangContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Records real effect prevalidation and commits; expected payloads belong to the oracle tests. */
final class OracleRecorder implements EffectValidator, EffectSink {
    private final ArrayList<EffectCommand> staged = new ArrayList<>();
    private final ArrayList<EffectCommand> committed = new ArrayList<>();
    private Class<? extends EffectCommand> failingPrevalidation;
    private RuntimeException effectFailure;
    private int prepareCalls;
    private int commitCalls;

    void failPrevalidation(Class<? extends EffectCommand> commandType, RuntimeException failure) {
        failingPrevalidation = Objects.requireNonNull(commandType, "commandType");
        effectFailure = Objects.requireNonNull(failure, "failure");
    }

    List<EffectCommand> stagedCommands() {
        return List.copyOf(staged);
    }

    List<EffectCommand> committedCommands() {
        return List.copyOf(committed);
    }

    int prepareCalls() {
        return prepareCalls;
    }

    int commitCalls() {
        return commitCalls;
    }

    @Override
    public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {
        if (failingPrevalidation != null && failingPrevalidation.isInstance(command)) {
            throw effectFailure;
        }
        staged.add(command);
    }

    @Override
    public PreparedCommit prepare(List<EffectCommand> commands, InvocationProvenance provenance) {
        prepareCalls++;
        var prepared = List.copyOf(commands);
        return () -> {
            committed.addAll(prepared);
            commitCalls++;
        };
    }
}
