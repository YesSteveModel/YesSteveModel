package com.elfmcys.ysm.client.compat.carryon;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.geckolib3.core.controller.HybridAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import java.util.Optional;
import java.util.function.BiFunction;

public class CarryOnCompat {
    private static final String CARRY_ON_ID = "carryon";
    private static boolean INSTALLED = false;

    public static void init() {
        INSTALLED = ModList.get().isLoaded(CARRY_ON_ID);
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static Optional<BiFunction<String, CustomPlayerEntity, IAnimationController<CustomPlayerEntity>>>
        animationPredicate() {
        if (INSTALLED) {
            return Optional
                .of((name, entity) -> new HybridAnimationController<>(entity, name, 0.1f, new CarryOnPredicate()));
        } else {
            return Optional.empty();
        }
    }

    public static boolean isCarryOnPrincess(Player player) {
        return isCarryOnPrincess((LivingEntity)player);
    }

    public static boolean isCarryOnPrincess(LivingEntity entity) {
        if (INSTALLED) {
            return CarryOnInnerCompat.isCarryOnPrincess(entity);
        }
        return false;
    }

    /**
     * Whether one borrowed entity is the Carry On princess, or {@code false} when this entity is not a living entity or
     * the adapter is unavailable.
     *
     * <p>
     * This is the capability-facing form: it owns the living-entity narrowing so a caller never has to reach the
     * optional adapter itself.
     */
    public static boolean carryOnPrincess(net.minecraft.world.entity.Entity entity) {
        return entity instanceof LivingEntity living && isCarryOnPrincess(living);
    }

}
