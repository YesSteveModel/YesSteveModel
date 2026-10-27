package com.elfmcys.ysm.buffer;

public enum BufferType {
    ARRAY(0), NATIVE(1);

    private final int id;

    BufferType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
