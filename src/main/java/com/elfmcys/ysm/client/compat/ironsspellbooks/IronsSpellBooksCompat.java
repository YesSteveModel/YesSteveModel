package com.elfmcys.ysm.client.compat.ironsspellbooks;

import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

public class IronsSpellBooksCompat {
    private static final String ID = "irons_spellbooks";
    private static boolean IS_LOADED = false;

    public static void init() {
        IS_LOADED = ModList.get().isLoaded(ID);
    }

    public static boolean isInstalled() {
        return IS_LOADED;
    }

    @Nullable
    public static PlayState playAnimation(AnimationEvent<CustomHumanoidEntity<?>> event, LivingEntity entity) {
        if (IS_LOADED) {
            return IronsSpellBooksCompatInner.playAnimation(event, entity);
        }
        return null;
    }

}
