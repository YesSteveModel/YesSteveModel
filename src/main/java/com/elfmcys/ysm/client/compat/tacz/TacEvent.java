package com.elfmcys.ysm.client.compat.tacz;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunMeleeEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class TacEvent {
    @SubscribeEvent
    public void onGunShoot(GunFireEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        LivingEntity shooter = event.getShooter();
        shooter.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            cap.waitForAsyncUpdate();
            cap.setTacGunAnimationNeedReload(true);
        });
    }

    @SubscribeEvent
    public void onGunShoot(GunMeleeEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        LivingEntity shooter = event.getShooter();
        shooter.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            cap.waitForAsyncUpdate();
            cap.setTacGunAnimationNeedReload(true);
        });
    }

    @SubscribeEvent
    public void onGunShoot(GunReloadEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        LivingEntity shooter = event.getEntity();
        shooter.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            cap.waitForAsyncUpdate();
            cap.setTacGunAnimationNeedReload(true);
        });
    }
}
