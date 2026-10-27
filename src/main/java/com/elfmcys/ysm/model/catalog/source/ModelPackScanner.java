package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Reads one model-pack manifest and its optional cover metadata. */
public final class ModelPackScanner {
    private static final int MAX_PACK_ICON_SIZE = 1024 * 1024;

    private final Gson gson = new Gson();

    public ModelPackScanner() {}

    public Optional<ModelPackDescriptor> scan(ModelCatalogSource root, Path directory) throws IOException {
        directory = directory.toAbsolutePath().normalize();
        var configured = root.path().toAbsolutePath().normalize();
        if (Files.notExists(directory.resolve("ysm-pack.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        var hierarchy = directory.equals(configured) ? "" : ModelPath.relativeTo(configured, directory).value() + "/";
        return scan(ModelSourceDiscovery.observePack(new ModelPackSourceKey(root.identity(), hierarchy), directory));
    }

    public Optional<ModelPackDescriptor> scan(PackObservation observation) throws IOException {
        if (!observation.equals(ModelSourceDiscovery.observePack(observation.key(), observation.directory()))) {
            throw new IOException("Model pack changed before reading: " + observation.directory());
        }
        final PackManifest source;
        try (var reader = Files.newBufferedReader(observation.manifest().target())) {
            source = gson.fromJson(reader, PackManifest.class);
        }
        if (source == null) {
            throw new IOException("Empty model pack manifest");
        }

        var translations = new LinkedHashMap<String, ModelPackDescriptor.LocalizedText>();
        if (source.lang != null) {
            source.lang.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                var value = entry.getValue();
                translations.put(entry.getKey(), new ModelPackDescriptor.LocalizedText(value == null ? "" : value.name,
                    value == null ? "" : value.description));
            });
        }

        Hash256 coverHash = null;
        var coverSize = 0;
        if (observation.cover() != null) {
            var iconPath = observation.cover().target();
            var size = Files.size(iconPath);
            if (size > MAX_PACK_ICON_SIZE) {
                throw new IOException("Model pack icon is larger than " + MAX_PACK_ICON_SIZE + " bytes");
            }
            coverHash = ModelHashing.blake3(iconPath);
            coverSize = Math.toIntExact(size);
        }
        if (!observation.equals(ModelSourceDiscovery.observePack(observation.key(), observation.directory()))) {
            throw new IOException("Model pack changed while reading: " + observation.directory());
        }
        return Optional
            .of(new ModelPackDescriptor(observation.key().root().rootKind(), observation.key().location().value(),
                source.name, source.description, translations, coverHash, coverSize == 0 ? "" : "png", coverSize));
    }

    private static final class PackManifest {
        private String name = "";
        private String description = "";
        private Map<String, PackTranslation> lang = Map.of();
    }

    private static final class PackTranslation {
        private String name = "";
        private String description = "";
    }
}
