package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ControlHandler;
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

public class MolangCommand {
    private static final String MOLANG_NAME = "molang";
    private static final String EXECUTE_NAME = "execute";
    private static final String EXPRESSION_NAME = "exp";
    private static final String TARGETS_NAME = "targets";

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        LiteralArgumentBuilder<CommandSourceStack> molang =
            Commands.literal(MOLANG_NAME).requires(src -> CommandUtil.hasPermission(src, 2));
        LiteralArgumentBuilder<CommandSourceStack> execute = Commands.literal(EXECUTE_NAME);

        RequiredArgumentBuilder<CommandSourceStack, String> exp =
            Commands.argument(EXPRESSION_NAME, StringArgumentType.greedyString());
        RequiredArgumentBuilder<CommandSourceStack, EntitySelector> targets =
            Commands.argument(TARGETS_NAME, EntityArgument.players());

        molang.then(execute.then(targets.then(exp.executes(MolangCommand::executeMolang))));

        return molang;
    }

    private static int executeMolang(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String molangExp = StringArgumentType.getString(ctx, EXPRESSION_NAME);
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, TARGETS_NAME);
        return executeMolangOnPlayer(molangExp, targets);
    }

    private static int executeMolangOnPlayer(String molangExp, Collection<ServerPlayer> players) {
        NetworkHandler.broadcastToAllPlayers(ControlHandler.privilegedTransient(molangExp, players));
        return Command.SINGLE_SUCCESS;
    }
}
