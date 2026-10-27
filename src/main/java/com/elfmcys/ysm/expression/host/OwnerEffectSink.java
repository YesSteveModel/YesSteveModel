package com.elfmcys.ysm.expression.host;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single owner-controlled production commit boundary for prevalidated typed effects. */
public final class OwnerEffectSink implements EffectSink {
    private final Owner owner;

    public OwnerEffectSink(Owner owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public PreparedCommit prepare(List<EffectCommand> commands, InvocationProvenance provenance) {
        var prepared = owner.prepare(List.copyOf(commands), provenance);
        var committed = new AtomicBoolean();
        return () -> {
            if (!committed.compareAndSet(false, true)) {
                throw new IllegalStateException("prepared production effects already committed");
            }
            prepared.commit();
        };
    }

    @FunctionalInterface
    public interface Owner {
        PreparedEffects prepare(List<EffectCommand> commands, InvocationProvenance provenance);
    }

    /** Implementations complete all fallible work before returning this handle. */
    @FunctionalInterface
    public interface PreparedEffects {
        void commit();
    }
}
