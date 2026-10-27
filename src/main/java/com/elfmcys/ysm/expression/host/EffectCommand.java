package com.elfmcys.ysm.expression.host;

import java.util.Objects;

/** Typed commands staged during one logical action/update transaction. */
public sealed interface EffectCommand {
    record Sound(HostSymbol action, ModelSoundId soundId, String name, int mode, float volume, float pitch,
        boolean global) implements EffectCommand {
        public Sound {
            requireEffect(action, EffectKind.SOUND);
            if ((action == HostSymbol.YSM_STOP_ALL_SOUNDS) == (soundId != null)) {
                throw new IllegalArgumentException("sound id does not match sound action");
            }
            Objects.requireNonNull(name, "name");
        }
    }

    final class Particle implements EffectCommand {
        private final boolean absolute;
        private final String name;
        private final float[] arguments;

        public Particle(boolean absolute, String name, float[] arguments) {
            this.absolute = absolute;
            this.name = Objects.requireNonNull(name, "name");
            if (arguments.length > 254)
                throw new IllegalArgumentException("particle accepts at most 254 values");
            this.arguments = arguments.clone();
            for (float value : this.arguments)
                requireFinite(value);
        }

        public boolean absolute() {
            return absolute;
        }

        public String name() {
            return name;
        }

        public int size() {
            return arguments.length;
        }

        public float argument(int index, float fallback) {
            return index < arguments.length ? arguments[index] : fallback;
        }
    }

    record Controller(HostSymbol action, String name, float first, float second, float third) implements EffectCommand {
        public Controller {
            requireEffect(action, EffectKind.CONTROLLER);
            Objects.requireNonNull(name, "name");
            requireFinite(first);
            requireFinite(second);
            requireFinite(third);
        }
    }

    record Deferred(EventArguments arguments) implements EffectCommand {
        public Deferred {
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    record Sync(EventArguments arguments) implements EffectCommand {
        public Sync {
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    private static void requireEffect(HostSymbol symbol, EffectKind effect) {
        Objects.requireNonNull(symbol, "symbol");
        if (!symbol.effects().contains(effect) || !symbol.stagedEffect()) {
            throw new IllegalArgumentException("symbol is not a staged " + effect + " command");
        }
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value))
            throw new IllegalArgumentException("non-finite effect argument");
    }
}
