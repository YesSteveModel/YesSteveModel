package com.elfmcys.ysm.api.molang;

/** Captures exact targets and immutable values before submission, never the borrowed context or event. */
@FunctionalInterface
public interface PreparedEffect extends EffectRequest {
    /** May run repeatedly; performs fallible checks without producing externally visible effects. */
    default void validate() {}

    void commit();
}
