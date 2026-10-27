package com.elfmcys.ysm.client.animation.debug;

import net.minecraft.network.chat.Component;

public interface DebugSource {
    void print(String message, Object... args);

    void print(Component message);
}
