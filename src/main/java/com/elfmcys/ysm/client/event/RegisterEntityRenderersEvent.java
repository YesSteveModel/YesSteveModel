package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.compat.backpack.sophisticated.SophisticatedCompat;
import com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer;
import com.elfmcys.ysm.client.renderer.CustomPlayerRenderer;
import com.elfmcys.ysm.client.renderer.CustomProjectileRenderer;
import com.elfmcys.ysm.client.renderer.CustomVehicleRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class RegisterEntityRenderersEvent {
    private static CustomPlayerRenderer CUSTOM_PLAYER_RENDERER;
    private static CustomProjectileRenderer CUSTOM_PROJECTILE_RENDERER;
    private static CustomFirstPersonArmRenderer CUSTOM_FIRST_PERSON_RENDERER;
    private static CustomVehicleRenderer CUSTOM_VEHICLE_RENDERER;

    private static void init(ResourceManager resourceManager) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        ItemInHandRenderer itemInHandRenderer = dispatcher.getItemInHandRenderer();
        EntityModelSet entityModels = Minecraft.getInstance().getEntityModels();
        Font font = Minecraft.getInstance().font;
        EntityRendererProvider.Context context = new EntityRendererProvider.Context(dispatcher, itemRenderer,
            blockRenderer, itemInHandRenderer, resourceManager, entityModels, font);

        CUSTOM_PLAYER_RENDERER = new CustomPlayerRenderer(context);
        CUSTOM_PROJECTILE_RENDERER = new CustomProjectileRenderer(context);
        releaseFirstPersonArm();
        CUSTOM_FIRST_PERSON_RENDERER = new CustomFirstPersonArmRenderer();
        CUSTOM_VEHICLE_RENDERER = new CustomVehicleRenderer(context);

        SophisticatedCompat.addLayer();
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        event.addListener((ResourceManagerReloadListener)RegisterEntityRenderersEvent::init);
    }

    public static CustomPlayerRenderer getPlayerRenderer() {
        if (CUSTOM_PLAYER_RENDERER == null) {
            init(Minecraft.getInstance().getResourceManager());
        }
        return CUSTOM_PLAYER_RENDERER;
    }

    public static CustomProjectileRenderer getProjectRenderer() {
        if (CUSTOM_PROJECTILE_RENDERER == null) {
            init(Minecraft.getInstance().getResourceManager());
        }
        return CUSTOM_PROJECTILE_RENDERER;
    }

    public static CustomFirstPersonArmRenderer getFirstPersonArmRenderer() {
        if (CUSTOM_FIRST_PERSON_RENDERER == null) {
            init(Minecraft.getInstance().getResourceManager());
        }
        return CUSTOM_FIRST_PERSON_RENDERER;
    }

    public static void releaseFirstPersonArm() {
        RenderFirstPlayerBackground.reset();
        if (CUSTOM_FIRST_PERSON_RENDERER != null) {
            CUSTOM_FIRST_PERSON_RENDERER.release();
        }
    }

    public static void releaseFirstPersonArm(LocalPlayer player) {
        RenderFirstPlayerBackground.reset();
        if (CUSTOM_FIRST_PERSON_RENDERER != null) {
            CUSTOM_FIRST_PERSON_RENDERER.releaseForPlayer(player);
        }
    }

    public static CustomVehicleRenderer getVehicleRenderer() {
        if (CUSTOM_VEHICLE_RENDERER == null) {
            init(Minecraft.getInstance().getResourceManager());
        }
        return CUSTOM_VEHICLE_RENDERER;
    }
}
