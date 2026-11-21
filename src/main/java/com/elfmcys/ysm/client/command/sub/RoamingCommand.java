package com.elfmcys.ysm.client.command.sub;

import com.elfmcys.ysm.command.ModelSelectorArgument;
import com.elfmcys.ysm.model.catalog.source.ModelSelector;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class RoamingCommand {
    private RoamingCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("roaming").then(Commands.literal("clear")
            .then(Commands.argument("model", ModelSelectorArgument.selector()).suggests((context, builder) -> {
                for (var value : ModelSelector
                    .suggestions(ClientModelService.instance().catalog().catalog().byLocation()))
                    builder.suggest(value.indexOf(' ') >= 0
                        ? '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : value);
                return builder.buildFuture();
            }).executes(context -> {
                var models = ClientModelService.instance();
                var result = ModelSelector.resolveHistorical(context.getArgument("model", String.class),
                    models.catalog().catalog().byLocation());
                if (result.found().isEmpty()) {
                    context.getSource().sendFailure(Component.literal(result.message()));
                    return 0;
                }
                models.preferences().clear(result.found().orElseThrow());
                return 1;
            })));
    }
}
