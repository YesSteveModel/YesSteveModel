package com.elfmcys.ysm.model.resource.client;

/** The input closure is independent of acquisition policy and subject specialization. */
public enum ModelResourcePurpose {
    FULL("full-v1"), GUI("gui-closure-v1");

    private final String ruleId;

    ModelResourcePurpose(String ruleId) {
        this.ruleId = ruleId;
    }

    public String ruleId() {
        return ruleId;
    }

    public boolean isGui() {
        return this == GUI;
    }
}
