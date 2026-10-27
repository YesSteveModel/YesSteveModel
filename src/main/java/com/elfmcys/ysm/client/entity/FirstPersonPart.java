package com.elfmcys.ysm.client.entity;

import net.minecraft.world.entity.HumanoidArm;

public enum FirstPersonPart {
    LEFT_ARM, RIGHT_ARM, BACKGROUND;

    public static FirstPersonPart forArm(HumanoidArm arm) {
        return arm == HumanoidArm.LEFT ? LEFT_ARM : RIGHT_ARM;
    }
}
