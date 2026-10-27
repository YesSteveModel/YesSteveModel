package com.elfmcys.ysm.expression.host;

import java.util.List;

/**
 * Host effect seam. prepare performs every fallible eligibility check without mutation; PreparedCommit.commit is the
 * single ordered, non-throwing dispatch boundary.
 */
@FunctionalInterface
public interface EffectSink {
    PreparedCommit prepare(List<EffectCommand> commands, InvocationProvenance provenance);

    @FunctionalInterface
    interface PreparedCommit {
        void commit();
    }
}
