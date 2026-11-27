package com.elfmcys.ysm.client.command.sub;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController.Kind;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController.Layer;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public final class PhysicsCommand {
    private static final String TARGET = "target";
    private static final String NODE = "nodeId";
    private static final String NODE_A = "nodeA";
    private static final String NODE_B = "nodeB";
    private static final String CONSTRAINT = "constraintId";
    private static final String PAGE = "page";
    private static final String MESSAGE_PREFIX = "commands.ysmclient.physics.";
    private static final SimpleCommandExceptionType PLAYER_NOT_FOUND =
        new SimpleCommandExceptionType(Component.translatable("argument.entity.notfound.player"));
    private static final SimpleCommandExceptionType DEBUG_DISABLED =
        new SimpleCommandExceptionType(Component.translatable(MESSAGE_PREFIX + "debug_disabled"));
    private static final SimpleCommandExceptionType INVALID_SELECTION =
        new SimpleCommandExceptionType(Component.translatable(MESSAGE_PREFIX + "invalid_selection"));
    private static final SimpleCommandExceptionType NO_DEGRADATION =
        new SimpleCommandExceptionType(Component.translatable(MESSAGE_PREFIX + "no_degradation"));

    private PhysicsCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        var layers = Commands.literal("layers");
        for (var layer : Layer.values()) {
            layers.then(Commands.literal(layer.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
                requireDebug();
                PhysicsDebugController.setLayer(layer);
                return Command.SINGLE_SUCCESS;
            }));
        }
        return Commands.literal("physics")
            .then(Commands.literal("debug").executes(ctx -> debug(ctx, "@s"))
                .then(Commands.literal("off").executes(PhysicsCommand::off))
                .then(Commands.argument(TARGET, StringArgumentType.greedyString()).suggests(targets())
                    .executes(ctx -> debug(ctx, StringArgumentType.getString(ctx, TARGET)))))
            .then(Commands.literal("select")
                .then(Commands.literal("node")
                    .then(Commands.argument(NODE, StringArgumentType.string()).suggests(ids(Kind.NODE))
                        .executes(ctx -> select(ctx, Kind.NODE, NODE, null))))
                .then(Commands.literal("pair")
                    .then(Commands.argument(NODE_A, StringArgumentType.string()).suggests(ids(Kind.NODE))
                        .then(Commands.argument(NODE_B, StringArgumentType.string()).suggests(ids(Kind.NODE))
                            .executes(ctx -> select(ctx, Kind.PAIR, NODE_A, NODE_B)))))
                .then(
                    Commands.literal("constraint")
                        .then(
                            Commands.argument(CONSTRAINT, StringArgumentType.string()).suggests(ids(Kind.CONSTRAINT))
                                .executes(ctx -> select(ctx, Kind.CONSTRAINT, CONSTRAINT, null)))))
            .then(layers)
            .then(Commands.literal(PAGE).then(Commands.literal("next").executes(ctx -> stepPage(1)))
                .then(Commands.literal("prev").executes(ctx -> stepPage(-1)))
                .then(Commands.argument(PAGE, IntegerArgumentType.integer(1))
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        IntStream.rangeClosed(1, PhysicsDebugController.pageCount()).mapToObj(Integer::toString),
                        builder))
                    .executes(ctx -> {
                        requireDebug();
                        PhysicsDebugController.setPage(IntegerArgumentType.getInteger(ctx, PAGE));
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("retry").executes(ctx -> retry(ctx, null))
                .then(Commands.argument(TARGET, StringArgumentType.greedyString()).suggests(targets())
                    .executes(ctx -> retry(ctx, StringArgumentType.getString(ctx, TARGET)))));
    }

    private static int debug(CommandContext<CommandSourceStack> context, String target) throws CommandSyntaxException {
        var owner = player(target);
        PhysicsDebugController.enable(owner);
        context.getSource()
            .sendSuccess(() -> Component.translatable(MESSAGE_PREFIX + "enabled", owner.getEntity().getName()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int off(CommandContext<CommandSourceStack> context) {
        PhysicsDebugController.disable();
        context.getSource().sendSuccess(() -> Component.translatable(MESSAGE_PREFIX + "disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int select(CommandContext<CommandSourceStack> context, Kind kind, String first,
        @Nullable String second) throws CommandSyntaxException {
        requireDebug();
        if (!PhysicsDebugController.select(kind, StringArgumentType.getString(context, first),
            second == null ? null : StringArgumentType.getString(context, second)))
            throw INVALID_SELECTION.create();
        return Command.SINGLE_SUCCESS;
    }

    private static int retry(CommandContext<CommandSourceStack> context, @Nullable String name)
        throws CommandSyntaxException {
        var selected = PhysicsDebugController.target();
        var owner = name != null ? player(name) : selected == null ? player("@s") : selected;
        if (!owner.retryPhysics())
            throw NO_DEGRADATION.create();
        context.getSource()
            .sendSuccess(() -> Component.translatable(MESSAGE_PREFIX + "retry", owner.getEntity().getName()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static void requireDebug() throws CommandSyntaxException {
        if (PhysicsDebugController.target() == null)
            throw DEBUG_DISABLED.create();
    }

    private static int stepPage(int delta) throws CommandSyntaxException {
        requireDebug();
        PhysicsDebugController.stepPage(delta);
        return Command.SINGLE_SUCCESS;
    }

    private static AnimatableEntity<?> player(String name) throws CommandSyntaxException {
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        var local = minecraft.player;
        if (level == null || local == null)
            throw PLAYER_NOT_FOUND.create();
        String target = name.trim();
        if (target.equals("@s"))
            return local.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElseThrow(PLAYER_NOT_FOUND::create);
        for (var player : level.players()) {
            if (target.equalsIgnoreCase(player.getGameProfile().getName())
                || target.equalsIgnoreCase(player.getStringUUID()) || target.equals(Integer.toString(player.getId())))
                return player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                    .orElseThrow(PLAYER_NOT_FOUND::create);
        }
        throw PLAYER_NOT_FOUND.create();
    }

    private static SuggestionProvider<CommandSourceStack> targets() {
        return (context, builder) -> {
            var names = new ArrayList<String>();
            var level = Minecraft.getInstance().level;
            if (level != null) {
                names.add("@s");
                for (var player : level.players())
                    names.add(player.getGameProfile().getName());
            }
            return SharedSuggestionProvider.suggest(names, builder);
        };
    }

    private static SuggestionProvider<CommandSourceStack> ids(Kind kind) {
        return (context, builder) -> {
            var metadata = PhysicsDebugController.metadata();
            if (metadata == null)
                return Suggestions.empty();
            var values = kind == Kind.CONSTRAINT ? metadata.constraints().stream().map(PhysicsConstraintProto::id)
                : metadata.nodes().stream().map(PhysicsNodeProto::id);
            String remaining = builder.getRemainingLowerCase();
            String prefix =
                remaining.startsWith("\"") || remaining.startsWith("'") ? remaining.substring(1) : remaining;
            values.forEach(value -> {
                var quoted = StringArgumentType.escapeIfRequired(value);
                if (value.toLowerCase(Locale.ROOT).startsWith(prefix)
                    || quoted.toLowerCase(Locale.ROOT).startsWith(remaining))
                    builder.suggest(quoted);
            });
            return builder.buildFuture();
        };
    }
}
