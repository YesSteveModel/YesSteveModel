package com.elfmcys.ysm.client.compat.carryon;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import tschipp.carryon.common.carry.CarryOnData;
import tschipp.carryon.common.carry.CarryOnDataManager;

public class CarryOnInnerCompat {
    static boolean isCarryOnPrincess(LivingEntity entity) {
        Entity vehicle = entity.getVehicle();
        return vehicle instanceof Player playerVehicle
            && getCarryOnType(playerVehicle) == CarryOnInnerCompat.Type.PLAYER;
    }

    static CarryOnInnerCompat.Type getCarryOnType(Player player) {
        CarryOnData carry = CarryOnDataManager.getCarryData(player);
        if (carry.isCarrying(CarryOnData.CarryType.BLOCK)) {
            return CarryOnInnerCompat.Type.BLOCK;
        }
        if (carry.isCarrying(CarryOnData.CarryType.ENTITY)) {
            return CarryOnInnerCompat.Type.ENTITY;
        }
        if (carry.isCarrying(CarryOnData.CarryType.PLAYER)) {
            return CarryOnInnerCompat.Type.PLAYER;
        }
        return CarryOnInnerCompat.Type.NONE;
    }

    enum Type {
        ENTITY, BLOCK, PLAYER, NONE
    }
}
