package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.domain.PublicFailure;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Command syntax and committed per-root feedback shared by the two host dispatchers. */
public final class CacheCommand {
    private CacheCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return tree("ysm", (source, all) -> {
            try {
                return schedule(ModelRuntime.system().storage(), all, source.getServer()::execute,
                    message -> source.sendSuccess(() -> message, false), source::sendFailure);
            } catch (RuntimeException failure) {
                source.sendFailure(
                    Component.translatable("commands.ysm.cache.failure", "cache", PublicFailure.summary(failure)));
                return 0;
            }
        }).requires(source -> source.hasPermission(2));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree(String root,
        BiFunction<CommandSourceStack, Boolean, Integer> action) {
        return Commands.literal("cache").executes(context -> {
            context.getSource().sendSystemMessage(Component.translatable("commands.ysm.cache.help", root));
            return Command.SINGLE_SUCCESS;
        }).then(Commands.literal("prune-next").executes(context -> action.apply(context.getSource(), false))
            .then(Commands.literal("all").executes(context -> action.apply(context.getSource(), true))));
    }

    /** Reports only committed resets, on the original host executor. No completion means no success feedback. */
    public static int schedule(ModelStorageInfra storage, boolean all, Executor host, Consumer<Component> success,
        Consumer<Component> failure) {
        storage.pruneNext(all).whenComplete((results, error) -> host.execute(() -> {
            if (error != null) {
                com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Cache prune-next command failed", error);
                failure.accept(
                    Component.translatable("commands.ysm.cache.failure", "cache", PublicFailure.summary(error)));
                return;
            }
            for (var result : results) {
                var region = Component.translatable(
                    result.local() ? result.global() ? "commands.ysm.cache.local_global" : "commands.ysm.cache.local"
                        : "commands.ysm.cache.global");
                if (result.succeeded()) {
                    success.accept(Component.translatable(
                        all && result.global() ? "commands.ysm.cache.scheduled_all" : "commands.ysm.cache.scheduled",
                        region));
                } else {
                    var reason = result.failure() == null ? String.valueOf(result.storage())
                        : PublicFailure.summary(result.failure());
                    com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Cache prune-next reset failed: {} {}", result.root(),
                        reason, result.failure());
                    failure.accept(Component.translatable("commands.ysm.cache.failure", region, reason));
                }
            }
            if (!storage.paths().globalEnabled())
                success.accept(Component.translatable("commands.ysm.cache.global_disabled"));
        }));
        return Command.SINGLE_SUCCESS;
    }
}
