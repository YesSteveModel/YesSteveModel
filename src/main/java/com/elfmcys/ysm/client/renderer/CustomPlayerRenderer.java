package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.swarfare.SWarfareCompat;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.renderer.layer.CustomParrotOnShoulderLayer;
import com.elfmcys.ysm.client.renderer.layer.CustomPlayerElytraLayer;
import com.elfmcys.ysm.client.renderer.layer.CustomPlayerHeadLayer;
import com.elfmcys.ysm.client.renderer.layer.CustomPlayerItemInHandLayer;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.elfmcys.ysm.geckolib3.geo.GeoReplacedEntityRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.NotNull;

public class CustomPlayerRenderer extends GeoReplacedEntityRenderer<Player, CustomPlayerEntity> {
    private ResourceLocation textureOverride;

    @SuppressWarnings("all")
    public CustomPlayerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        addLayer(new CustomPlayerItemInHandLayer(ctx.getItemInHandRenderer()));
        addLayer(new CustomPlayerElytraLayer(ctx));
        addLayer(new CustomParrotOnShoulderLayer(ctx));
        addLayer(new CustomPlayerHeadLayer(ctx));
    }

    @Override
    @SuppressWarnings("all")
    public void render(Player player, float entityYaw, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight) {
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap != null)
            renderReplacement(cap, entityYaw, partialTick, poseStack, bufferSource, packedLight, () -> {
            });
    }

    public boolean renderReplacement(PlayerAnimatableCapability cap, float entityYaw, float partialTick,
        PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, Runnable handoff) {
        if (!cap.isPresentationAllowed())
            return false;
        if (SWarfareCompat.shouldHidePlayerRender(cap.getEntity())) {
            handoff.run();
            return true;
        }
        var data = cap.update(partialTick);
        return cap.consumePrepared(data, borrowed -> {
            var event = new SpecialPlayerRenderEvent(cap.getEntity(), cap, cap.getInstalledModelId());
            var suppressed = MinecraftForge.EVENT_BUS.post(event);
            if (!cap.canConsume(borrowed, borrowed.ctx))
                return false;
            if (suppressed) {
                handoff.run();
                return true;
            }
            if (MinecraftForge.EVENT_BUS.post(new net.minecraftforge.client.event.RenderLivingEvent.Pre<>(
                cap.getEntity(), this, partialTick, poseStack, bufferSource, packedLight))) {
                if (!cap.canConsume(borrowed, borrowed.ctx))
                    return false;
                handoff.run();
                return true;
            }
            if (!cap.canConsume(borrowed, borrowed.ctx))
                return false;
            handoff.run();
            textureOverride = event.getTextureLocationOverride();
            try {
                renderPrepared(cap, borrowed, textureOverride, entityYaw, partialTick, poseStack, bufferSource,
                    packedLight);
            } finally {
                textureOverride = null;
            }
            return true;
        });
    }

    @Override
    public boolean shouldShowName(Player entity) {
        double distance = this.entityRenderDispatcher.distanceToSqr(entity);
        float renderDistance = entity.isDiscrete() ? 32.0F : 64.0F;
        if (distance >= (double)(renderDistance * renderDistance)) {
            return false;
        } else {
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return false;
            }
            boolean invisible = !entity.isInvisibleTo(player);
            if (entity != player) {
                Team team1 = entity.getTeam();
                Team team2 = player.getTeam();
                if (team1 != null) {
                    Team.Visibility team$visibility = team1.getNameTagVisibility();
                    return switch (team$visibility) {
                        case ALWAYS -> invisible;
                        case NEVER -> false;
                        case HIDE_FOR_OTHER_TEAMS -> team2 == null ? invisible
                            : team1.isAlliedTo(team2) && (team1.canSeeFriendlyInvisibles() || invisible);
                        case HIDE_FOR_OWN_TEAM -> team2 == null ? invisible : !team1.isAlliedTo(team2) && invisible;
                    };
                }
            }
            return Minecraft.renderNames() && entity != minecraft.getCameraEntity() && invisible && !entity.isVehicle();
        }
    }

    @Override
    @NotNull
    public ResourceLocation getTextureLocation(Player pEntity) {
        return textureOverride == null ? pEntity.getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .map(CustomPlayerEntity::getTextureLocation).orElse(MissingTextureAtlasSprite.getLocation())
            : textureOverride;
    }

    @Override
    @SuppressWarnings("all")
    protected void renderNameTag(Player player, Component displayName, PoseStack poseStack, MultiBufferSource buffer,
        int packedLight) {
        if (CustomGuiPlayerEntity.isFakePlayer(player)) {
            return;
        }
        double distance = this.entityRenderDispatcher.distanceToSqr(player);
        poseStack.pushPose();
        if (distance < 100) {
            Scoreboard scoreboard = player.getScoreboard();
            Objective objective = scoreboard.getDisplayObjective(2);
            if (objective != null) {
                Score score = scoreboard.getOrCreatePlayerScore(player.getScoreboardName(), objective);
                super.renderNameTag(player, (Component.literal(Integer.toString(score.getScore()))).append(" ")
                    .append(objective.getDisplayName()), poseStack, buffer, packedLight);
                poseStack.translate(0, 9.0 * 1.15 * 0.025, 0);
            }
        }
        super.renderNameTag(player, displayName, poseStack, buffer, packedLight);
        poseStack.popPose();
    }
}
