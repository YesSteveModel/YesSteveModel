package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
@SuppressWarnings("removal")
public class VanillaPlayerRenderEvent {
    private static final ResourceLocation STEVE_SKIN_LOCATION =
        new ResourceLocation("textures/entity/player/wide/steve.png");
    private static final ResourceLocation ALEX_SKIN_LOCATION =
        new ResourceLocation("textures/entity/player/slim/alex.png");
    private static final String STEVE = "misc/2_steve";
    private static final String ALEX = "misc/1_alex";

    @SubscribeEvent
    public static void onRenderPlayer(SpecialPlayerRenderEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        Player player = event.getPlayer();
        if (isVanillaPlayer(event.getModelId()) && player instanceof AbstractClientPlayer) {
            AbstractClientPlayer clientPlayer = (AbstractClientPlayer)player;
            ResourceLocation location;
            Minecraft minecraft = Minecraft.getInstance();
            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map =
                minecraft.getSkinManager().getInsecureSkinInformation(clientPlayer.getGameProfile());
            if (map.containsKey(MinecraftProfileTexture.Type.SKIN)) {
                location = minecraft.getSkinManager().registerTexture(map.get(MinecraftProfileTexture.Type.SKIN),
                    MinecraftProfileTexture.Type.SKIN);
            } else {
                location = getDefaultSkin(event.getModelId());
            }
            event.setTextureLocationOverride(location);
        }
    }

    private static boolean isVanillaPlayer(String modelId) {
        return modelId.equals(STEVE) || modelId.equals(ALEX);
    }

    private static ResourceLocation getDefaultSkin(String modelId) {
        return modelId.equals(STEVE) ? STEVE_SKIN_LOCATION : ALEX_SKIN_LOCATION;
    }
}
