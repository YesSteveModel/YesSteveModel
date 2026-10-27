package com.elfmcys.ysm.format.container;

import com.elfmcys.ysm.compatibility.AbiVersion;

import com.elfmcys.ysm.natives.Blake3;

public class AssetContainerConstant {
    public static final byte[] HEAD =
        new byte[] {(byte)0xEF, (byte)0xBB, (byte)0xBF, 'Y', 'S', 'G', 'P', '2', '\n', '\n'};
    public static final String VERIFICATION_CHUNK_TYPE = "verification";
    public static final int MAX_FILE_SIZE = 128 * 1024 * 1024;
    public static final int MAX_SUMMARY_SIZE = 16 * 1024;
    public static final int HEADER_SIZE = 64;
    public static final int HEADER_QUALIFIER_VERSION_SIZE = 13;
    public static final int HEADER_SCHEMA_SIZE = 32;
    public static final int MAX_CHUNK_COUNT = Short.MAX_VALUE;
    public static final int MAX_CHUNK_ALIGN_SHIFT = 8;

    public static final String CURRENT_VERSION = AbiVersion.CONTAINER;
    public static final int CURRENT_MAJOR_VER = 0;
    public static final int CURRENT_MINOR_VER = 1;
    public static final int CURRENT_PATCH_VER = 0;
    public static final String CURRENT_QUALIFIER_VER = "unstable";

    public static final String HASH_NAME = "BLAKE3";
    public static final int HASH_SIZE = Blake3.HASH_SIZE;
    public static final int VERIFICATION_PAYLOAD_HEADER_SIZE = HASH_SIZE + Short.BYTES;

    public static final String ED25519_NAME = "ED25519";
}
