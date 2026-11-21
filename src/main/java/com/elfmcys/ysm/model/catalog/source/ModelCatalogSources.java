package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.model.ModelRuntime;

import java.nio.file.Path;
import java.util.List;

public final class ModelCatalogSources {
    private ModelCatalogSources() {}

    public static List<ModelCatalogSource> sources() {
        return sources(ModelRuntime.paths());
    }

    public static List<ModelCatalogSource> reloadableSources() {
        return reloadableSources(ModelRuntime.paths());
    }

    public static List<ModelCatalogSource> sources(AssetPaths paths) {
        var reloadable = reloadableSources(paths);
        var sources = new java.util.ArrayList<ModelCatalogSource>();
        sources.add(builtin(paths));
        sources.addAll(reloadable);
        return List.copyOf(sources);
    }

    public static ModelCatalogSource builtin(AssetPaths paths) {
        return new ModelCatalogSource(CatalogRootKind.BUILTIN, paths.builtinModelsResource(), false);
    }

    public static List<ModelCatalogSource> reloadableSources(AssetPaths paths) {
        var sources = new java.util.ArrayList<ModelCatalogSource>();
        paths.global().ifPresent(global -> {
            sources.add(new ModelCatalogSource(CatalogRoot.GLOBAL, global.modelsRoot(), false));
        });
        sources.add(new ModelCatalogSource(CatalogRoot.LOCAL, paths.modelsRoot(), true));
        return List.copyOf(sources);
    }

    public static Path modelsPath() {
        return ModelRuntime.paths().defaultModelsRoot();
    }
}
