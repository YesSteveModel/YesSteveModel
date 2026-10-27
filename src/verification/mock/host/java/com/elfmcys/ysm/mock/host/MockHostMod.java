package com.elfmcys.ysm.mock.host;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

@Mod(MockHostMod.MOD_ID)
public final class MockHostMod {
    public static final String MOD_ID = "ysm_mock_host";

    public MockHostMod() throws IOException {
        var io = HostIo.open();
        var expectedClient = io.role().startsWith("client-");
        if (expectedClient != (FMLEnvironment.dist == Dist.CLIENT)) {
            throw new IllegalStateException("Probe role does not match the Forge distribution");
        }
        MinecraftForge.EVENT_BUS
            .register(expectedClient ? instantiate("com.elfmcys.ysm.mock.host.ClientProbe", io) : new ServerProbe(io));
    }

    private static Object instantiate(String className, HostIo io) {
        try {
            return Class.forName(className).getConstructor(HostIo.class).newInstance(io);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
            | IllegalAccessException exception) {
            throw new IllegalStateException("Failed to construct client-only probe", exception);
        } catch (InvocationTargetException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Client-only probe failed during construction", cause);
        }
    }
}
