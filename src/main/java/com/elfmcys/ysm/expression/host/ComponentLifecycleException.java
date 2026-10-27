package com.elfmcys.ysm.expression.host;

/** Owner-local declaration failure, independent of program or shared content failures. */
public final class ComponentLifecycleException extends RuntimeException {
    public enum Phase { CONSTRUCT, SET_RENDER_TARGET, BEGIN_EVAL, TICK, UPDATE, END_EVAL }

    private final Class<?> declaration;
    private final Phase phase;

    public ComponentLifecycleException(Class<?> declaration, Phase phase, Throwable cause) {
        super("Component lifecycle failed: declaration=" + declaration.getName() + " phase=" + phase, cause);
        this.declaration = declaration;
        this.phase = phase;
    }

    public Class<?> declaration() {
        return declaration;
    }

    public Phase phase() {
        return phase;
    }
}
