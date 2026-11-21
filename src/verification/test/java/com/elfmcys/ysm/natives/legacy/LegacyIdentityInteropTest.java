package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LegacyIdentityInteropTest {
    @Test
    void nativeProjectedManifestPublishesTheSameCanonicalProperty(@TempDir Path temporary) throws Exception {
        final ManifestProto nativeManifest;
        try (var input = getClass().getResourceAsStream("/format/native-legacy-manifest.pb")) {
            assertNotNull(input);
            nativeManifest = ManifestProto.parseFrom(input.readAllBytes());
        }
        var expected = ModelId.parse("hash:000102030405060708090a0b0c0d0e0f00000000000000000000000000000000");
        assertEquals(expected.toString(), nativeManifest.info().properties().modelId());
        var manifest = new LegacyFormalModelMapper().manifest(nativeManifest);
        var file = temporary.resolve("native-manifest.mxc");
        try (var writer = new ModelFileWriter();
            var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.setManifest(manifest);
            writer.write(output);
        }
        try (var input = FileChannel.open(file)) {
            var preamble = AssetContainerReader.read(input);
            assertEquals(expected.toString(), preamble.getSchemaProperty(ModelFileConstant.PROP_MODEL_ID));
            input.position(0);
            assertEquals(expected, ModelFileIdentityReader.read(input).modelId());
        }
    }
}
