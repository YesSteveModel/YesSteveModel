package com.elfmcys.ysm.expression.host;

import java.util.ArrayList;
import java.util.List;

/** Controlled prevalidation and commit observations for transaction tests. */
public final class ControlledHostFixture implements EffectValidator, EffectSink {
    private final ArrayList<EffectCommand> committedEffects = new ArrayList<>();
    private HostSymbol failingSymbol;
    private RuntimeException effectFailure;
    private int prepareCalls;
    private int commitCalls;
    private InvocationProvenance committedProvenance;

    public void failEffect(HostSymbol symbol, RuntimeException failure) {
        failingSymbol = symbol;
        effectFailure = failure;
    }

    public int prepareCalls() {
        return prepareCalls;
    }

    public int commitCalls() {
        return commitCalls;
    }

    public List<EffectCommand> committedEffects() {
        return List.copyOf(committedEffects);
    }

    public InvocationProvenance committedProvenance() {
        return committedProvenance;
    }

    @Override
    public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {
        HostSymbol symbol;
        if (command instanceof EffectCommand.Sound value) {
            symbol = value.action();
        } else if (command instanceof EffectCommand.Particle value) {
            symbol = value.absolute() ? HostSymbol.YSM_ABS_PARTICLE : HostSymbol.YSM_PARTICLE;
        } else if (command instanceof EffectCommand.Controller value) {
            symbol = value.action();
        } else if (command instanceof EffectCommand.Deferred) {
            symbol = HostSymbol.YSM_DEFER;
        } else if (command instanceof EffectCommand.Sync) {
            symbol = HostSymbol.YSM_SYNC;
        } else {
            throw new IllegalArgumentException("unknown effect command " + command);
        }
        if (symbol == failingSymbol) {
            throw effectFailure;
        }
    }

    @Override
    public PreparedCommit prepare(List<EffectCommand> commands, InvocationProvenance provenance) {
        prepareCalls++;
        var prepared = List.copyOf(commands);
        return () -> {
            committedEffects.addAll(prepared);
            committedProvenance = provenance;
            commitCalls++;
        };
    }
}
