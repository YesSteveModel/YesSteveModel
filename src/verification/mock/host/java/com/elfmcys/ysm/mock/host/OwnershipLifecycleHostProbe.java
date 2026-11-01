package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.gui.PlayerModelScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Exercises page removal and an undrawn world's active-retirement path. */
final class OwnershipLifecycleHostProbe {
    private final Minecraft minecraft;
    private final boolean page;
    private int ticks;
    private PlayerModelScreen screen;
    private net.minecraft.client.player.RemotePlayer remote;
    private AnimatableEntity<?> owner;
    private EntityUseProbe lease;
    private boolean removed;

    OwnershipLifecycleHostProbe(Minecraft minecraft, boolean page) {
        this.minecraft = minecraft;
        this.page = page;
    }

    Map<String, ?> poll() throws Exception {
        if (++ticks > 300) throw new IllegalStateException("Ownership lifecycle fixture timed out");
        if (page) return page();
        if (remote == null) {
            remote = new net.minecraft.client.player.RemotePlayer(minecraft.level,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString("0b739a3d-d6be-4bba-9d03-96ff810465b7"),
                    "UndrawnOwner"));
            remote.setPos(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ() + 2);
            minecraft.level.addPlayer(-789321, remote);
            var capability = remote.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                .orElseThrow(IllegalStateException::new);
            owner = capability;
            var player = minecraft.player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                .orElseThrow(IllegalStateException::new);
            capability.updateModelAndTexture(player.getModelHash(), player.getTextureName());
            return null;
        }
        if (!removed) {
            owner.waitForAsyncUpdate();
            if (!owner.hasInstalledModel()) return null;
            if (lease == null) {
                lease = EntityUseProbe.watch(owner);
                remote.setPos(1_000_000, 100, 1_000_000);
                return null;
            }
            minecraft.level.removeEntity(remote.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            removed = true;
            return null;
        }
        if (owner.isAssigned()) return null;
        if (owner.hasPendingUpdate() || owner.getExpressionRuntime().module().isPresent())
            throw new AssertionError("Undrawn owner retained execution state");
        var facts = lease.snapshot();
        if (!facts.get("leaseCloses").equals(1)) throw new AssertionError("Undrawn owner lease close count");
        return Map.of("undrawn", true, "worldRemovalObserved", true, "lease", facts,
            "assigned", false, "runtimeInstalled", false);
    }

    private Map<String, ?> page() throws Exception {
        if (screen == null) {
            screen = new PlayerModelScreen();
            minecraft.setScreen(screen);
            var field = PlayerModelScreen.class.getDeclaredField("textField");
            field.setAccessible(true);
            ((net.minecraft.client.gui.components.EditBox)field.get(screen)).setValue("molang/fault");
            var initialize = PlayerModelScreen.class.getDeclaredMethod("init");
            initialize.setAccessible(true);
            initialize.invoke(screen);
            return null;
        }
        if (ticks < 30) return null;
        var field = PlayerModelScreen.class.getDeclaredField("modelButtons");
        field.setAccessible(true);
        var owners = new ArrayList<AnimatableEntity<?>>();
        var leases = new ArrayList<EntityUseProbe>();
        for (var card : (List<?>)field.get(screen)) {
            var widget = (net.minecraft.client.gui.components.AbstractWidget)card;
            var window = minecraft.getWindow();
            double pointerX = widget.getX() + 10;
            double pointerY = widget.getY() + 10;
            var mouseX = minecraft.mouseHandler.getClass().getDeclaredField("xpos");
            var mouseY = minecraft.mouseHandler.getClass().getDeclaredField("ypos");
            mouseX.setAccessible(true);
            mouseY.setAccessible(true);
            mouseX.setDouble(minecraft.mouseHandler, pointerX * window.getScreenWidth() / window.getGuiScaledWidth());
            mouseY.setDouble(minecraft.mouseHandler, pointerY * window.getScreenHeight() / window.getGuiScaledHeight());
            screen.mouseMoved(pointerX, pointerY);
            var entity = card.getClass().getDeclaredField("entity");
            entity.setAccessible(true);
            var owner = (AnimatableEntity<?>)entity.get(card);
            if (!owner.hasInstalledModel()) continue;
            owners.add(owner);
            leases.add(EntityUseProbe.watch(owner));
        }
        if (owners.isEmpty()) return null;
        minecraft.setScreen(null);
        for (var owner : owners)
            if (owner.isAssigned() || owner.getExpressionRuntime().module().isPresent())
                throw new AssertionError("Removed page retained its card owner");
        for (var lease : leases)
            if (!lease.snapshot().get("leaseCloses").equals(1)) throw new AssertionError("Page lease close count");
        return Map.of("closedByMinecraftSetScreen", true, "owners", owners.size(),
            "leases", leases.stream().map(EntityUseProbe::snapshot).toList());
    }
}
