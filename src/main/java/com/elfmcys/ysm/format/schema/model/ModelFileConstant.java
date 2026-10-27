package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.compatibility.AbiVersion;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public final class ModelFileConstant {
    public static final String SCHEMA_ID = "mixel/character";
    public static final String MANIFEST_CHUNK_NAME = "manifest";
    public static final String THUMB_BUTTON_CHUNK_NAME = "thumb-button";
    public static final String THUMB_ICON_CHUNK_NAME = "thumb-icon";
    public static final DefaultArtifactVersion CURRENT_VERSION = new DefaultArtifactVersion(AbiVersion.MODEL_SCHEMA);
    public static final short PROP_VERSION = 0;
    public static final short PROP_VENDOR = 1;
    public static final short PROP_MODEL_ID = 2;
    public static final short PROP_SIGNATURE = 3;

    private ModelFileConstant() {}
}
