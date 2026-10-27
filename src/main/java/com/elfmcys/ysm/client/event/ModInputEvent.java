package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.util.InputCheckUtil;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = YesSteveModel.MOD_ID)
public class ModInputEvent {
    public static volatile boolean[] KEY_STATES = new boolean[GLFW.GLFW_KEY_LAST + 1];
    public static volatile boolean[] MOUSE_STATES = new boolean[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        if (GLFW.GLFW_KEY_SPACE <= event.getKey() && event.getKey() <= GLFW.GLFW_KEY_LAST) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                KEY_STATES[event.getKey()] = true;
            } else if (event.getAction() == GLFW.GLFW_RELEASE) {
                KEY_STATES[event.getKey()] = false;
            }
        }
    }

    @SubscribeEvent
    public static void onMouseInput(InputEvent.MouseButton.Post event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        if (GLFW.GLFW_MOUSE_BUTTON_1 <= event.getButton() && event.getButton() <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                MOUSE_STATES[event.getButton()] = true;
            } else if (event.getAction() == GLFW.GLFW_RELEASE) {
                MOUSE_STATES[event.getButton()] = false;
            }
        }
    }
}
