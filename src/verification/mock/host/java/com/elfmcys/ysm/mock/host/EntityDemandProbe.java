package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Counts each subject's real access calls without replacing resource results. */
public final class EntityDemandProbe {
    // Observation must not extend the entity or client world's lifetime.
    private static final Map<AnimatableEntity<?>, Map<String, Integer>> CALLS = new WeakHashMap<>();

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) throws ReflectiveOperationException {
        if (!event.getLevel().isClientSide() || !(event.getEntity() instanceof Player player))
            return;
        var owner = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (owner == null || CALLS.containsKey(owner))
            return;
        var bindingField = AnimatableEntity.class.getDeclaredField("modelBinding");
        bindingField.setAccessible(true);
        var binding = bindingField.get(owner);
        var accessField = binding.getClass().getDeclaredField("models");
        accessField.setAccessible(true);
        var delegate = accessField.get(binding);
        var counts = new LinkedHashMap<String, Integer>();
        CALLS.put(owner, counts);
        var type = accessField.getType();
        accessField.set(binding,
            Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
                synchronized (counts) {
                    counts.merge(method.getName(), 1, Integer::sum);
                }
                method.setAccessible(true);
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }));
    }

    static Map<String, Integer> snapshot(AnimatableEntity<?> owner) {
        var counts = CALLS.get(owner);
        if (counts == null)
            return Map.of("notObservedFromJoin", 1);
        synchronized (counts) {
            return Map.copyOf(counts);
        }
    }
}
