package com.elfmcys.ysm.expression.host;

import dev.semaweave.registry.JavaMemberBinding;
import java.util.Optional;
import net.minecraft.world.entity.Entity;

/** Command and diagnostic projection of the common linked declaration; never a registry input. */
public record BuiltinDescriptor(BuiltinSurface surface, JavaMemberBinding binding,
    Class<? extends Entity> subjectType) {
    public boolean available() {
        return true;
    }

    public String stableId() {
        return surface.stableId();
    }

    public Optional<String> providerId() {
        return binding.providerId();
    }

    public Class<?> componentClass() {
        return binding.owner();
    }
}
