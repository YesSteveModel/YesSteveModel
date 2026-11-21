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

/**
 * Coordinates one world's ordinary animation invocation and its final projection window.
 *
 * <p>
 * Execution: the host invokes this coordinator on the main thread. The host must pair scheduleAll() with waitAll()
 * before leaving the invocation, including when scheduling fails. Scheduled tasks own separate entity state; the
 * coordinator does not freeze live host inputs.
 *
 * <p>
 * Ownership: registrations are weak. The invocation retains scheduled owners until every task and final projection is
 * drained.
 *
 * <p>
 * Seam: implements the ordinary invocation portion of [execution authority and task
 * handoff](docs/architecture/animation/entity-lifecycle.md#执行权与任务交接).
 */
public class AnimationParallelTicker {
    private static final ReferenceArrayList<WeakReference<AnimatableEntity<?>>> ANIMATABLE_LIST =
        new ReferenceArrayList<>(64);
    private static final ReferenceArrayList<AnimatableEntity<?>> TASK_LIST = new ReferenceArrayList<>(16);

    private static boolean invocationActive;
    private static Throwable invocationFailure;

    public static boolean isInvocationActive() {
        return invocationActive;
    }

    /**
     * Retains an owner until the complete invocation drain, including after scheduling failure or retirement.
     *
     * <p>
     * Requires: the invocation is open. The owner's retainForAnimationInvocation() calls this method once per
     * invocation, on the main thread. The owner maintains registration; this queue does not deduplicate entries.
     */
    public static void retainForFinalProjection(AnimatableEntity<?> owner) {
        TASK_LIST.add(owner);
    }

    /** Requires the owner to register once, during construction, on the main thread. */
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
        ResourceDiagnostics.log("world animatables retired: world={} count={}", System.identityHashCode(level),
            retired);
        if (failure instanceof RuntimeException error)
            throw error;
        if (failure instanceof Error error)
            throw error;
    }

    /**
     * Opens one invocation and schedules active registered owners.
     *
     * <p>
     * Failure: owner scheduling failures accumulate for waitAll(). An empty world still requires waitAll() to close the
     * invocation. Recursive invocation is a caller violation.
     */
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

    /**
     * Adds one owner to the open invocation before starting its update.
     *
     * <p>
     * Requires: scheduleAll() has opened the invocation. The caller schedules each owner at most once in this
     * invocation and preserves the matching waitAll() obligation after failure. The task list retains an owner whenever
     * a submitted task still needs its terminal handoff.
     */
    public static void schedule(AnimatableEntity<?> owner, float partialTick) {
        if (!invocationActive)
            throw new IllegalStateException("Asynchronous animation requires a world invocation");
        owner.retainForAnimationInvocation();
        owner.beginAsyncUpdate(partialTick);
    }

    /**
     * Drains every retained task, then attempts every final projection, before closing the invocation.
     *
     * <p>
     * Failure: an owner failure does not skip other owners. The method clears invocation state before reporting the
     * first failure with later failures suppressed.
     *
     * <p>
     * Execution: can block on each corresponding task get; completion of an individual future is not an owner-access
     * handoff.
     */
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
        for (var animatable : TASK_LIST)
            animatable.releaseAnimationInvocation();
        TASK_LIST.clear();
        invocationFailure = null;
        invocationActive = false;
        if (failure instanceof RuntimeException error)
            throw error;
        if (failure instanceof Error error)
            throw error;
    }

    private static Throwable appendFailure(Throwable primary, Throwable next) {
        if (primary == null)
            return next;
        if (primary != next)
            primary.addSuppressed(next);
        return primary;
    }
}
