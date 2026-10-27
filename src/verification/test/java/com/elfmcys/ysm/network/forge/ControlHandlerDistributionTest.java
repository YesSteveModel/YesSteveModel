package com.elfmcys.ysm.network.forge;

import java.io.IOException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ControlHandlerDistributionTest {
    @Test
    void serverSelectionHandlerLinksWithoutTheClientCapability() {
        var parent = getClass().getClassLoader();
        var loader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("com.elfmcys.ysm.capability.PlayerAnimatableCapability")
                    || name.equals("com.elfmcys.ysm.network.forge.ClientControlHandler")) {
                    throw new ClassNotFoundException("Client capability is absent on dedicated servers");
                }
                if (!name.startsWith(ControlHandler.class.getName()))
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
        assertDoesNotThrow(() -> Class.forName(ControlHandler.class.getName(), true, loader));
    }
}
