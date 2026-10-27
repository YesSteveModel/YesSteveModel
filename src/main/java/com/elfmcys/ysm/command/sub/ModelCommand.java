package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.event.CommandRegistry;
import com.elfmcys.ysm.model.catalog.source.AccessPolicy;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.util.CommandUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ModelCommand {
    private static final String TARGETS = "targets";
    private static final String MODEL_PATH = "model_path";
    private static final String TEXTURE = "texture";
    private static final String IGNORE_AUTH = "ignore_auth";

    private ModelCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        var model = Commands.literal("model").requires(source -> CommandUtil.hasPermission(source, 2));
        model.then(Commands.literal("reload").executes(ModelCommand::reload));
        model.then(Commands.literal("errors").executes(ModelCommand::errors));
        model.then(Commands.literal("gen-preview").requires(source -> source.hasPermission(2))
            .executes(ModelCommand::genPreview));
        model.then(Commands.literal("disable").then(Commands.argument(TARGETS, EntityArgument.players())
            .then(Commands.argument("value", BoolArgumentType.bool()).executes(ModelCommand::disable))));
        var set = Commands.literal("set").then(Commands.argument(TARGETS, EntityArgument.players()).then(Commands
            .argument(MODEL_PATH, StringArgumentType.string()).suggests(CommandRegistry.ALL_MODELS)
            .then(Commands.argument(TEXTURE, StringArgumentType.string()).suggests(CommandRegistry.ALL_TEXTURES)
                .executes(context -> set(context, false)).then(Commands.argument(IGNORE_AUTH, BoolArgumentType.bool())
                    .executes(context -> set(context, BoolArgumentType.getBool(context, IGNORE_AUTH)))))));
        model.then(set);
        return model;
    }

    private static int genPreview(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        if (!source.hasPermission(2) || !(source.getEntity() instanceof ServerPlayer player)
            || !ServerModelService.instance().startGenPreview(player)) {
            source.sendFailure(Component.translatable("message.yes_steve_model.gen_preview.no_client"));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int set(CommandContext<CommandSourceStack> context, boolean ignoreAuth)
        throws CommandSyntaxException {
        var path = StringArgumentType.getString(context, MODEL_PATH);
        var catalog = ServerModelService.instance().catalog().orElseThrow();
        var resolution = catalog.resolvePath(path);
        var model = resolution.found().flatMap(catalog::find);
        if (model.isEmpty()) {
            context.getSource().sendFailure(Component.literal(resolution.message()));
            return 0;
        }
        var handle = model.get();
        var texture = StringArgumentType.getString(context, TEXTURE);
        if ("-".equals(texture)) {
            var requested = handle.view().getMetadata().getSettings().defaultTexture().orElse("");
            texture = handle.view().getPlayer().getTextureNames().contains(requested) ? requested
                : handle.view().getPlayer().getTextureNames().stream().sorted().findFirst().orElse("");
        }
        if (!handle.view().getPlayer().getTextureNames().contains(texture)) {
            context.getSource().sendFailure(Component.literal("Unknown texture for " + path + ": " + texture));
            return 0;
        }

        var hash = handle.representation().modelId();
        var selectedTexture = texture;
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS);
        for (var player : targets) {
            player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(
                selection -> player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).ifPresent(auth -> {
                    if (!ignoreAuth && handle.location().rootKind().accessPolicy() == AccessPolicy.SESSION_AUTHORIZED
                        && !auth.containModel(hash)) {
                        context.getSource().sendFailure(
                            Component.literal(player.getScoreboardName() + " is not authorized for " + path));
                        return;
                    }
                    if (!SessionProtocolHandler.applyCommandSelection(player, selection,
                        new Selection.Model(hash, selectedTexture), ignoreAuth)) {
                        context.getSource().sendFailure(
                            Component.literal(player.getScoreboardName() + " rejected the model selection"));
                        return;
                    }
                }));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("Reloading model catalog..."), true);
        ServerModelService.instance().reload().thenAccept(result -> {
            CommandUtil.sendAsyncFeedback(context.getSource(),
                Component.literal(result.success() ? "Model catalog reloaded: " + result.modelCount() + " model(s), "
                    + result.errorCount() + " scan error(s)" : "Model catalog reload failed: " + result.message()),
                true);
            if (result.success() && result.warningCount() > 0) {
                for (var kind : ModelScanWarning.Kind.values()) {
                    var count = result.warnings().stream().filter(warning -> warning.kind() == kind)
                        .mapToInt(ModelScanWarning::occurrences).sum();
                    if (count > 0) {
                        var key = switch (kind) {
                            case UNKNOWN_AUDIO -> "commands.ysm.model.reload.unknown_audio";
                            case INVALID_AUDIO -> "commands.ysm.model.reload.invalid_audio";
                        };
                        CommandUtil.sendAsyncFeedback(context.getSource(), Component.translatable(key, count), true);
                    }
                }
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int errors(CommandContext<CommandSourceStack> context) {
        var errors = ServerModelService.instance().lastReport().errors();
        if (errors.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("The last model scan had no errors."), false);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendSuccess(() -> Component.literal("Model scan errors: " + errors.size()), false);
        for (var error : errors) {
            var text = "[" + error.rootKind() + "] " + error.source() + ": " + error.message();
            var output = text;
            context.getSource().sendSuccess(() -> Component.literal(output), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int disable(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var disabled = BoolArgumentType.getBool(context, "value");
        for (var player : EntityArgument.getPlayers(context, TARGETS)) {
            player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP)
                .ifPresent(capability -> capability.setDisabled(disabled));
        }
        return Command.SINGLE_SUCCESS;
    }
}
