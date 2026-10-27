package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class PlayerModelVariant {
    private final GeoModel mainModel;
    private final @Nullable GeoModel armModel;

    public PlayerModelVariant(GeoModel mainModel, @Nullable GeoModel armModel) {
        this.mainModel = Objects.requireNonNull(mainModel, "mainModel");
        this.armModel = armModel;
    }

    public GeoModel mainModel() {
        return mainModel;
    }

    public @Nullable GeoModel armModel() {
        return armModel;
    }

}
