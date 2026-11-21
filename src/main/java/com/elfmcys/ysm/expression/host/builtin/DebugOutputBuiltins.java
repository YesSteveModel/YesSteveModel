package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.client.animation.debug.DebugSource;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import java.util.ArrayDeque;
import net.minecraft.world.entity.Entity;

@BuiltinComponent("query")
public final class DebugOutputBuiltins implements EntityComponent<Entity> {
    private final ArrayDeque<StringBuilder> messages = new ArrayDeque<>();
    private DebugSource debug;

    public DebugOutputBuiltins(AnimatableEntity<Entity> owner) {}

    @Override
    public void beginEval(AnimationEvent<?> event) {
        debug = event.getDebugSource();
    }

    @Override
    public void endEval() {
        messages.clear();
        debug = null;
    }

    @BuiltinFunction(value = "debug_output_enabled", alwaysOrigin = true)
    public boolean enabled() {
        return debug != null;
    }

    @BuiltinFunction(value = "debug_output_begin", alwaysOrigin = true, action = true)
    public void begin() {
        messages.push(new StringBuilder());
    }

    @BuiltinFunction(value = "debug_output_append", alwaysOrigin = true, action = true)
    public void append(String value) {
        if (!messages.isEmpty())
            messages.peek().append(value);
    }

    @BuiltinFunction(value = "debug_output_append", alwaysOrigin = true, action = true)
    public void append(float value) {
        if (!messages.isEmpty())
            messages.peek().append(value);
    }

    @BuiltinFunction(value = "debug_output_append", alwaysOrigin = true, action = true)
    public void append(boolean value) {
        if (!messages.isEmpty())
            messages.peek().append(value);
    }

    @BuiltinFunction(value = "debug_output_end", alwaysOrigin = true, action = true)
    public void end() {
        if (!messages.isEmpty() && debug != null)
            debug.print("%s", messages.pop());
    }

    @BuiltinFunction(value = "debug_output", alwaysOrigin = true, action = true, minArgs = 1)
    public void output(int argc, String... values) {
        if (debug != null)
            debug.print("%s", String.join("", values));
    }
}
