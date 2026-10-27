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
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.expression.aot.UserFunctionProgram;
import com.elfmcys.ysm.geckolib3.file.AnimationControllerFile;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import org.apache.commons.lang3.StringUtils;

public final class ModelRenderTargetAssembler {
    private ModelRenderTargetAssembler() {}

    public static ModelRenderTarget build(Hash256 modelHash, ModelRenderTargetBuildInput data) {
        var assets = buildCommonAssets(data);
        var armAssets = data.armAssets() == null ? null
            : new CommonAsset(data.armAssets().sounds(), data.armAssets().soundResources(),
                new Object2ReferenceOpenHashMap<>(data.armAssets().userFunctions()),
                new Object2ReferenceOpenHashMap<>(data.armAssets().eventHandlers()), false);
        final RenderTargetResources targetResources;
        if (data.target() instanceof PlayerModelData player) {
            targetResources = buildPlayerModel(player, data.info(), assets, armAssets == null ? assets : armAssets);
        } else if (data.target() instanceof ProjectileModelData projectile) {
            targetResources = buildProjectileModel(projectile, assets);
        } else if (data.target() instanceof VehicleModelData vehicle) {
            targetResources = buildVehicleModel(vehicle, assets);
        } else {
            throw new IllegalArgumentException("Unsupported model render target data: " + data.target().getClass());
        }
        return new ModelRenderTarget(modelHash, data.renderTargetId(), targetResources, assets, data.expressions(),
            data.info(), armAssets, data.armExpressions(), data.preparedOwner(), data.defaultSources(),
            data.stateIdentity());
    }

    private static PlayerModelResources buildPlayerModel(PlayerModelData player, ModelInfoView info, CommonAsset assets,
        CommonAsset armAssets) {
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

        var animationControllers = new Object2ReferenceOpenHashMap<String, AnimationControllerData>();
        for (var file : player.animationControllerFiles()) {
            animationControllers.putAll(file.animationControllers());
        }
        var armControllers = new Object2ReferenceOpenHashMap<String, AnimationControllerData>();
        for (var file : player.fpArmAnimationControllerFiles())
            armControllers.putAll(file.animationControllers());

        var configuredTexture = info.getSettings().defaultTexture().orElse("");
        var defaultTexture = !StringUtils.isEmpty(configuredTexture) && variants.containsKey(configuredTexture)
            ? configuredTexture : variants.getKeyAt(0);

        return new PlayerModelResources(variants, animations, fpArmAnimations, conditionManager, fpArmConditionManager,
            animationControllers, armControllers, defaultTexture, assets, armAssets);
    }

    private static ProjectileModelResources buildProjectileModel(ProjectileModelData projectile, CommonAsset assets) {
        AnimationControllerFile controllerFile = projectile.controllerFile();
        Object2ReferenceMap<String, AnimationControllerData> controllers = Object2ReferenceMaps.emptyMap();
        if (controllerFile != null) {
            controllers = new Object2ReferenceOpenHashMap<>(controllerFile.animationControllers());
        }
        return new ProjectileModelResources(projectile.geoModel(), projectile.animations(), controllers, assets);
    }

    private static VehicleModelResources buildVehicleModel(VehicleModelData vehicle, CommonAsset assets) {
        var controllerFile = vehicle.controllerFile();
        Object2ReferenceMap<String, AnimationControllerData> controllers = Object2ReferenceMaps.emptyMap();
        if (controllerFile != null) {
            controllers = new Object2ReferenceOpenHashMap<>(controllerFile.animationControllers());
        }
        return new VehicleModelResources(vehicle.geoModel(), vehicle.animations(), controllers, assets);
    }

    private static CommonAsset buildCommonAssets(ModelRenderTargetBuildInput data) {
        var sounds = data.assets().sounds();
        var userFunctions = buildUserFunctionMap(data);
        return new CommonAsset(sounds, data.assets().soundResources(), userFunctions,
            new Object2ReferenceOpenHashMap<>(data.assets().eventHandlers()));
    }

    private static Object2ReferenceOpenHashMap<String, UserFunctionProgram>
        buildUserFunctionMap(ModelRenderTargetBuildInput data) {
        var map = new Object2ReferenceOpenHashMap<String, UserFunctionProgram>(data.assets().userFunctions().size());
        map.putAll(data.assets().userFunctions());
        return map;
    }
}
