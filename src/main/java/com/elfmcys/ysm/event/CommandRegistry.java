package com.elfmcys.ysm.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.command.ClientRootCommand;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.command.RootCommand;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.google.common.collect.Sets;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber
@SuppressWarnings("removal")
public final class CommandRegistry {
    public static final SuggestionProvider<CommandSourceStack> ALL_MODELS =
        SuggestionProviders.register(new ResourceLocation(YesSteveModel.MOD_ID, "models"), (source, builder) -> {
            if (source.getSource() instanceof SharedSuggestionProvider) {
                var paths = ServerModelService.current().flatMap(ServerModelService::catalog)
                    .map(snapshot -> snapshot.models().values().stream().map(handle -> handle.location().value())
                        .distinct().map(CommandRegistry::filterSuggestionStr).toList())
                    .orElse(List.of());
                return SharedSuggestionProvider.suggest(paths, builder);
            } else {
                return Suggestions.empty();
            }
        });

    public static final SuggestionProvider<CommandSourceStack> ALL_ANIMATIONS =
        SuggestionProviders.register(new ResourceLocation(YesSteveModel.MOD_ID, "animations"), (source, builder) -> {
            if (source.getSource() instanceof SharedSuggestionProvider) {
                if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
                    // Fixme: 应该为服务器后台也添加提示功能
                    return Suggestions.empty();
                } else {
                    var main = ClientModelService.instance().defaultRenderTarget().playerResources().animations();
                    Set<String> animations = Sets.newHashSet();
                    animations.addAll(main.keySet().stream().map(CommandRegistry::filterSuggestionStr).toList());
                    animations.add("stop");
                    return SharedSuggestionProvider.suggest(animations, builder);
                }
            } else {
                return Suggestions.empty();
            }
        });

    public static final SuggestionProvider<CommandSourceStack> ALL_TEXTURES =
        SuggestionProviders.register(new ResourceLocation(YesSteveModel.MOD_ID, "textures"), (source, builder) -> {
            if (source.getSource() instanceof SharedSuggestionProvider) {
                String modelPath = source.getArgument("model_path", String.class);
                var model = ServerModelService.current().flatMap(ServerModelService::catalog)
                    .flatMap(snapshot -> snapshot.findPath(modelPath));
                if (model.isPresent()) {
                    List<String> textures = model.get().representation().view().getPlayer().getTextureNames().stream()
                        .sorted().map(CommandRegistry::filterSuggestionStr).collect(Collectors.toList());
                    textures.add(0, "-");
                    return SharedSuggestionProvider.suggest(textures, builder);
                }
            }
            return Suggestions.empty();
        });

    @SubscribeEvent
    public static void onServerStaring(RegisterCommandsEvent event) {
        if (!YesSteveModel.isAvailable()) {
            RootCommand.registerPlaceholder(event.getDispatcher());
            return;
        }

        RootCommand.register(event.getDispatcher());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientRootCommand.register(event.getDispatcher());
        }
    }

    private static String filterSuggestionStr(String str) {
        if (str.chars().allMatch(c -> StringReader.isAllowedInUnquotedString((char)c))) {
            return str;
        }
        return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
