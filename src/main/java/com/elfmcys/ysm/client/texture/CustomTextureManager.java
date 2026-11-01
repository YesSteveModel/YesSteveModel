package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.util.CleanerUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.time.StopWatch;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class CustomTextureManager {
    private static final int DEFAULT_REMOVAL_DELAY_TICKS = 10 * 20;
    private static final Registry REGISTRY = new Registry(new MinecraftHost());

    private CustomTextureManager() {}

    public static TextureHolder register(AbstractTexture texture) {
        return register(texture, DEFAULT_REMOVAL_DELAY_TICKS);
    }

    public static TextureHolder register(AbstractTexture texture, int removingDelayTicks) {
        RenderSystem.assertOnRenderThread();
        return REGISTRY.register(texture, removingDelayTicks);
    }

    public static void release(AbstractTexture texture) {
        RenderSystem.assertOnRenderThread();
        REGISTRY.release(texture);
    }

    public static Registration observe(CustomTexture texture, Runnable invalidated) {
        RenderSystem.assertOnRenderThread();
        return REGISTRY.observe(texture, invalidated);
    }

    /** Exact host registration, independent of an activation holder's reachability. */
    public interface Registration {
        boolean live();
        TextureHolder acquire();
        void release();
    }

    public static void tick() {
        RenderSystem.assertOnRenderThread();
        REGISTRY.tick();
    }

    static final class Registry {
        private static final int MAX_MILLI = 20;

        private final Host host;
        private final IdentityHashMap<AbstractTexture, TextureRegistration> registrations = new IdentityHashMap<>();
        private final Queue<CleanupEvent> cleanupEvents = new ConcurrentLinkedQueue<>();
        private final Queue<RemovalEvent> pendingRemovals = new ArrayDeque<>();
        private long counter;

        Registry(Host host) {
            this.host = host;
        }

        TextureHolder register(AbstractTexture texture, int removingDelayTicks) {
            var registration = registrations.get(texture);
            if (registration == null) {
                registration = new TextureRegistration(new TextureRegistrationState<>(nextId()));
                registrations.put(texture, registration);
            } else {
                if (!registration.live)
                    throw new IllegalStateException("Texture registration was invalidated");
                var current = registration.holder();
                if (current != null && registration.state.isActive(current.token)) {
                    return current;
                }
            }

            registration.delay = removingDelayTicks;
            var activation = registration.state.activate(removingDelayTicks);
            if (!activation.registered()) {
                try {
                    host.register(registration.state.id(), texture);
                } catch (RuntimeException | Error failure) {
                    discardIfUnregistered(texture, registration, registration.state.release(activation.token()));
                    throw failure;
                }
                if (!registration.state.markRegistered(activation.token())) {
                    throw new IllegalStateException("Standalone texture registration lost ownership");
                }
            }

            var holder = new TextureHolderImpl(registration.state.id(), activation.token());
            registration.holder(holder);
            CleanerUtil.ref(holder, new CleanupEvent(texture, activation.token()), cleanupEvents::add);
            return holder;
        }

        Registration observe(CustomTexture texture, Runnable invalidated) {
            var registration = registrations.get(texture);
            if (registration == null)
                throw new IllegalStateException("Texture has no host registration");
            registration.invalidated = invalidated;
            texture.onInvalidated(registration::invalidate);
            return new Registration() {
                @Override
                public boolean live() {
                    return registration.live && registrations.get(texture) == registration
                        && texture.failure().isEmpty();
                }

                @Override
                public TextureHolder acquire() {
                    if (!live())
                        throw new IllegalStateException("Texture registration is no longer consumable");
                    return register(texture, registration.delay);
                }

                @Override
                public void release() {
                    if (registrations.get(texture) == registration)
                        Registry.this.release(texture);
                }
            };
        }

        void release(AbstractTexture texture) {
            var registration = registrations.get(texture);
            if (registration == null) {
                return;
            }
            registration.clearHolder();
            discardIfUnregistered(texture, registration, registration.state.releaseCurrent());
        }

        void tick() {
            CleanupEvent cleanup;
            while ((cleanup = cleanupEvents.poll()) != null) {
                var registration = registrations.get(cleanup.texture());
                if (registration != null) {
                    discardIfUnregistered(cleanup.texture(), registration, registration.state.release(cleanup.token()));
                }
            }

            for (var entry : registrations.entrySet()) {
                entry.getValue().state.tickRemoval()
                    .ifPresent(token -> pendingRemovals.add(new RemovalEvent(entry.getKey(), token)));
            }

            var stopWatch = StopWatch.createStarted();
            while (true) {
                var removal = pendingRemovals.poll();
                if (removal == null) {
                    return;
                }
                var registration = registrations.get(removal.texture());
                if (registration != null && registration.state.shouldRelease(removal.token())) {
                    host.release(registration.state.id());
                    if (registration.state.markReleased(removal.token())) {
                        registrations.remove(removal.texture());
                        registration.invalidate();
                    }
                }
                if (stopWatch.getTime() >= MAX_MILLI) {
                    return;
                }
            }
        }

        private void discardIfUnregistered(AbstractTexture texture, TextureRegistration registration,
            TextureRegistrationState.ReleaseResult result) {
            if (result == TextureRegistrationState.ReleaseResult.DISCARD
                && registrations.get(texture) == registration) {
                registrations.remove(texture);
                registration.invalidate();
            }
        }

        @SuppressWarnings("removal")
        private ResourceLocation nextId() {
            return new ResourceLocation(YesSteveModel.MOD_ID, "textures/" + ++counter);
        }
    }

    interface Host {
        void register(ResourceLocation id, AbstractTexture texture);

        void release(ResourceLocation id);
    }

    private static final class MinecraftHost implements Host {
        @Override
        public void register(ResourceLocation id, AbstractTexture texture) {
            Minecraft.getInstance().getTextureManager().register(id, texture);
        }

        @Override
        public void release(ResourceLocation id) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    private static final class TextureRegistration {
        private final TextureRegistrationState<ResourceLocation> state;
        private WeakReference<TextureHolderImpl> holder;
        private int delay;
        private boolean live = true;
        private Runnable invalidated = () -> {};

        private void invalidate() {
            if (!live)
                return;
            live = false;
            var listener = invalidated;
            invalidated = () -> {};
            listener.run();
        }

        private TextureRegistration(TextureRegistrationState<ResourceLocation> state) {
            this.state = state;
        }

        private TextureHolderImpl holder() {
            return holder == null ? null : holder.get();
        }

        private void holder(TextureHolderImpl holder) {
            this.holder = new WeakReference<>(holder);
        }

        private void clearHolder() {
            holder = null;
        }
    }

    private record CleanupEvent(AbstractTexture texture, TextureRegistrationState.Token token) {
    }

    private record RemovalEvent(AbstractTexture texture, TextureRegistrationState.Token token) {
    }

    private record TextureHolderImpl(ResourceLocation id,
        TextureRegistrationState.Token token) implements TextureHolder {
    }
}
