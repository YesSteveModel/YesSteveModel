package com.elfmcys.ysm.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class LoadingStateScreenConfig {
    public static ForgeConfigSpec.BooleanValue DISABLE_LOADING_STATE_SCREEN;
    public static ForgeConfigSpec.EnumValue<Position> LOADING_STATE_POSITION;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("loading_state_screen");

        builder.comment("Whether to disable loading state screen");
        DISABLE_LOADING_STATE_SCREEN = builder.define("DisableLoadingStateScreen", false);

        builder.comment("Loading state screen position");
        LOADING_STATE_POSITION = builder.defineEnum("LoadingStatePosition", Position.TOP_CENTER);

        builder.pop();
    }

    public enum Position {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }
}
