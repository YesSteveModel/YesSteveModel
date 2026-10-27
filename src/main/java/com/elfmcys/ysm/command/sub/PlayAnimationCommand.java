package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.event.CommandRegistry;
import com.elfmcys.ysm.util.CommandUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

public class PlayAnimationCommand {
    private static final String PLAY_NAME = "play";
    private static final String TARGETS_NAME = "targets";
    private static final String ANIMATION_NAME = "animation";
    private static final String STOP = "stop";

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        LiteralArgumentBuilder<CommandSourceStack> play =
            Commands.literal(PLAY_NAME).requires(src -> CommandUtil.hasPermission(src, 2));
        RequiredArgumentBuilder<CommandSourceStack, EntitySelector> targets =
            Commands.argument(TARGETS_NAME, EntityArgument.players());
        RequiredArgumentBuilder<CommandSourceStack, String> animation =
            Commands.argument(ANIMATION_NAME, StringArgumentType.string()).suggests(CommandRegistry.ALL_ANIMATIONS);
        play.then(targets.then(animation.executes(PlayAnimationCommand::playAnimation)));
        return play;
    }

    private static int playAnimation(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS_NAME);
        String animation = StringArgumentType.getString(context, ANIMATION_NAME);
        targets.forEach(player -> player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(cap -> {
            if (STOP.equals(animation)) {
                cap.stopAnimation(player);
            } else {
                cap.playAnimation(player, animation);
            }
        }));
        return Command.SINGLE_SUCCESS;
    }
}
