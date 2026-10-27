package com.elfmcys.ysm.format.parser.pojo.model;

import com.google.gson.annotations.SerializedName;

public class FaceUv {
    public float[] uv;

    @SerializedName("uv_size")
    public float[] uvSize;
    /*
    // 暂未实现
    @SerializedName("uv_rotation")
    public Integer uvRotation;

     */
}
