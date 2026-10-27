package com.elfmcys.ysm.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.elfmcys.ysm.natives.NativeRuntime;
import java.nio.file.Path;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientConfigDeviceDefaultsTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(NativeRuntime.DeviceQuality.class)
    void parsesIndependentMissingAndExplicitValuesAndIgnoresTheOldKey(NativeRuntime.DeviceQuality quality) {
        boolean eager = quality == NativeRuntime.DeviceQuality.HIGH;
        int retained = eager ? 30 : 10;
        for (var mode = 0; mode < 6; mode++) {
            var config = CommentedConfig.inMemory();
            if (mode == 1 || mode == 3 || mode == 5) config.set("general.ModelScreenEagerLoading", !eager);
            if (mode == 2 || mode == 3 || mode == 5) config.set("general.ModelRetentionCount", 17);
            if (mode == 4 || mode == 5) config.set("general.UnusedModelRenderTargetLimit", 99);
            var builder = new ForgeConfigSpec.Builder();
            ClientConfig.init(builder, quality);
            builder.build().setConfig(config);
            assertEquals(mode == 1 || mode == 3 || mode == 5 ? !eager : eager,
                ClientConfig.MODEL_SCREEN_EAGER_LOADING.get());
            assertEquals(mode == 2 || mode == 3 || mode == 5 ? 17 : retained,
                ClientConfig.MODEL_RETENTION_COUNT.get());
        }
    }

    @ParameterizedTest
    @EnumSource(NativeRuntime.DeviceQuality.class)
    void reloadsExplicitTomlValuesWithoutReplacingThemWithDefaults(NativeRuntime.DeviceQuality quality) {
        var file = directory.resolve("client.toml");
        try (var config = CommentedFileConfig.of(file)) {
            config.set("general.ModelScreenEagerLoading", false);
            config.set("general.ModelRetentionCount", 123);
            config.save();
        }
        for (int reload = 0; reload < 2; reload++) {
            try (var config = CommentedFileConfig.of(file)) {
                config.load();
                var builder = new ForgeConfigSpec.Builder();
                ClientConfig.init(builder, quality);
                builder.build().setConfig(config);
                assertEquals(false, ClientConfig.MODEL_SCREEN_EAGER_LOADING.get());
                assertEquals(123, ClientConfig.MODEL_RETENTION_COUNT.get());
            }
        }
    }
}
