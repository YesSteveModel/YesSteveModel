package com.elfmcys.ysm.util;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;

public class CommandUtil {
    private static final String MESSAGE_HEADER = "§6§l【§aYSM§6§l】§r";

    public static Component wrapMessage(Component message) {
        return Component.literal(MESSAGE_HEADER).append(message);
    }

    // 是否为单人模式，或联机模式下的房主
    public static boolean isLocalPlayer(Entity player) {
        return player != null && FMLEnvironment.dist == Dist.CLIENT
            && player.getUUID().equals(Minecraft.getInstance().getUser().getGameProfile().getId());
    }

    public static boolean hasPermission(@Nullable Entity source, int level) {
        if (source == null) {
            return false;
        }
        if (source.hasPermissions(level)) {
            return true;
        }
        if (isLocalPlayer(source)) {
            return true;
        }
        return false;
    }

    public static boolean hasPermission(CommandSourceStack source, int level) {
        if (source.hasPermission(level)) {
            return true;
        }
        if (source.getEntity() != null && isLocalPlayer(source.getEntity())) {
            return true;
        }
        return false;
    }

    public static void sendAsyncFeedback(@Nullable final CommandSourceStack oldSource, final Component feedbackMsg,
        final boolean allowLogging) {
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            CommandSourceStack commandSource = null;
            if (oldSource != null && oldSource.getEntity() instanceof ServerPlayer) {
                ServerPlayer newSender = server.getPlayerList().getPlayer(oldSource.getEntity().getUUID());
                if (newSender != null) {
                    commandSource = newSender.createCommandSourceStack();
                }
            }
            if (commandSource == null) {
                commandSource = server.createCommandSourceStack();
            }
            commandSource.sendSuccess(() -> feedbackMsg, allowLogging);
        });
    }
}
