package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.event.ModInputEvent;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.util.InputCheckUtil;
import net.minecraft.world.entity.Entity;
import org.lwjgl.glfw.GLFW;

@BuiltinComponent("ysm")
public final class InputBuiltins implements EntityComponent<Entity> {
    public InputBuiltins(AnimatableEntity<Entity> owner) {}

    @BuiltinFunction(value = "keyboard", alwaysOrigin = true, minArgs = 1)
    public boolean keyboard(MolangContextView<Entity> context, int argc, float... keys) {
        if (!InputCheckUtil.isInGame())
            return false;
        for (float key : keys) {
            int index = (int)key;
            if (GLFW.GLFW_KEY_SPACE <= index && index <= GLFW.GLFW_KEY_LAST && ModInputEvent.KEY_STATES[index])
                return true;
        }
        return false;
    }

    @BuiltinFunction(value = "mouse", alwaysOrigin = true)
    public static boolean mouse(MolangContextView<Entity> context, int button) {
        return InputCheckUtil.isInGame() && button >= GLFW.GLFW_MOUSE_BUTTON_1 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST
            && ModInputEvent.MOUSE_STATES[button];
    }
}
