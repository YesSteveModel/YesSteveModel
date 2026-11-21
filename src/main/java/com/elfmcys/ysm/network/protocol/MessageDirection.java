package com.elfmcys.ysm.network.protocol;

public enum MessageDirection {
    CLIENT_TO_SERVER, SERVER_TO_CLIENT, BOTH;

    public boolean accepts(MessageDirection direction) {
        return this == BOTH || this == direction;
    }
}
