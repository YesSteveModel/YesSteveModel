package com.elfmcys.ysm.client.command.sub;

import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;

public class DebugCommand {
    private static final String DEBUG_NAME = "debug";
    private static final String TARGET_NAME = "target";

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(DEBUG_NAME)
            .then(Commands.argument(TARGET_NAME, EntityArgument.entity()).executes(DebugCommand::enableDebugging));
    }

    @SuppressWarnings("DataFlowIssue")
    private static int enableDebugging(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Minecraft mc = Minecraft.getInstance();
        var entity = EntityArgument.getEntity(ctx, TARGET_NAME);
        var clientEntity = mc.level.getEntity(entity.getId());
        if (DebugAnimationScreen.enable(clientEntity)) {
            return Command.SINGLE_SUCCESS;
        }
        return 0;
    }
}
