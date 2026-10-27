package com.elfmcys.ysm.model.resource.client.data;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.geckolib3.file.AnimationControllerFile;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record ProjectileModelData(GeoModel geoModel, AnimationStore animations,
    @Nullable AnimationControllerFile controllerFile) implements RenderTargetData {
    public ProjectileModelData {
        Objects.requireNonNull(geoModel, "geoModel");
        Objects.requireNonNull(animations, "animations");
    }
}
