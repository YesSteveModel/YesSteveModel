package com.elfmcys.ysm.client.command;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.command.sub.DebugCommand;
import com.elfmcys.ysm.client.command.sub.MolangCommand;
import com.elfmcys.ysm.client.command.sub.SimpleWatchCommand;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.elfmcys.ysm.expression.host.RoamingMember;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.util.CommandUtil;
import com.google.common.collect.Sets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings("removal")
public class ClientRootCommand {
    private static final String ROOT_NAME = "ysmclient";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root =
            Commands.literal(ROOT_NAME).requires(source -> CommandUtil.isLocalPlayer(source.getEntity()));
        root.then(MolangCommand.get());
        root.then(SimpleWatchCommand.get());
        root.then(DebugCommand.get());
        dispatcher.register(root);
    }

    public static final SuggestionProvider<CommandSourceStack> ALL_VARS =
        SuggestionProviders.register(new ResourceLocation(YesSteveModel.MOD_ID, "vars"), (source, builder) -> {
            if (source.getSource() instanceof SharedSuggestionProvider && FMLEnvironment.dist == Dist.CLIENT) {
                return getTarget().map(cap -> {
                    Set<String> vars = Sets.newHashSet();
                    // v.roaming 变量
                    if (cap.getExpressionRuntime().roamingState().isPresent()) {
                        for (var member : RoamingMember.values()) {
                            vars.add(String.format("v.roaming.%s", member.memberName()));
                        }
                    }

                    // 其他上下文变量
                    for (var symbol : cap.getExpressionRuntime().module().map(com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule::publishedSymbols).orElse(java.util.List.of())) {
                        var sourceName = sourceName(symbol);
                        if (sourceName != null) {
                            vars.add(sourceName);
                        }
                    }

                    // 自定义函数
                    for (var name : cap.getModelRenderTarget().assets().userFunctions().keySet()) {
                        vars.add(String.format("fn.%s", name));
                    }

                    return SharedSuggestionProvider.suggest(vars, builder);
                }).orElseGet(Suggestions::empty);
            }
            return Suggestions.empty();
        });

    public static final SuggestionProvider<CommandSourceStack> ALL_CONTROLLERS =
        SuggestionProviders.register(new ResourceLocation(YesSteveModel.MOD_ID, "controllers"), (source, builder) -> {
            if (source.getSource() instanceof SharedSuggestionProvider && FMLEnvironment.dist == Dist.CLIENT) {
                return getTarget().map(target -> {
                    Set<String> vars = target.getAnimationData().getAnimationControllers().stream()
                        .map(IAnimationController::getName).collect(Collectors.toSet());
                    return SharedSuggestionProvider.suggest(vars, builder);
                }).orElseGet(Suggestions::empty);
            }
            return Suggestions.empty();
        });

    private static Optional<AnimatableEntity<?>> getTarget() {
        var target = DebugAnimationScreen.getTarget();
        if (target == null) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                target = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
            }
        }
        return Optional.ofNullable(target);
    }

    static String sourceName(String stableId) {
        if (stableId.startsWith("query:")) {
            return "q." + stableId.substring("query:".length());
        }
        var delimiter = stableId.indexOf(':');
        if (delimiter < 0)
            return null;
        var root = stableId.substring(0, delimiter);
        var member = stableId.substring(delimiter + 1);
        return root.equals("global") ? member : root + '.' + member;
    }
}
