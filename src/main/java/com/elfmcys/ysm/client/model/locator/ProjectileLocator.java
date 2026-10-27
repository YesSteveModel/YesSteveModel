package com.elfmcys.ysm.client.model.locator;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.model.ModelKind;
import com.elfmcys.ysm.api.model.event.RegisterModelLocatorEvent;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import net.minecraft.resources.ResourceLocation;

public class ProjectileLocator extends GeoLocatorType {
    private static ProjectileLocator INSTANCE;

    @SuppressWarnings("removal")
    private ProjectileLocator() {
        super(new ResourceLocation(YesSteveModel.MOD_ID, "projectile"));
    }

    public static void init() {
        if (INSTANCE == null) {
            synchronized (ProjectileLocator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ProjectileLocator();
                    YesSteveModel.postEvent(new RegisterModelLocatorEvent(ModelKind.PROJECTILE, INSTANCE::register));
                    INSTANCE.freeze();
                }
            }
        }
    }

    public static ProjectileLocator get() {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        throw new IllegalStateException("ProjectileLocator has not been initialized");
    }
}
