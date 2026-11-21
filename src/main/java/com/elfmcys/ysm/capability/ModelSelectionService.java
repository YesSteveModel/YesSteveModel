package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.PublishedModelId;

import java.util.Optional;

/** Resolves mutable player selection against an explicit immutable server catalog. */
public final class ModelSelectionService {
    private ModelSelectionService() {}

    public static boolean selectDefault(ModelInfoCapability capability, ServerCatalog snapshot) {
        var configuredId = snapshot.resolvePath(ServerConfig.DEFAULT_MODEL_PATH.get()).found()
            .filter(id -> snapshot.find(id).isPresent());
        var id = configuredId.or(() -> snapshot.defaultModel().map(model -> PublishedModelId.model(model.modelId())))
            .orElse(null);
        var model = id == null ? null : snapshot.find(id).or(() -> snapshot.defaultModel()).orElse(null);
        if (model == null) {
            return false;
        }
        var view = model.view();
        var configured = ServerConfig.DEFAULT_MODEL_TEXTURE.get();
        var requested = view.getPlayer().getTextureNames().contains(configured) ? configured
            : view.getMetadata().getSettings().defaultTexture().orElse("");
        var texture = view.getPlayer().getTextureNames().contains(requested) ? requested
            : view.getPlayer().getTextureNames().stream().sorted().findFirst().orElse("");
        capability.setModelSelection(id, texture);
        return true;
    }

    public static boolean selectBuiltinDefault(ModelInfoCapability capability, ServerCatalog snapshot,
        String requestedTexture) {
        var model = snapshot.defaultModel().orElse(null);
        if (model == null) {
            return false;
        }
        var textures = model.view().getPlayer().getTextureNames();
        var configured = model.view().getMetadata().getSettings().defaultTexture().orElse("");
        var texture = textures.contains(requestedTexture) ? requestedTexture
            : textures.contains(configured) ? configured : textures.stream().sorted().findFirst().orElse("");
        capability.setModelSelection(PublishedModelId.INTRINSIC_DEFAULT, texture);
        return true;
    }

    public static Optional<ManagedContainer> resolve(ModelInfoCapability capability, ServerCatalog snapshot) {
        if (capability.getModelId() == null && !selectDefault(capability, snapshot)) {
            return Optional.empty();
        }
        var model = snapshot.find(capability.getModelId()).orElse(null);
        if (model == null) {
            if (!selectDefault(capability, snapshot)) {
                capability.setModelAndTexture(null, "");
                return Optional.empty();
            }
            model = snapshot.find(capability.getModelId()).orElse(null);
        }
        if (capability.getModelId() != null && capability.getModelId().isIntrinsicDefault()) {
        }
        return Optional.ofNullable(model);
    }

    public static String displayId(ModelInfoCapability capability, ServerCatalog snapshot) {
        var hash = capability.getModelId();
        return hash == null ? CatalogLocation.BUILTIN_DEFAULT.value() : hash.isIntrinsicDefault() ? "default"
            : snapshot.find(hash).map(model -> model.location().value()).orElse(hash.toString());
    }
}
