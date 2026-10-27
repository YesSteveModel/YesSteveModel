package com.elfmcys.ysm.expression.host;

/** Marker ABI for an invocation-local, entity-owned read view. */
public interface EntityView {
    default net.minecraft.world.entity.Entity entity() {
        throw new IllegalStateException("this host view has no Minecraft entity");
    }

    /**
     * This view as a production input source, when it is one.
     *
     * <p>
     * A declaration method that implements its own business reads the borrowed production inputs here rather than
     * through a cast, so a host or test view that is not a production source stays valid and simply offers none.
     */
    default InvocationInputs<?> productionInputs() {
        return null;
    }
}
