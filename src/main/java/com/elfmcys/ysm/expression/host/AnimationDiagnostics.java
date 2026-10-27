package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

public final class AnimationDiagnostics {
    private AnimationDiagnostics() {}

    public static Level errorLevel(@Nullable InvocationInputs<?> inputs) {
        return errorLevel(inputs instanceof MinecraftRuntimeInputs<?> minecraft
            && minecraft.animationEvent().getDebugSource() != null);
    }

    public static Level errorLevel(boolean animationDebug) {
        return animationDebug ? Level.WARN : Level.DEBUG;
    }
}
