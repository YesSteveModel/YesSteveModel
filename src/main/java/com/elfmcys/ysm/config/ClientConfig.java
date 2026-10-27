package com.elfmcys.ysm.config;

import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.network.session.SessionMode;
import net.minecraftforge.common.ForgeConfigSpec;

public class ClientConfig {
    public static ForgeConfigSpec.BooleanValue DISCLAIMER_SHOW;
    public static ForgeConfigSpec.BooleanValue PRINT_ANIMATION_ROULETTE_MSG;
    public static ForgeConfigSpec.BooleanValue DISABLE_SELF_MODEL;
    public static ForgeConfigSpec.BooleanValue DISABLE_OTHER_MODEL;
    public static ForgeConfigSpec.BooleanValue DISABLE_SELF_HANDS;
    public static ForgeConfigSpec.BooleanValue DISABLE_PROJECTILE_MODEL;
    public static ForgeConfigSpec.BooleanValue DISABLE_VEHICLE_MODEL;
    public static ForgeConfigSpec.BooleanValue DISABLE_EXTERNAL_FIRST_PERSON_ANIM;
    public static ForgeConfigSpec.BooleanValue USE_COMPATIBILITY_RENDERER;
    public static ForgeConfigSpec.BooleanValue MODEL_SCREEN_EAGER_LOADING;
    public static ForgeConfigSpec.IntValue MODEL_RETENTION_COUNT;
    public static ForgeConfigSpec.DoubleValue SOUND_VOLUME;
    public static ForgeConfigSpec.BooleanValue SHOW_MODEL_ID_FIRST;
    public static ForgeConfigSpec.BooleanValue ENABLE_SOPHISTICATED_BACKPACK_COMPAT;
    public static ForgeConfigSpec.BooleanValue ENABLE_PARCOOL_COMPAT;
    public static ForgeConfigSpec.EnumValue<SessionMode> NETWORK_SESSION_MODE;

    public static ForgeConfigSpec init() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        init(builder);
        ExtraPlayerScreenConfig.init(builder);
        LoadingStateScreenConfig.init(builder);
        return builder.build();
    }

    public static void init(ForgeConfigSpec.Builder builder) {
        init(builder, NativeRuntime.deviceQuality());
    }

    static void init(ForgeConfigSpec.Builder builder, NativeRuntime.DeviceQuality deviceQuality) {
        var highEndDevice = deviceQuality == NativeRuntime.DeviceQuality.HIGH;
        builder.push("general");

        builder.comment("Whether to display disclaimer GUI");
        DISCLAIMER_SHOW = builder.define("DisclaimerShow", true);

        builder.comment("Whether to print animation roulette play message");
        PRINT_ANIMATION_ROULETTE_MSG = builder.define("PrintAnimationRouletteMsg", false);

        builder.comment("Prevents rendering of self player's model");
        DISABLE_SELF_MODEL = builder.define("DisableSelfModel", false);

        builder.comment("Prevents rendering of other player's model");
        DISABLE_OTHER_MODEL = builder.define("DisableOtherModel", false);

        builder.comment("Prevents rendering of self player's hand");
        DISABLE_SELF_HANDS = builder.define("DisableSelfHands", false);

        builder.comment("Prevents rendering of projectile model");
        DISABLE_PROJECTILE_MODEL = builder.define("DisableProjectileModel", false);

        builder.comment("Prevents rendering of vehicle model");
        DISABLE_VEHICLE_MODEL = builder.define("DisableVehicleModel", false);

        builder.comment("Disable first person animation from other mods.");
        DISABLE_EXTERNAL_FIRST_PERSON_ANIM = builder.define("DisableExternalFirstPersonAnim", false);

        builder.comment("If rendering errors occur, try turning on this.");
        USE_COMPATIBILITY_RENDERER = builder.define("UseCompatibilityRenderer", false);

        builder.comment("Automatically prepare locally available models for the model selection screen.");
        MODEL_SCREEN_EAGER_LOADING = builder.define("ModelScreenEagerLoading", highEndDevice);

        builder.comment("Maximum number of unused model render targets retained in memory. Zero disables retention.");
        MODEL_RETENTION_COUNT = builder.defineInRange("ModelRetentionCount",
            highEndDevice ? 30 : 10, 0, Integer.MAX_VALUE);

        builder.comment("The amount of volume when the animation is played.");
        SOUND_VOLUME = builder.defineInRange("SoundVolume", 100.0, 0.0, 100.0);

        builder.comment(
            "Whether to display model ID first in the model selection screen, instead of the model name filled in by the model author.");
        SHOW_MODEL_ID_FIRST = builder.define("ShowModelIdFirst", false);

        builder.pop();

        builder.push("network");
        builder.comment("AUTO uses the game-server YSM session when its channel is present.");
        builder.comment("LOCAL declines the server session and keeps the local catalog.");
        NETWORK_SESSION_MODE = builder.defineEnum("SessionMode", SessionMode.AUTO);
        builder.pop();

        builder.push("Integration");
        ENABLE_SOPHISTICATED_BACKPACK_COMPAT = builder.define("SophisticatedBackpack", true);
        ENABLE_PARCOOL_COMPAT = builder.define("Parcool", true);
        builder.pop();
    }
}
