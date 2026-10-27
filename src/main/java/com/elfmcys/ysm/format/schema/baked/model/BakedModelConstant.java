package com.elfmcys.ysm.format.schema.baked.model;

import com.elfmcys.ysm.compatibility.AbiVersion;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public class BakedModelConstant {
    public static String SCHEMA_ID = "ysm/baked-model";
    public static String MANIFEST_CHUNK_NAME = "manifest";
    public static String BAKE_HASH_CHUNK_NAME = "bake_hash";
    public static String MODEL_CHUNK_NAME = "model";
    public static DefaultArtifactVersion CURRENT_VERSION = new DefaultArtifactVersion(AbiVersion.CACHE_GEOMETRY);
    public static short PROP_VERSION = 0;
}
