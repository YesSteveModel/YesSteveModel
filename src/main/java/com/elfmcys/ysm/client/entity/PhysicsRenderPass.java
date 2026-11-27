package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.geckolib3.geo.RenderContext;

public enum PhysicsRenderPass {
    PURE_ANIMATION_ONLY, WORLD_OBSERVER, CANONICAL;

    public static PhysicsRenderPass classify(RenderContext context) {
        if (!context.level() || context.inventory() || context.paperDoll() || context.firstPersonMod())
            return PURE_ANIMATION_ONLY;
        return context.irisShadow() || context.offScreen() ? WORLD_OBSERVER : CANONICAL;
    }
}
