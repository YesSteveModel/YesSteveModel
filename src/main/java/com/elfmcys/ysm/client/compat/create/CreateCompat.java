package com.elfmcys.ysm.client.compat.create;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public class CreateCompat {
    private static final String MOD_ID = "create";
    private static boolean INSTALLED = false;

    public static void init() {
        ModFileInfo modFileById = LoadingModList.get().getModFileById(MOD_ID);
        if (modFileById != null) {
            DefaultArtifactVersion modVersion = new DefaultArtifactVersion(modFileById.versionString());
            INSTALLED = modVersion.compareTo(new DefaultArtifactVersion("6.0.0")) >= 0;
        }
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static boolean isHangingSkyhook(Player player) {
        if (INSTALLED) {
            return CreateCompatInner.isHangingSkyhook(player);
        }
        return false;
    }

}
