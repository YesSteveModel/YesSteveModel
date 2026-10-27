package com.elfmcys.ysm.util;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.settings.KeyModifier;

public class InputCheckUtil {
    @SuppressWarnings("removal")
    public static boolean keyIsMatch(InputEvent.Key event, KeyMapping keyMapping) {
        return keyMapping.matches(event.getKey(), event.getScanCode())
            && keyMapping.getKeyModifier().equals(KeyModifier.getActiveModifier());
    }

    public static boolean isInGame() {
        Minecraft mc = Minecraft.getInstance();
        // 不能是加载界面
        if (mc.getOverlay() != null) {
            return false;
        }
        // 不能打开任何 GUI
        if (mc.screen != null) {
            return false;
        }
        // 当前窗口捕获鼠标操作
        if (!mc.mouseHandler.isMouseGrabbed()) {
            return false;
        }
        // 选择了当前窗口
        return mc.isWindowActive();
    }
}
