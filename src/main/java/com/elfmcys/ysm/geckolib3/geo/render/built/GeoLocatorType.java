package com.elfmcys.ysm.geckolib3.geo.render.built;

import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

public class GeoLocatorType {
    private final Object2ReferenceOpenHashMap<String, GeoLocator> nameMap = new Object2ReferenceOpenHashMap<>(16);
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private final ResourceLocation id;

    protected GeoLocatorType(ResourceLocation id) {
        this.id = id;
        GeoLocatorTypeRegistry.register(this);
    }

    protected GeoLocator register(String name) {
        if (nameMap.size() == 255) {
            throw new IllegalStateException("Cannot register more than 255 locator");
        }
        if (frozen.get()) {
            throw new IllegalStateException("Registry is already frozen");
        }
        return nameMap.computeIfAbsent(name, n -> new GeoLocator(this, name, (byte)(nameMap.size() + 1)));
    }

    public int size() {
        return nameMap.size();
    }

    @Nullable
    public GeoLocator getByBoneName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        var suffixStart = name.length();
        while (suffixStart > 0 && Character.isDigit(name.charAt(suffixStart - 1))) {
            suffixStart--;
        }
        if (suffixStart > 0 && suffixStart < name.length()) {
            name = name.substring(0, suffixStart);
        }
        return nameMap.get(name);
    }

    protected void freeze() {
        frozen.setRelease(true);
    }

    public ResourceLocation id() {
        return id;
    }
}
