package com.elfmcys.ysm.expression.host;

import dev.semaweave.EffectId;

/** Stable semantic effects carried by registry and artifact identity. */
public enum EffectKind {
    CONTEXT_STATE("ysm:context-state"), SOUND("ysm:sound"), PARTICLE("ysm:particle"),
    CONTROLLER("ysm:controller-mutation"), DEFER("ysm:defer"), SYNC("ysm:sync"), RANDOM_STATE("ysm:random-state"),
    PHYSICS_STATE("ysm:physics-state");

    private final EffectId id;

    EffectKind(String canonical) {
        id = new EffectId(canonical);
    }

    public EffectId id() {
        return id;
    }
}
