package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;

/** Effect validation borrows the round scope synchronously; prepared effects must not retain the scope. */
public interface EffectValidator {
    void prevalidateEffect(PreparedEffect command, MolangContext<?> scope);
}
