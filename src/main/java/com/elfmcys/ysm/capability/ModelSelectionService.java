package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;

import java.util.Optional;

/** Resolves mutable player selection against an explicit immutable server catalog. */
public final class ModelSelectionService {
    private ModelSelectionService() {}

    public static boolean selectDefault(ModelInfoCapability capability, ServerCatalog snapshot) {
        var model = snapshot.findPath(ServerConfig.DEFAULT_MODEL_PATH.get()).or(snapshot::defaultModel).orElse(null);
        if (model == null) {
            return false;
        }
        var view = model.view();
        var configured = ServerConfig.DEFAULT_MODEL_TEXTURE.get();
        var requested = view.getPlayer().getTextureNames().contains(configured) ? configured
            : view.getMetadata().getSettings().defaultTexture().orElse("");
        var texture = view.getPlayer().getTextureNames().contains(requested) ? requested
            : view.getPlayer().getTextureNames().stream().sorted().findFirst().orElse("");
        capability.setModelAndTexture(model.representation().modelId(), texture);
        return true;
    }

    public static boolean selectBuiltinDefault(ModelInfoCapability capability, ServerCatalog snapshot,
        String requestedTexture) {
        var model =
            snapshot.models().values().stream().filter(handle -> handle.location().rootKind() == CatalogRootKind.BUILTIN
                && handle.location().path().value().equals("default")).findFirst().orElse(null);
        if (model == null) {
            return false;
        }
        var textures = model.view().getPlayer().getTextureNames();
        var configured = model.view().getMetadata().getSettings().defaultTexture().orElse("");
        var texture = textures.contains(requestedTexture) ? requestedTexture
            : textures.contains(configured) ? configured : textures.stream().sorted().findFirst().orElse("");
        capability.clearIgnoreGrants();
        capability.setModelAndTexture(model.representation().modelId(), texture);
        return true;
    }

    public static Optional<ManagedContainer> resolve(ModelInfoCapability capability, ServerCatalog snapshot) {
        if (capability.getModelId() == null && !selectDefault(capability, snapshot)) {
            return Optional.empty();
        }
        var model = snapshot.find(capability.getModelId()).orElse(null);
        if (model == null || !model.view().getPlayer().getTextureNames().contains(capability.getSelectTexture())) {
            capability.clearIgnoreGrants();
            if (!selectDefault(capability, snapshot)) {
                capability.setModelAndTexture(null, "");
                return Optional.empty();
            }
            model = snapshot.find(capability.getModelId()).orElse(null);
        }
        if (model != null && model.location().rootKind() == CatalogRootKind.BUILTIN
            && model.location().path().value().equals("default")) {
            capability.clearIgnoreGrants();
        }
        return Optional.ofNullable(model);
    }

    public static String displayId(ModelInfoCapability capability, ServerCatalog snapshot) {
        var hash = capability.getModelId();
        return hash == null ? CatalogLocation.DEFAULT.value()
            : snapshot.find(hash).map(model -> model.location().value()).orElse(hash.toString());
    }
}
