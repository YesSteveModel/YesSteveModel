package com.elfmcys.ysm.info.roulette.forms;

import java.util.Objects;

/** Stable catalog locator for one model-declared configuration form. */
public record ConfigActionRoute(String buttonId, int formIndex) {

    public static final int MAX_FORM_INDEX = 16_383;

    public ConfigActionRoute {
        if (Objects.requireNonNull(buttonId, "buttonId").isBlank() || buttonId.length() > 128) {
            throw new IllegalArgumentException("buttonId must contain 1..128 characters");
        }
        if (formIndex < 0 || formIndex > MAX_FORM_INDEX) {
            throw new IllegalArgumentException("formIndex must be in 0.." + MAX_FORM_INDEX);
        }
    }

    public String location() {
        return "config/" + buttonId + '/' + formIndex;
    }

    public String labelLocation() {
        return location() + "/label";
    }

    public String labelDiscriminator(String labelName, int labelIndex) {
        if (Objects.requireNonNull(labelName, "labelName").length() > 128 || labelIndex < 0) {
            throw new IllegalArgumentException("invalid config label route");
        }
        return labelName + ':' + labelIndex;
    }
}
