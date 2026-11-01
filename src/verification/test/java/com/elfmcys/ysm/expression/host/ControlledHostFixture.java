package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import java.util.ArrayList;
import java.util.List;

/** Controlled prevalidation and commit observations for transaction tests. */
public final class ControlledHostFixture implements EffectValidator, EffectSink {
    private final ArrayList<PreparedEffect> committedEffects = new ArrayList<>();
    private int prepareCalls;
    private int commitCalls;
    private InvocationProvenance committedProvenance;

    public int prepareCalls() {
        return prepareCalls;
    }

    public int commitCalls() {
        return commitCalls;
    }

    public List<PreparedEffect> committedEffects() {
        return List.copyOf(committedEffects);
    }

    public InvocationProvenance committedProvenance() {
        return committedProvenance;
    }

    @Override
    public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {
        command.validate();
    }

    @Override
    public PreparedCommit prepare(List<PreparedEffect> commands, InvocationProvenance provenance) {
        prepareCalls++;
        var prepared = List.copyOf(commands);
        return () -> {
            committedEffects.addAll(prepared);
            committedProvenance = provenance;
            commitCalls++;
        };
    }
}
