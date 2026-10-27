package com.elfmcys.ysm;

import java.util.Objects;
import java.util.function.Supplier;

/** Retains one completed selection across model-system lifetimes. */
public final class ProcessAssetPaths {
    private final Supplier<AssetPaths> factory;
    private AssetPaths selected;

    public ProcessAssetPaths(Supplier<AssetPaths> factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    public synchronized AssetPaths get() {
        if (selected == null) selected = Objects.requireNonNull(factory.get());
        return selected;
    }
}
