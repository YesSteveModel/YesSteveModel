package com.elfmcys.ysm.init;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, YesSteveModel.MOD_ID);

    public static final SoundEvent CUSTOM = registerSound("custom");

    private static SoundEvent registerSound(String name) {
        var ev = SoundEvent.createFixedRangeEvent(new ResourceLocation(YesSteveModel.MOD_ID, name), 16.0F);
        SOUNDS.register(name, () -> ev);
        return ev;
    }
}
