package com.elfmcys.ysm.api.rendering.type;

import com.elfmcys.ysm.api.internal.annotation.ParallelInvoke;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;

public interface RenderStateModifier<E, R extends GeoRenderData> {
    @ParallelInvoke("entity")
    void apply(E target, R geoRenderData);
}
