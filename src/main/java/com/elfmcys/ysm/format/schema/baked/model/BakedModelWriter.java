package com.elfmcys.ysm.format.schema.baked.model;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.schema.file.AssetFileWriter;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;

import java.io.IOException;

public class BakedModelWriter extends AssetFileWriter {
    public BakedModelWriter() {
        setSchemaId(BakedModelConstant.SCHEMA_ID);
        setProperty(BakedModelConstant.PROP_VERSION, BakedModelConstant.CURRENT_VERSION.toString());
    }

    public void setData(byte[] bakeHash, GeoModelProto sourceModel, UniBuffer bakedData) throws IOException {
        if (bakeHash.length != Blake3.HASH_SIZE) {
            throw new IllegalArgumentException("Invalid bake hash");
        }
        addProtoChunk(BakedModelConstant.MANIFEST_CHUNK_NAME, sourceModel, 0);
        addRawChunk(BakedModelConstant.BAKE_HASH_CHUNK_NAME, ArrayBuffer.borrow(bakeHash), 0);
        addRawChunk(BakedModelConstant.MODEL_CHUNK_NAME, bakedData, 9);
    }

}
