package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.event.CommandRegistry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.command.ModelSelectorArgument;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.util.CommandUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

public final class AuthCommand {
    private static final String TARGETS = "targets";
    private static final String MODEL_PATH = "model_path";

    private AuthCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        var auth = Commands.literal("auth").requires(source -> CommandUtil.hasPermission(source, 2));
        var targets = Commands.argument(TARGETS, EntityArgument.players());
        var path = Commands.argument(MODEL_PATH, ModelSelectorArgument.selector()).suggests(CommandRegistry.ALL_MODELS);
        auth.then(targets.then(Commands.literal("add").then(path.executes(context -> change(context, true)))));
        auth.then(Commands.argument(TARGETS, EntityArgument.players())
            .then(Commands.literal("remove").then(Commands.argument(MODEL_PATH, ModelSelectorArgument.selector())
                .suggests(CommandRegistry.ALL_MODELS).executes(context -> change(context, false)))));
        auth.then(Commands.argument(TARGETS, EntityArgument.players())
            .then(Commands.literal("all").executes(AuthCommand::all)));
        auth.then(Commands.argument(TARGETS, EntityArgument.players())
            .then(Commands.literal("clear").executes(AuthCommand::clear)));
        return auth;
    }

    private static int change(CommandContext<CommandSourceStack> context, boolean add) throws CommandSyntaxException {
        var path = StringArgumentType.getString(context, MODEL_PATH);
        var snapshot = ServerModelService.instance().catalog().orElseThrow();
        var resolution = snapshot.resolvePath(path);
        var model = resolution.found().filter(id -> !id.isIntrinsicDefault()).flatMap(snapshot::find);
        if (model.isEmpty()) {
            context.getSource().sendFailure(
                Component.literal(resolution.status() == com.elfmcys.ysm.model.catalog.source.ModelSelector.Status.FOUND
                    ? "Intrinsic default cannot be granted" : resolution.message()));
            return 0;
        }
        var hash = model.get().representation().modelId();
        for (var player : EntityArgument.getPlayers(context, TARGETS)) {
            player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).ifPresent(capability -> {
                if (add) {
                    capability.addModel(hash);
                } else {
                    capability.removeModel(hash);
                    player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(selection -> {
                        if (selection
                            .getModelId() instanceof com.elfmcys.ysm.model.domain.PublishedModelId.Model modelId
                            && hash.equals(modelId.hash())
                            && snapshot.catalog().byModelId().get(hash).entry().access() == CatalogAccess.AUTHORIZED) {
                            ModelSelectionService.selectDefault(selection, snapshot);
                        }
                    });
                }
                SessionProtocolHandler.refreshGrants(player);
            });
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int all(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var hashes = ServerModelService.instance().catalog().orElseThrow().models().keySet();
        for (var player : EntityArgument.getPlayers(context, TARGETS)) {
            player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).ifPresent(capability -> {
                hashes.forEach(capability::addModel);
                SessionProtocolHandler.refreshGrants(player);
            });
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var snapshot = ServerModelService.instance().catalog().orElseThrow();
        for (var player : EntityArgument.getPlayers(context, TARGETS)) {
            player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).ifPresent(capability -> {
                capability.clear();
                player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(selection -> {
                    var selected = selection.getModelId();
                    if (selected instanceof com.elfmcys.ysm.model.domain.PublishedModelId.Model modelId
                        && snapshot.catalog().byModelId().containsKey(modelId.hash()) && snapshot.catalog().byModelId()
                            .get(modelId.hash()).entry().access() == CatalogAccess.AUTHORIZED) {
                        ModelSelectionService.selectDefault(selection, snapshot);
                    }
                });
                SessionProtocolHandler.refreshGrants(player);
            });
        }
        return Command.SINGLE_SUCCESS;
    }
}
