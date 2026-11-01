package com.elfmcys.ysm.client.command.sub;

import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;

public class DebugCommand {
    private static final String DEBUG_NAME = "debug";
    private static final String TARGET_NAME = "target";
    private static final SimpleCommandExceptionType ENTITY_NOT_FOUND =
        new SimpleCommandExceptionType(Component.translatable("argument.entity.notfound.entity"));

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(DEBUG_NAME)
            .executes(ctx -> enableDebugging("@s"))
            .then(Commands.argument(TARGET_NAME, StringArgumentType.greedyString())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(targets(), builder))
                .executes(ctx -> enableDebugging(StringArgumentType.getString(ctx, TARGET_NAME))));
    }

    private static int enableDebugging(String target) throws CommandSyntaxException {
        target = target.trim();
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null)
            throw ENTITY_NOT_FOUND.create();
        Entity selected = target.equals("@s") ? minecraft.player : null;
        // Server selectors cannot resolve against a client command source.
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (target.equalsIgnoreCase(entity.getStringUUID())
                || entity instanceof Player player && target.equalsIgnoreCase(player.getGameProfile().getName())) {
                selected = entity;
                break;
            }
        }
        if (selected == null) {
            try {
                selected = minecraft.level.getEntity(Integer.parseInt(target));
            } catch (NumberFormatException ignored) {}
        }
        if (selected == null)
            throw ENTITY_NOT_FOUND.create();
        return DebugAnimationScreen.enable(selected) ? Command.SINGLE_SUCCESS : 0;
    }

    private static Iterable<String> targets() {
        var targets = new ArrayList<String>();
        targets.add("@s");
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (var entity : level.entitiesForRendering()) {
                targets.add(Integer.toString(entity.getId()));
                if (entity instanceof Player player)
                    targets.add(player.getGameProfile().getName());
            }
        }
        return targets;
    }
}
