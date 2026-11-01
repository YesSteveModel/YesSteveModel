package com.elfmcys.ysm.client.gui.overlay;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.processor.DebugInfo;
import dev.semaweave.ProgramKey;
import dev.semaweave.compilation.ProgramReplacement;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class DebugAnimationScreen {
    private static final DebugInfo DEBUG_INFO = new DebugInfo();
    private static final ReferenceArrayList<String> DEBUG_CONTROLLERS = new ReferenceArrayList<>();
    private static final ReplacementNoticeTracker REPLACEMENT_NOTICES = new ReplacementNoticeTracker();
    private static volatile WeakReference<AnimatableEntity<?>> TARGET = null;

    public static IGuiOverlay getGuiOverlay() {
        return (ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) -> {
            renderCustom(gui, graphics, screenWidth, screenHeight);
        };
    }

    public static DebugInfo getDebugInfo() {
        return DEBUG_INFO;
    }

    public static boolean isEnabled() {
        return getTarget() != null;
    }

    public static boolean enable() {
        if (Minecraft.getInstance().hitResult instanceof EntityHitResult result) {
            return enable(result.getEntity());
        }
        return enableForLocalPlayer();
    }

    public static boolean enableForLocalPlayer() {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(DebugAnimationScreen::enable);
            return true;
        }
        disable();
        return false;
    }

    public static boolean enable(Entity entity) {
        LazyOptional<? extends AnimatableEntity<?>> opt;
        if (entity instanceof Player) {
            opt = entity.getCapability(PlayerAnimatableCapabilityProvider.CAP);
        } else if (entity instanceof Projectile) {
            opt = entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP);
        } else {
            opt = entity.getCapability(VehicleAnimatableCapabilityProvider.CAP);
        }
        return opt.map(cap -> {
            DebugAnimationScreen.enable(cap);
            return true;
        }).orElseGet(() -> {
            disable();
            return false;
        });
    }

    public static void enable(AnimatableEntity<?> customEntity) {
        disable();
        TARGET = new WeakReference<>(customEntity);
        customEntity.setDebugInfo(DEBUG_INFO);
        var entity = customEntity.getEntity();
        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null) {
            localPlayer.sendSystemMessage(Component.translatable("message.yes_steve_model.model.debug_animation.true")
                .append(" -> ").append(Objects.requireNonNullElseGet(entity.getCustomName(), entity::getDisplayName)));
        }
    }

    public static void disable() {
        REPLACEMENT_NOTICES.reset();
        if (TARGET != null) {
            var target = TARGET.get();
            if (target != null) {
                target.setDebugInfo(null);
            }
            TARGET = null;
            var localPlayer = Minecraft.getInstance().player;
            if (localPlayer != null) {
                localPlayer
                    .sendSystemMessage(Component.translatable("message.yes_steve_model.model.debug_animation.false"));
            }
        }
    }

    public static void addDebugController(String controllerName) {
        DEBUG_CONTROLLERS.add(0, controllerName);
    }

    public static void clearDebugController() {
        DEBUG_CONTROLLERS.clear();
    }

    @Nullable
    public static AnimatableEntity<?> getTarget() {
        var selected = TARGET;
        if (selected != null) {
            var entity = selected.get();
            if (entity != null && entity.isActive()) {
                return entity;
            }
        }
        return null;
    }

    @SuppressWarnings("all")
    private static void renderCustom(ForgeGui gui, GuiGraphics graphics, int screenWidth, int screenHeight) {
        AnimatableEntity<?> target = getTarget();
        if (target == null) {
            // Detach stale state on the HUD thread, not from animation-worker queries.
            disable();
            return;
        }

        target.waitForAsyncUpdate();
        observeReplacementNotices(target);

        int[] y = {5};

        DebugInfo debugInfo = DEBUG_INFO;
        debugInfo.enumerate((name, result) -> {
            renderCustomText(gui, graphics, y, name, result, screenWidth, screenHeight);
        });

        // 渲染状态机信息
        if (!target.hasInstalledModel())
            return;
        DEBUG_CONTROLLERS.forEach(name -> {
            IAnimationController controller = target.getAnimationData().getAnimationController(name);
            renderCustomText(gui, graphics, y, name, controller != null ? controller.getStateName() : "(N/A)",
                screenWidth, screenHeight);
        });
    }

    private static void observeReplacementNotices(AnimatableEntity<?> target) {
        var renderTarget = target.getModelRenderTarget();
        if (renderTarget == null) {
            REPLACEMENT_NOTICES.awaitResource();
            return;
        }
        var module = renderTarget.expressions();
        REPLACEMENT_NOTICES.observe(module.generation(), module.compilationReport().replacements(),
            module.programLocations(), notice -> sendReplacementNotice(target, module, notice));
    }

    private static boolean sendReplacementNotice(AnimatableEntity<?> target, RenderTargetExpressionModule module,
        ReplacementNotice notice) {
        var selected = TARGET;
        var renderTarget = target.getModelRenderTarget();
        var minecraft = Minecraft.getInstance();
        if (selected == null || selected.get() != target || !target.isActive() || renderTarget == null
            || renderTarget.expressions() != module || minecraft.player == null) {
            return false;
        }
        var replacement = notice.replacement();
        minecraft.player.sendSystemMessage(Component.translatable(
            "message.yes_steve_model.model.debug_animation.replacement", displayLocation(notice.location()),
            replacement.result().name(), replacement.reason().name(), replacement.nodePath(), replacement.message()));
        return true;
    }

    private static String displayLocation(ModelProgramInventory.SemanticLocation location) {
        var discriminator = location.discriminator().isEmpty() ? "" : ":" + location.discriminator();
        return location.targetId() + "/" + location.location() + " [" + location.role() + discriminator + "]";
    }

    record ReplacementNotice(ModelProgramInventory.SemanticLocation location, ProgramReplacement replacement) {
        ReplacementNotice {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(replacement, "replacement");
        }
    }

    static final class ReplacementNoticeTracker {
        private Object resourceIdentity;
        private int nextReplacement;

        void observe(Object resourceIdentity, List<ProgramReplacement> replacements,
            Map<ProgramKey, ModelProgramInventory.SemanticLocation> locations, Predicate<ReplacementNotice> sender) {
            Objects.requireNonNull(resourceIdentity, "resourceIdentity");
            Objects.requireNonNull(replacements, "replacements");
            Objects.requireNonNull(locations, "locations");
            Objects.requireNonNull(sender, "sender");
            if (this.resourceIdentity != resourceIdentity) {
                this.resourceIdentity = resourceIdentity;
                nextReplacement = 0;
            }
            while (nextReplacement < replacements.size()) {
                var replacement = replacements.get(nextReplacement);
                var location = locations.get(replacement.programKey());
                if (location == null) {
                    throw new IllegalStateException("Replacement has no current semantic location");
                }
                if (!sender.test(new ReplacementNotice(location, replacement))) {
                    return;
                }
                nextReplacement++;
            }
        }

        void awaitResource() {
            reset();
        }

        void reset() {
            resourceIdentity = null;
            nextReplacement = 0;
        }
    }

    private static void renderCustomText(ForgeGui gui, GuiGraphics graphics, int[] y, String name, String result,
        int screenWidth, int screenHeight) {
        Font font = gui.getFont();
        if ((y[0] - 5) % 20 == 0) {
            graphics.fill(2, y[0] - 1, screenWidth, y[0] + 9, 0xc0505050);
        } else {
            graphics.fill(2, y[0] - 1, screenWidth, y[0] + 9, 0xc0506050);
        }
        graphics.drawString(font, name, 5, y[0], 0xffffff);
        graphics.drawString(font, result, screenWidth / 2, y[0], 0xffffff);
        y[0] = y[0] + 10;
    }
}
