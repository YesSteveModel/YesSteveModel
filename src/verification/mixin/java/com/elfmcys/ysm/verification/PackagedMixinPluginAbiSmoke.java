package com.elfmcys.ysm.verification;

import java.lang.reflect.Modifier;

public final class PackagedMixinPluginAbiSmoke {
    private PackagedMixinPluginAbiSmoke() {}

    public static void main(String[] args) throws ReflectiveOperationException {
        var loader = PackagedMixinPluginAbiSmoke.class.getClassLoader();
        var contract = Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin", false, loader);
        // Loading without initialization keeps this ABI check independent of Forge startup.
        var plugin = Class.forName("com.elfmcys.ysm.mixin.plugin.MixinTweaker", false, loader);
        if (!contract.isAssignableFrom(plugin)) {
            throw new AssertionError("Packaged MixinTweaker does not implement IMixinConfigPlugin");
        }
        for (var callback : contract.getMethods()) {
            if (!Modifier.isAbstract(callback.getModifiers())) {
                continue;
            }
            var implementation = plugin.getMethod(callback.getName(), callback.getParameterTypes());
            if (Modifier.isAbstract(implementation.getModifiers())
                || implementation.getReturnType() != callback.getReturnType()) {
                throw new AssertionError("Packaged Mixin callback does not implement " + callback);
            }
        }
        System.out.println("PACKAGED_MIXIN_PLUGIN_ABI_OK");
    }
}
