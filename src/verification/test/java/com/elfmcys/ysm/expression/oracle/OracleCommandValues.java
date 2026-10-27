package com.elfmcys.ysm.expression.oracle;

import com.elfmcys.ysm.expression.host.EffectCommand;
import com.elfmcys.ysm.expression.host.HostSymbol;
import com.elfmcys.ysm.expression.host.ModelSoundId;
import dev.semaweave.RuntimeValue;
import dev.semaweave.RuntimeValues;
import it.unimi.dsi.fastutil.floats.FloatList;
import java.util.ArrayList;
import java.util.List;

/** Projects typed commands into the independent oracle's historical value vocabulary. */
final class OracleCommandValues {
    private OracleCommandValues() {}

    static List<RuntimeValue> arguments(EffectCommand command) {
        if (command instanceof EffectCommand.Sound sound) {
            if (sound.action() == HostSymbol.YSM_STOP_ALL_SOUNDS)
                return List.of(RuntimeValues.ofBoolean(sound.global()));
            RuntimeValue id =
                sound.soundId() instanceof ModelSoundId.Numeric numeric ? RuntimeValues.ofFloat(numeric.value())
                    : RuntimeValues.ofString(((ModelSoundId.StringKey)sound.soundId()).value());
            if (sound.action() == HostSymbol.YSM_STOP_SOUND)
                return List.of(id, RuntimeValues.ofBoolean(sound.global()));
            return List.of(id, RuntimeValues.ofString(sound.name()), RuntimeValues.ofFloat(sound.mode()),
                RuntimeValues.ofFloat(sound.volume()), RuntimeValues.ofFloat(sound.pitch()));
        }
        if (command instanceof EffectCommand.Particle value) {
            var result = new ArrayList<RuntimeValue>();
            result.add(RuntimeValues.ofString(value.name()));
            for (int i = 0; i < value.size(); i++)
                result.add(RuntimeValues.ofFloat(value.argument(i, 0)));
            return List.copyOf(result);
        }
        if (command instanceof EffectCommand.Controller value)
            return switch (value.action()) {
                case CTRL_SET_ANIMATION, YSM_BONE_GLOW, YSM_BONE_TRANSPARENCY ->
                    List.of(RuntimeValues.ofString(value.name()), RuntimeValues.ofFloat(value.first()));
                case YSM_BONE_COLOR ->
                    List.of(RuntimeValues.ofString(value.name()), RuntimeValues.ofFloat(value.first()),
                        RuntimeValues.ofFloat(value.second()), RuntimeValues.ofFloat(value.third()));
                case CTRL_SET_BEGINNING_TRANSITION_LENGTH -> List.of(RuntimeValues.ofFloat(value.first()));
                case CTRL_RESET, CTRL_INDICATE_RELOAD -> List.of();
                default -> throw new AssertionError(value.action());
            };
        if (command instanceof EffectCommand.Sync value)
            return floats(value.arguments());
        if (command instanceof EffectCommand.Deferred value)
            return floats(value.arguments());
        throw new AssertionError(command);
    }

    private static List<RuntimeValue> floats(FloatList values) {
        var result = new ArrayList<RuntimeValue>(values.size());
        for (int i = 0; i < values.size(); i++)
            result.add(RuntimeValues.ofFloat(values.getFloat(i)));
        return List.copyOf(result);
    }
}
