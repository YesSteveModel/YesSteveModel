package com.elfmcys.ysm.client.model.locator;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.model.ModelKind;
import com.elfmcys.ysm.api.model.event.RegisterModelLocatorEvent;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocator;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import net.minecraft.resources.ResourceLocation;

public class FirstPersonLocator extends GeoLocatorType {
    private static FirstPersonLocator INSTANCE;

    public final GeoLocator leftArm = register("LeftArm");
    public final GeoLocator rightArm = register("RightArm");
    public final GeoLocator background = register("Background");

    @SuppressWarnings("removal")
    private FirstPersonLocator() {
        super(new ResourceLocation(YesSteveModel.MOD_ID, "first_person"));
    }

    public static void init() {
        if (INSTANCE == null) {
            synchronized (FirstPersonLocator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FirstPersonLocator();
                    YesSteveModel
                        .postEvent(new RegisterModelLocatorEvent(ModelKind.HUMANOID_FIRST_PERSON, INSTANCE::register));
                    INSTANCE.freeze();
                }
            }
        }
    }

    public static FirstPersonLocator get() {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        throw new IllegalStateException("PlayerLocator has not been initialized");
    }
}
