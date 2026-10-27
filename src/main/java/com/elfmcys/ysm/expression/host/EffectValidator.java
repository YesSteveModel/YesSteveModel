package com.elfmcys.ysm.expression.host;

/** Effect validation borrows the round scope synchronously; prepared effects must not retain the scope. */
public interface EffectValidator {
    void prevalidateEffect(EffectCommand command, MolangContext<?> scope);
}
