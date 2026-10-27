package com.elfmcys.ysm.format.schema.baked.asset;

import com.elfmcys.ysm.compatibility.AbiVersion;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public final class BakedAssetConstant {
    public static final String SCHEMA_ID = "ysm/baked-asset";
    public static final String MANIFEST_CHUNK_NAME = "manifest";
    public static final String EXPRESSION_CHUNK_NAME = "expression";
    public static final String ANIM_CHUNK_PREFIX = "animation/";
    public static final DefaultArtifactVersion CURRENT_VERSION = new DefaultArtifactVersion(AbiVersion.CACHE_ANIMATION);
    public static final short PROP_VERSION = 0;

    private BakedAssetConstant() {}
}
