package com.elfmcys.ysm.client.command;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class ResourceDebugCommand {
    private ResourceDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var dump = Commands.literal("dump");
        for (var kind : ResourceDiagnostics.Kind.values()) {
            dump.then(Commands.literal(kind.name().toLowerCase(Locale.ROOT)).executes(context -> {
                var count = ResourceDiagnostics.dump(kind);
                context.getSource().sendSuccess(() -> Component.literal("YSM resource dump "
                    + kind.name().toLowerCase(Locale.ROOT) + ": " + count + " live resources; see log"), false);
                return Command.SINGLE_SUCCESS;
            }));
        }
        dispatcher.register(Commands.literal("ysmdebug").then(Commands.literal("resource")
            .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                var enabled = BoolArgumentType.getBool(context, "enabled");
                ResourceDiagnostics.setEnabled(enabled);
                context.getSource().sendSuccess(() -> Component.literal("YSM resource logging: " + enabled), false);
                return Command.SINGLE_SUCCESS;
            })).then(dump)));
    }
}
