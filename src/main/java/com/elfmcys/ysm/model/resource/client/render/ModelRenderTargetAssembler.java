package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.FPArmConditionManager;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.model.resource.client.ProjectileModelResources;
import com.elfmcys.ysm.model.resource.client.RenderTargetResources;
import com.elfmcys.ysm.model.resource.client.VehicleModelResources;
import com.elfmcys.ysm.model.resource.client.data.ModelRenderTargetBuildInput;
import com.elfmcys.ysm.model.resource.client.data.PlayerModelData;
import com.elfmcys.ysm.model.resource.client.data.ProjectileModelData;
import com.elfmcys.ysm.model.resource.client.data.VehicleModelData;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.ModelId;
import org.apache.commons.lang3.StringUtils;

public final class ModelRenderTargetAssembler {
    private ModelRenderTargetAssembler() {}

    public static ModelRenderTarget build(ModelId modelHash, ModelRenderTargetBuildInput data) {
        var assets = buildCommonAssets(data);
        var armAssets = data.armAssets() == null ? null
            : new CommonAsset(data.armAssets().sounds(), data.armAssets().soundResources(), false);
        final RenderTargetResources targetResources;
        if (data.target() instanceof PlayerModelData player) {
            targetResources = buildPlayerModel(player, data.info());
        } else if (data.target() instanceof ProjectileModelData projectile) {
            targetResources = new ProjectileModelResources(projectile.geoModel(), projectile.animations());
        } else if (data.target() instanceof VehicleModelData vehicle) {
            targetResources = new VehicleModelResources(vehicle.geoModel(), vehicle.animations());
        } else {
            throw new IllegalArgumentException("Unsupported model render target data: " + data.target().getClass());
        }
        return new ModelRenderTarget(modelHash, data.renderTargetId(), targetResources, assets, data.animationStore(),
            data.info(), armAssets, data.armAnimationStore(), data.preparedOwner(), data.defaultSources(),
            data.stateIdentity());
    }

    private static PlayerModelResources buildPlayerModel(PlayerModelData player, ModelInfoView info) {
        var variants = player.variants();
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("Player model has no variants");
        }

        var animations = player.animations();
        var fpArmAnimations = player.firstPersonAnimations();

        var conditionManager = new ConditionManager();
        animations.keySet().forEach(conditionManager::addTest);

        var fpArmConditionManager = new FPArmConditionManager();
        fpArmAnimations.keySet().forEach(fpArmConditionManager::addTest);

        var configuredTexture = info.getSettings().defaultTexture().orElse("");
        var defaultTexture = !StringUtils.isEmpty(configuredTexture) && variants.containsKey(configuredTexture)
            ? configuredTexture : variants.getKeyAt(0);

        return new PlayerModelResources(variants, animations, fpArmAnimations, conditionManager, fpArmConditionManager,
            defaultTexture);
    }

    private static CommonAsset buildCommonAssets(ModelRenderTargetBuildInput data) {
        var sounds = data.assets().sounds();
        return new CommonAsset(sounds, data.assets().soundResources());
    }
}
