package com.elfmcys.ysm.format;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicUnstableVersionTest {
    @Test
    void publicFormatsUseTheirDeclaredUnstableVersions() {
        var expected = "0.1.0-unstable";
        var modelExpected = "0.1.1-unstable";
        assertAll(() -> assertEquals(expected, AssetContainerConstant.CURRENT_VERSION),
            () -> assertEquals(0, AssetContainerConstant.CURRENT_MAJOR_VER),
            () -> assertEquals(1, AssetContainerConstant.CURRENT_MINOR_VER),
            () -> assertEquals(0, AssetContainerConstant.CURRENT_PATCH_VER),
            () -> assertEquals("unstable", AssetContainerConstant.CURRENT_QUALIFIER_VER),
            () -> assertEquals(modelExpected, ModelFileConstant.CURRENT_VERSION.toString()),
            () -> assertEquals("0.5.0-unstable", ProtocolVersion.CURRENT),
            () -> assertEquals("0.5.0-unstable", ProtocolVersion.TRANSPORT_VERSION));
    }
}
