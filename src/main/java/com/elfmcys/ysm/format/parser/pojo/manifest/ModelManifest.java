package com.elfmcys.ysm.format.parser.pojo.manifest;

import com.elfmcys.ysm.format.parser.pojo.manifest.metadata.ModelMetadata;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.ModelFiles;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.ModelFilesWarper;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.PlayerModelFiles;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ModelProperties;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class ModelManifest {
    @Nullable
    public ModelMetadata metadata;
    public ModelProperties properties = new ModelProperties();
    public ModelFiles files = new ModelFiles();

    public void normalize() {
        if (properties == null) {
            properties = new ModelProperties();
        }
        if (properties.extraAnimation == null) {
            properties.extraAnimation = new LinkedHashMap<>();
        }
        if (properties.extraAnimationButtons == null) {
            properties.extraAnimationButtons = new ArrayList<>();
        }
        if (properties.extraAnimationClassify == null) {
            properties.extraAnimationClassify = new ArrayList<>();
        }
        if (files == null) {
            files = new ModelFiles();
        }
        if (files.player == null) {
            files.player = new PlayerModelFiles();
        }
        if (files.projectiles == null) {
            files.projectiles = new ModelFilesWarper<>();
        }
        if (files.projectiles.list == null) {
            files.projectiles.list = new ArrayList<>();
        }
        if (files.vehicles == null) {
            files.vehicles = new ModelFilesWarper<>();
        }
        if (files.vehicles.list == null) {
            files.vehicles.list = new ArrayList<>();
        }
        if (files.player.model == null) {
            files.player.model = new LinkedHashMap<>();
        }
        if (files.player.animation == null) {
            files.player.animation = new LinkedHashMap<>();
        }
        if (files.player.animationControllers == null) {
            files.player.animationControllers = new ArrayList<>();
        }
        if (files.player.texture == null) {
            files.player.texture = new ArrayList<>();
        }
    }
}
