package com.elfmcys.ysm.client.model.locator;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.model.ModelKind;
import com.elfmcys.ysm.api.model.event.RegisterModelLocatorEvent;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocator;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import net.minecraft.resources.ResourceLocation;

public class VehicleLocator extends GeoLocatorType {
    private static VehicleLocator INSTANCE;

    public final GeoLocator passenger = register("PassengerLocator");

    @SuppressWarnings("removal")
    private VehicleLocator() {
        super(new ResourceLocation(YesSteveModel.MOD_ID, "vehicle"));
    }

    public static void init() {
        if (INSTANCE == null) {
            synchronized (VehicleLocator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new VehicleLocator();
                    YesSteveModel.postEvent(new RegisterModelLocatorEvent(ModelKind.VEHICLE, INSTANCE::register));
                    INSTANCE.freeze();
                }
            }
        }
    }

    public static VehicleLocator get() {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        throw new IllegalStateException("VehicleLocator has not been initialized");
    }
}
