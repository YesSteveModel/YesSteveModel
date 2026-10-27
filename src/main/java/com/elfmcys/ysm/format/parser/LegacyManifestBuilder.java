package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.manifest.ModelManifest;
import com.elfmcys.ysm.format.parser.pojo.manifest.metadata.ModelAuthor;
import com.elfmcys.ysm.format.parser.pojo.manifest.metadata.ModelMetadata;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.PBRTextureSet;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.PlayerModelFiles;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.ReplacedModelFiles;
import com.elfmcys.ysm.format.parser.pojo.model.ExtraInfo;
import com.google.gson.Gson;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

final class LegacyManifestBuilder {
    private static final String INFO_FILE_NAME = "info.json";
    private static final String MAIN_MODEL_FILE_NAME = "main.json";
    private static final String ARM_MODEL_FILE_NAME = "arm.json";
    private static final String ARROW_MODEL_FILE_NAME = "arrow.json";
    private static final String ARROW_TEXTURE_FILE_NAME = "arrow.png";
    private static final String ARROW_ENTITY_ID = "arrow";

    @SuppressWarnings("deprecation")
    static ModelManifest build(RawModelSource source, Gson gson) throws IOException {
        var main = source.hasFile(MAIN_MODEL_FILE_NAME);
        var arm = source.hasFile(ARM_MODEL_FILE_NAME);
        var arrow = source.hasFile(ARROW_MODEL_FILE_NAME);

        if (!main) {
            throw new FileNotFoundException("File " + MAIN_MODEL_FILE_NAME + " not found");
        }
        if (!arm) {
            throw new FileNotFoundException("File " + ARM_MODEL_FILE_NAME + " not found");
        }

        var manifest = new ModelManifest();
        manifest.files.player = new PlayerModelFiles();
        manifest.files.player.model.put("main", MAIN_MODEL_FILE_NAME);
        manifest.files.player.model.put("arm", ARM_MODEL_FILE_NAME);
        for (var name : RawModelAssembler.PLAYER_MAIN_ANIMATION_TYPES) {
            var file = name + ".animation.json";
            if (source.hasFile(file)) {
                manifest.files.player.animation.put(name, file);
            }
        }

        source.collectFileNames("", ".png", false, (name, path) -> {
            if (!path.equals(ARROW_TEXTURE_FILE_NAME)) {
                manifest.files.player.texture.add(new PBRTextureSet(path));
            } else {
                if (arrow) {
                    manifest.files.arrow = new ReplacedModelFiles();
                    manifest.files.arrow.match.add(ARROW_ENTITY_ID);
                    manifest.files.arrow.model = ARROW_MODEL_FILE_NAME;
                    var animation = "arrow.animation.json";
                    if (source.hasFile(animation)) {
                        manifest.files.arrow.animation = animation;
                    }
                    manifest.files.arrow.texture = new PBRTextureSet(ARROW_TEXTURE_FILE_NAME);
                } else {
                    throw new UncheckedIOException(new FileNotFoundException("Arrow model not found"));
                }
            }
        });

        if (arrow && manifest.files.arrow == null) {
            throw new FileNotFoundException("Arrow texture not found");
        }

        source.readJson(INFO_FILE_NAME, "info", gson, ExtraInfo.class, false)
            .ifPresent(info -> applyLegacyInfo(manifest, info));

        return manifest;
    }

    private static void applyLegacyInfo(ModelManifest manifest, ExtraInfo info) {
        if (info == null) {
            return;
        }
        manifest.metadata = new ModelMetadata();
        manifest.metadata.name = Objects.requireNonNullElse(info.name, "");
        manifest.metadata.tips = Objects.requireNonNullElse(info.tips, "");
        manifest.metadata.license.type = Objects.requireNonNullElse(info.license, "All Rights Reserved");
        for (var author : info.authors) {
            manifest.metadata.authors.add(new ModelAuthor(author));
        }
        for (var i = 0; i < info.extraAnimationNames.size(); i++) {
            manifest.properties.extraAnimation.put("extra" + i, info.extraAnimationNames.get(i));
        }
        manifest.properties.free = info.free;
    }
}
