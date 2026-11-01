package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;

import java.util.List;

/**
 * Host effect seam. prepare performs every fallible eligibility check without mutation; PreparedCommit.commit is the
 * single ordered external dispatch boundary.
 */
@FunctionalInterface
public interface EffectSink {
    PreparedCommit prepare(List<PreparedEffect> commands, InvocationProvenance provenance);

    static EffectSink direct() {
        return (effects, provenance) -> () -> effects.forEach(PreparedEffect::commit);
    }

    @FunctionalInterface
    interface PreparedCommit {
        void commit();
    }
}
