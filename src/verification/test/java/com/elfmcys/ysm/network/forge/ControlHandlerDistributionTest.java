package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.network.protocol.ProtocolMessageRegistry;
import com.elfmcys.ysm.proto.network.ServerFlagsProto;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ControlHandlerDistributionTest {
    @Test
    void serverSelectionHandlerLinksWithoutTheClientCapability() {
        var loader = dedicatedLoader();
        assertDoesNotThrow(() -> Class.forName(ControlHandler.class.getName(), true, loader));
    }

    @Test
    void playerStateAndServerFlagsRegistrationDoNotResolveClientHandlers() throws Exception {
        var loader = dedicatedLoader();
        assertDoesNotThrow(() -> Class.forName(PlayerStateHandler.class.getName(), true, loader));
        var messages = Class.forName("com.elfmcys.ysm.network.protocol.ProtocolMessages", true, loader);
        var registry = (ProtocolMessageRegistry)messages.getField("REGISTRY").get(null);
        registry.find(ServerFlagsProto.class).orElseThrow();
    }

    private ClassLoader dedicatedLoader() {
        var parent = getClass().getClassLoader();
        var isolated = List.of("com.elfmcys.ysm.network.forge.ControlHandler",
            "com.elfmcys.ysm.network.forge.PlayerStateHandler", "com.elfmcys.ysm.network.protocol.ProtocolMessages");
        return new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("com.elfmcys.ysm.capability.PlayerAnimatableCapability")
                    || name.equals("com.elfmcys.ysm.network.forge.ClientControlHandler")
                    || name.equals("com.elfmcys.ysm.network.forge.ClientPlayerStateHandler")) {
                    throw new ClassNotFoundException("Client capability is absent on dedicated servers");
                }
                if (isolated.stream().noneMatch(type -> name.equals(type) || name.startsWith(type + "$")))
                    return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var input = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null)
                                throw new ClassNotFoundException(name);
                            var bytes = input.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                    if (resolve)
                        resolveClass(loaded);
                    return loaded;
                }
            }
        };
    }
}
