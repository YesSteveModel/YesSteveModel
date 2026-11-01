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

    private static boolean invocationActive;
    private static Throwable invocationFailure;

    public static boolean isInvocationActive() { return invocationActive; }

    public static void retainForFinalProjection(AnimatableEntity<?> owner) {
        if (invocationActive && !TASK_LIST.contains(owner))
            TASK_LIST.add(owner);
    }

    public static void add(AnimatableEntity<?> instance) {
        ANIMATABLE_LIST.add(new WeakReference<>(instance));
    }

    public static void retireLevel(Level level) {
        int retired = 0;
        Throwable failure = null;
        var iterator = ANIMATABLE_LIST.iterator();
        while (iterator.hasNext()) {
            var animatable = iterator.next().get();
            if (animatable == null) {
                iterator.remove();
            } else if (animatable.getEntity().level() == level) {
                iterator.remove();
                try {
                    animatable.retire();
                } catch (RuntimeException | Error next) {
                    failure = appendFailure(failure, next);
                }
                retired++;
            }
        }
        TASK_LIST.removeIf(animatable -> animatable.getEntity().level() == level);
        ResourceDiagnostics.log("world animatables retired: world={} count={}", System.identityHashCode(level), retired);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    public static void scheduleAll(final float partialTick) {
        if (invocationActive)
            throw new IllegalStateException("Recursive world animation invocation");
        invocationActive = true;
        invocationFailure = null;
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
                try {
                    animatable.retire();
                } catch (RuntimeException | Error failure) {
                    invocationFailure = appendFailure(invocationFailure, failure);
                }
                continue;
            }

            try {
                schedule(animatable, partialTick);
            } catch (RuntimeException | Error failure) {
                invocationFailure = appendFailure(invocationFailure, failure);
            }
        }
    }

    public static void schedule(AnimatableEntity<?> owner, float partialTick) {
        if (!invocationActive)
            throw new IllegalStateException("Asynchronous animation requires a world invocation");
        TASK_LIST.add(owner);
        try {
            owner.beginAsyncUpdate(partialTick);
        } catch (RuntimeException | Error failure) {
            if (!owner.hasPendingUpdate())
                TASK_LIST.remove(owner);
            throw failure;
        }
    }

    public static void waitAll() {
        Throwable failure = invocationFailure;
        for (var animatable : TASK_LIST) {
            try {
                if (animatable.hasPendingUpdate())
                    animatable.waitForAsyncUpdate();
            } catch (RuntimeException | Error next) {
                failure = appendFailure(failure, next);
            }
        }
        for (var animatable : TASK_LIST) {
            try {
                animatable.finishProjections(true);
            } catch (RuntimeException | Error next) {
                failure = appendFailure(failure, next);
            }
        }
        TASK_LIST.clear();
        invocationFailure = null;
        invocationActive = false;
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private static Throwable appendFailure(Throwable primary, Throwable next) {
        if (primary == null) return next;
        if (primary != next) primary.addSuppressed(next);
        return primary;
    }
}
