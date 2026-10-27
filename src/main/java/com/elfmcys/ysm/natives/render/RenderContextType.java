package com.elfmcys.ysm.natives.render;

public enum RenderContextType {
    LEVEL(0), IRIS_SHADOW(1), GUI(2);

    private final int id;

    RenderContextType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
