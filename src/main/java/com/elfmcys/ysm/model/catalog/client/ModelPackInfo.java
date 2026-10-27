package com.elfmcys.ysm.model.catalog.client;

import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class ModelPackInfo {
    /** Full canonical directory address; source prefixes do not create navigation nodes. */
    private final String hierarchy;
    private final String name;
    private final String desc;
    /**
     * 最大 260 x 450，是游戏分辨率为 2k 且界面尺寸为 5 时 ModelButton 的大小。
     */
    private volatile @Nullable AbstractTexture icon;
    private final @Nullable Map<String, Map<String, String>> lang;

    public ModelPackInfo(String hierarchy, String name, String desc, @Nullable AbstractTexture icon,
        @Nullable Map<String, Map<String, String>> lang) {
        this.hierarchy = hierarchy;
        this.name = name;
        this.desc = desc;
        this.icon = icon;
        this.lang = lang;
    }

    @NotNull
    public String hierarchy() {
        return hierarchy;
    }

    @NotNull
    public String name() {
        return name;
    }

    @Nullable
    public String desc() {
        return desc;
    }

    @Nullable
    public AbstractTexture icon() {
        return icon;
    }

    public void setIcon(@Nullable AbstractTexture icon) {
        this.icon = icon;
    }

    @Nullable
    public Map<String, Map<String, String>> lang() {
        return lang;
    }
}
