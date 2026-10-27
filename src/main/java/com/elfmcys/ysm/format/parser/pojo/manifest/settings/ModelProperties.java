package com.elfmcys.ysm.format.parser.pojo.manifest.settings;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class ModelProperties {
    @SerializedName("height_scale")
    public float heightScale = 0.7f;

    @SerializedName("width_scale")
    public float widthScale = 0.7f;

    @SerializedName("extra_animation")
    public LinkedHashMap<String, String> extraAnimation = new LinkedHashMap<>();

    @SerializedName("extra_animation_buttons")
    public ArrayList<ExtraAnimationButton> extraAnimationButtons = new ArrayList<>();

    @SerializedName("extra_animation_classify")
    public ArrayList<ExtraAnimationClassify> extraAnimationClassify = new ArrayList<>();

    @SerializedName("default_texture")
    public String defaultTexture = "";

    @SerializedName("preview_animation")
    public String previewAnimation = "";

    @SerializedName("render_layers_first")
    public boolean renderLayersFirst = false;

    public boolean free = false;

    public String originVer;

    @SerializedName("all_cutout")
    public boolean forceCulling = false;

    @SerializedName("disable_preview_rotation")
    public boolean disablePreviewRotation = false;

    @SerializedName("gui_no_lighting")
    public boolean guiNoLighting = false;

    @SerializedName("merge_multiline_expr")
    public boolean mergeMultilineExpr = false;

    @SerializedName("gui_foreground")
    public String guiForeground;

    @SerializedName("gui_background")
    public String guiBackground;

    @SerializedName("icon")
    public String icon;

    @SerializedName("thumbnail")
    public String thumbnail;
}
