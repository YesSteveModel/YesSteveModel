package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.event.CommandRegistry;
import com.elfmcys.ysm.model.service.ModelExportService;
import com.elfmcys.ysm.model.service.ModelWorkSummary;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ExportCommand {
    private static final String MODEL_ID = "model_id";
    private static final String EXTRA = "extra";
    private static final String PATTERN = "reg";

    private ExportCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("export").requires(source -> source.hasPermission(2))
            .then(Commands.argument(MODEL_ID, StringArgumentType.string()).suggests(CommandRegistry.ALL_MODELS)
                .executes(context -> export(context, null))
                .then(Commands.argument(EXTRA, StringArgumentType.greedyString())
                    .executes(context -> export(context, StringArgumentType.getString(context, EXTRA)))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> getAll() {
        return Commands.literal("export-all").requires(source -> source.hasPermission(2))
            .executes(context -> exportAll(context, null))
            .then(Commands.argument(PATTERN, StringArgumentType.greedyString())
                .executes(context -> exportAll(context, parsePattern(StringArgumentType.getString(context, PATTERN)))));
    }

    static String parsePattern(String value) throws CommandSyntaxException {
        var reader = new StringReader(value);
        if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) {
            var pattern = reader.readString();
            if (reader.canRead())
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
            return pattern;
        }
        while (reader.canRead()) {
            if (Character.isWhitespace(reader.read()))
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        }
        return value;
    }

    private static int export(CommandContext<CommandSourceStack> context, String extra) {
        var source = context.getSource();
        try {
            ModelExportService.environment(source);
            ModelExportService.export(source, StringArgumentType.getString(context, MODEL_ID), extra)
                .whenComplete((output, failure) -> source.getServer().execute(() -> {
                    if (failure == null)
                        source.sendSuccess(
                            () -> Component.translatable("commands.yes_steve_model.export.success", output), false);
                    else
                        fail(source, failure);
                }));
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException failure) {
            fail(source, failure);
            return 0;
        }
    }

    private static int exportAll(CommandContext<CommandSourceStack> context, String pattern) {
        var source = context.getSource();
        try {
            var accepted = ModelExportService.environment(source).exportAll(pattern,
                item -> source.sendSuccess(
                    () -> Component.literal(item.path() + ": " + item.outcome() + " — " + item.reason()), false),
                summary -> source.sendSuccess(() -> Component.literal(summaryText(summary)), false));
            if (!accepted) {
                source.sendFailure(Component.literal("Export-all is BUSY"));
                return 0;
            }
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException failure) {
            fail(source, failure);
            return 0;
        }
    }

    private static String summaryText(ModelWorkSummary summary) {
        return "Export-all: total=" + summary.total() + ", success=" + summary.success() + ", failed="
            + summary.failed() + ", skipped=" + summary.skipped() + ", unprocessed=" + summary.unprocessed()
            + (summary.stopped() ? " (stopped)" : "");
    }

    private static void fail(CommandSourceStack source, Throwable failure) {
        com.elfmcys.ysm.YesSteveModel.LOGGER.error("Model export command failed", failure);
        source.sendFailure(Component.translatable("commands.yes_steve_model.export.failure",
            com.elfmcys.ysm.model.domain.PublicFailure.summary(failure)));
    }
}
