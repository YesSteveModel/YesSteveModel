package com.elfmcys.ysm.command;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.command.sub.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RootCommand {
    private static final String ROOT_NAME = "ysm";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_NAME);
        root.then(ModelCommand.get());
        root.then(AuthCommand.get());
        root.then(ExportCommand.get());
        root.then(ExportCommand.getAll());
        root.then(PlayAnimationCommand.get());
        root.then(MolangCommand.get());
        root.then(PingCommand.get());
        dispatcher.register(root);
    }

    public static void registerPlaceholder(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_NAME);
        root.then(Commands.argument("any", StringArgumentType.greedyString()).executes(ctx -> {
            if (ctx.getSource().isPlayer()) {
                ctx.getSource().sendSystemMessage(YesSteveModel.getUnavailableMessage());
            } else {
                ctx.getSource().sendSystemMessage(Component.literal(YesSteveModel.getUnavailableMessageString()));
            }
            return Command.SINGLE_SUCCESS;
        }));
        dispatcher.register(root);
    }
}
