package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewRenderHostTest {
    @ParameterizedTest
    @CsvSource({"false, , false", "false, idle, false", "false, custom_gui, false", "true, , true", "true, '   ', true",
        "true, idle, true", "true, custom_gui, false"})
    void idleScreenshotOnlyOverridesDisabledRotationForACustomGuiAnimation(boolean disabled, String preview,
        boolean expected) {
        var settings = SettingsProto.newBuilder().setDisablePreviewRotation(disabled);
        if (preview != null)
            settings.setPreviewAnimation(preview);

        assertEquals(expected, PreviewRenderHost.disablePreviewRotation(settings.build()));
    }
}
