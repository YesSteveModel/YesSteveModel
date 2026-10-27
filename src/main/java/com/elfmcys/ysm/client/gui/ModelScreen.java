package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/** A model browser and its child screens cannot outlive their client world. */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = YesSteveModel.MOD_ID)
public abstract class ModelScreen extends Screen {
    private static @Nullable Lifetime active;
    private static WeakReference<ClientLevel> unloadedLevel = new WeakReference<>(null);
    private final @Nullable Lifetime lifetime;

    protected ModelScreen(Component title) {
        super(title);
        lifetime = new Lifetime(Minecraft.getInstance().level);
    }

    protected ModelScreen(Component title, @Nullable ModelScreen parent) {
        super(title);
        lifetime = parent == null ? null : parent.lifetime;
    }

    public final boolean hasCurrentWorld() {
        return lifetime == null || lifetime.isCurrent();
    }

    public final void showScreen(@Nullable Screen screen) {
        if (!hasCurrentWorld())
            return;
        // Vanilla dialogs must belong to the same flow even though they do not extend ModelScreen.
        if (lifetime != null) {
            if (screen == null)
                lifetime.retire();
            else
                lifetime.displayed = new WeakReference<>(screen);
        }
        var minecraft = Minecraft.getInstance();
        try {
            minecraft.setScreen(screen);
        } finally {
            // A cancelled opening keeps the source in the same world-bound flow.
            if (lifetime != null && lifetime.isCurrent() && minecraft.screen == this)
                lifetime.displayed = new WeakReference<>(this);
        }
    }

    @Override
    protected void clearWidgets() {
        // clearWidgets does not clear the focused child, which may own a preview entity.
        setFocused(null);
        super.clearWidgets();
    }

    @Override
    public void removed() {
        clearWidgets();
        if (lifetime != null && lifetime.displayed.get() == this)
            lifetime.retire();
        super.removed();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof ModelScreen screen) || screen.lifetime == null)
            return;
        if (!screen.hasCurrentWorld()) {
            event.setCanceled(true);
            ResourceDiagnostics.log("model GUI opening rejected: screen={} reason=world-ended-or-unavailable",
                screen.getClass().getSimpleName());
            return;
        }
        if (active != null && active != screen.lifetime)
            active.retire();
        active = screen.lifetime;
        active.displayed = new WeakReference<>(screen);
    }

    public static void unload(ClientLevel level) {
        // Forge posts Unload before Minecraft replaces or clears its level field.
        unloadedLevel = new WeakReference<>(level);
        if (ResourceDiagnostics.isEnabled())
            ResourceDiagnostics.track(ResourceDiagnostics.Kind.CLIENT_LEVEL, level,
                "client level " + level.dimension().location());
        var closing = active;
        if (closing == null || closing.level.get() != level)
            return;
        var screen = closing.displayed.get();
        closing.retire();
        if (screen != null && Minecraft.getInstance().screen == screen)
            Minecraft.getInstance().setScreen(null);
        ResourceDiagnostics.log("model GUI world retired: world={}", System.identityHashCode(level));
    }

    private static final class Lifetime {
        private final WeakReference<ClientLevel> level;
        private WeakReference<Screen> displayed = new WeakReference<>(null);
        private boolean retired;

        private Lifetime(@Nullable ClientLevel level) {
            this.level = new WeakReference<>(level);
        }

        private boolean isCurrent() {
            var current = Minecraft.getInstance().level;
            return !retired && current != null && level.get() == current && unloadedLevel.get() != current;
        }

        private void retire() {
            retired = true;
            level.clear();
            displayed.clear();
            if (active == this)
                active = null;
        }
    }
}
