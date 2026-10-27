package com.elfmcys.ysm.client.compat;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.fml.ModList;

public class PlayerAnimatorCompat {
    private static final String MOD_ID = "playeranimator";
    private static boolean INSTALLED;

    public static void init() {
        INSTALLED = ModList.get().isLoaded(MOD_ID);
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static boolean hasThirdPersonModelAnim(AbstractClientPlayer player) {
        if (INSTALLED) {
            var stack = PlayerAnimationAccess.getPlayerAnimLayer(player);
            return stack.getFirstPersonMode(0) == FirstPersonMode.THIRD_PERSON_MODEL; // 最好整个 tick 期间都藏起来
        }
        return false;
    }
}
