package com.elfmcys.ysm.expression.host;

import dev.semaweave.RuntimeValue;

import java.util.List;

/** Test-only entry into package-private generated-host behavior. */
public final class TestHostInvocations {
    private TestHostInvocations() {}

    private static void stage(MolangContext<?> context, EffectCommand command) {
        if (context.isActive())
            context.stage(command);
        else
            context.stageOwner(command);
    }

    public static void defer(MolangContext context, List<RuntimeValue> values) {
        invoke(context, HostSymbol.YSM_DEFER, values);
    }

    public static void invoke(MolangContext context, HostSymbol symbol, List<RuntimeValue> values) {
        if (symbol == HostSymbol.YSM_SYNC || symbol == HostSymbol.YSM_DEFER) {
            float[] floats = new float[values.size()];
            for (int i = 0; i < floats.length; i++)
                floats[i] = values.get(i).asFloat();
            if (symbol == HostSymbol.YSM_SYNC)
                stage(context, new EffectCommand.Sync(EventArguments.sync(floats)));
            else
                stage(context, new EffectCommand.Deferred(EventArguments.deferredFloats(floats)));
        } else if (symbol == HostSymbol.YSM_PLAY_SOUND) {
            var id = values.get(0).tag() == dev.semaweave.ValueTag.STRING
                ? ModelSoundId.string(values.get(0).asString()) : ModelSoundId.numeric(values.get(0).asFloat());
            stage(context, new EffectCommand.Sound(symbol, id, values.get(1).asString(),
                values.size() > 2 ? (int)values.get(2).asFloat() : 0, values.size() > 3 ? values.get(3).asFloat() : 1,
                values.size() > 4 ? values.get(4).asFloat() : 1, false));
        } else if (symbol == HostSymbol.YSM_STOP_ALL_SOUNDS) {
            stage(context,
                new EffectCommand.Sound(symbol, null, "", 0, 1, 1, !values.isEmpty() && values.get(0).asBoolean()));
        } else if (symbol == HostSymbol.YSM_PARTICLE || symbol == HostSymbol.YSM_ABS_PARTICLE) {
            float[] floats = new float[values.size() - 1];
            for (int i = 0; i < floats.length; i++)
                floats[i] = values.get(i + 1).asFloat();
            stage(context,
                new EffectCommand.Particle(symbol == HostSymbol.YSM_ABS_PARTICLE, values.get(0).asString(), floats));
        } else if (symbol == HostSymbol.CTRL_SET_ANIMATION) {
            stage(context, new EffectCommand.Controller(symbol, values.get(0).asString(),
                values.size() > 1 ? values.get(1).asFloat() : 0, 0, 0));
        } else
            throw new AssertionError("unsupported test action " + symbol);
    }
}
