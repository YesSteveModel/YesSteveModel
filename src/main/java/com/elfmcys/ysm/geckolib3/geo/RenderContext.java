package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.natives.render.RenderContextType;

/**
 * Animation evaluation context. The immutable bit is resolved by the animatable's whitelist; callers must not treat an
 * unknown scope as immutable.
 */
public record RenderContext(boolean level, boolean irisShadow, boolean firstPersonMod, boolean inventory,
    boolean paperDoll, boolean offScreen, boolean immutable) {
    public static RenderContext levelImmutable() {
        return new RenderContext(true, false, false, false, false, false, true);
    }

    public RenderContext withImmutable(boolean value) {
        if (immutable == value) {
            return this;
        }
        return new RenderContext(level, irisShadow, firstPersonMod, inventory, paperDoll, offScreen, value);
    }

    public RenderContextType nativeType() {
        if (irisShadow) {
            return RenderContextType.IRIS_SHADOW;
        }
        return level || offScreen ? RenderContextType.LEVEL : RenderContextType.GUI;
    }
}
