package com.elfmcys.ysm.client.compat.bettercombat;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.commons.lang3.StringUtils;

public class BetterCombatCompat {
    private static final String MOD_ID = "bettercombat";
    private static boolean INSTALLED;

    public static void init() {
        if (LoadingModList.get().getModFileById(MOD_ID) != null) {
            BetterCombatCompatInner.innerInit();
            INSTALLED = true;
        }
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static String getAttackAnimation(AbstractClientPlayer player) {
        return INSTALLED ? BetterCombatCompatInner.getAttackAnimation(player) : StringUtils.EMPTY;
    }

    /**
     * Attack animation of one borrowed render entity, or the empty string when this entity is not a client player or
     * the adapter is unavailable.
     *
     * <p>
     * This is the capability-facing form: it owns the entity narrowing so a caller never has to reach the optional
     * adapter itself.
     */
    public static String attackAnimation(net.minecraft.world.entity.Entity entity) {
        return entity instanceof AbstractClientPlayer player ? getAttackAnimation(player) : StringUtils.EMPTY;
    }

}
