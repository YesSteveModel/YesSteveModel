package com.elfmcys.ysm.client.model.locator;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.model.ModelKind;
import com.elfmcys.ysm.api.model.event.RegisterModelLocatorEvent;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocator;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import net.minecraft.resources.ResourceLocation;

public class PlayerLocator extends GeoLocatorType {
    private static PlayerLocator INSTANCE;

    public final GeoLocator head = register("Head");
    public final GeoLocator leftHand = register("LeftHandLocator");
    public final GeoLocator rightHand = register("RightHandLocator");
    public final GeoLocator backpack = register("BackpackLocator");
    public final GeoLocator elytra = register("ElytraLocator");
    public final GeoLocator pistol = register("PistolLocator");
    public final GeoLocator rifle = register("RifleLocator");
    public final GeoLocator leftWaist = register("LeftWaistLocator");
    public final GeoLocator rightWaist = register("RightWaistLocator");
    public final GeoLocator leftShoulder = register("LeftShoulderLocator");
    public final GeoLocator rightShoulder = register("RightShoulderLocator");
    public final GeoLocator blade = register("BladeLocator");
    public final GeoLocator sheath = register("SheathLocator");

    @SuppressWarnings("removal")
    private PlayerLocator() {
        super(new ResourceLocation(YesSteveModel.MOD_ID, "player"));
    }

    public static void init() {
        if (INSTANCE == null) {
            synchronized (PlayerLocator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new PlayerLocator();
                    YesSteveModel.postEvent(new RegisterModelLocatorEvent(ModelKind.HUMANOID_FULL, INSTANCE::register));
                    INSTANCE.freeze();
                }
            }
        }
    }

    public static PlayerLocator get() {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        throw new IllegalStateException("PlayerLocator has not been initialized");
    }
}
