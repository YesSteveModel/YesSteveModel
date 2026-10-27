package com.elfmcys.ysm.client.animation;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;

public class AnimationParallelTicker {
    private static final ReferenceArrayList<WeakReference<AnimatableEntity<?>>> ANIMATABLE_LIST =
        new ReferenceArrayList<>(64);
    private static final ReferenceArrayList<AnimatableEntity<?>> TASK_LIST = new ReferenceArrayList<>(16);

    public static void add(AnimatableEntity<?> instance) {
        ANIMATABLE_LIST.add(new WeakReference<>(instance));
    }

    public static void retireLevel(Level level) {
        int retired = 0;
        var iterator = ANIMATABLE_LIST.iterator();
        while (iterator.hasNext()) {
            var animatable = iterator.next().get();
            if (animatable == null) {
                iterator.remove();
            } else if (animatable.getEntity().level() == level) {
                iterator.remove();
                animatable.retire();
                retired++;
            }
        }
        TASK_LIST.removeIf(animatable -> animatable.getEntity().level() == level);
        ResourceDiagnostics.log("world animatables retired: world={} count={}", System.identityHashCode(level), retired);
    }

    public static void scheduleAll(final float partialTick) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer localPlayer = mc.player;
        if (localPlayer == null) {
            return;
        }
        final var iterator = ANIMATABLE_LIST.iterator();
        while (iterator.hasNext()) {
            final AnimatableEntity<?> animatable = iterator.next().get();
            if (animatable == null) {
                iterator.remove();
                continue;
            }
            if (!animatable.isActive()) {
                // Client entity revival is outside this scheduler's lifecycle contract.
                iterator.remove();
                animatable.retire();
                continue;
            }

            animatable.checkModelUpdate();
            if (!animatable.canEvaluate())
                continue;

            animatable.beginAsyncUpdate(partialTick);
            TASK_LIST.add(animatable);
        }
    }

    public static void waitAll() {
        for (var animatable : TASK_LIST) {
            try {
                animatable.waitForAsyncUpdate();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
        TASK_LIST.clear();
    }
}
