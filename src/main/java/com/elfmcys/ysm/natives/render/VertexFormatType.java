package com.elfmcys.ysm.natives.render;

public enum VertexFormatType {
    FALLBACK(0), VANILLA(1), IRIS_56(2), IRIS_56_AR(3), IRIS_55(4), IRIS_54(5);

    private final int id;

    VertexFormatType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
