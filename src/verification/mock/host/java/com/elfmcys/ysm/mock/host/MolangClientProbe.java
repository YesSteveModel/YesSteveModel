package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.client.ClientCommandHandler;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Drives existing command and render owners in the isolated Forge development mod. */
final class MolangClientProbe {
    private final Minecraft minecraft;
    private final Path screenshot;
    private final Map<String, String> expected = Map.of("recovery_missing", "UNSET", "recovery_zero", "0.0",
        "recovery_false", "false", "recovery_forbidden", "0.0");
    private final Map<String, CompletableFuture<Suggestions>> suggestions = new LinkedHashMap<>();
    private boolean started;
    private int settled;
    private MolangSoundProbe sounds;

    MolangClientProbe(Minecraft minecraft, String screenshot) {
        this.minecraft = minecraft;
        this.screenshot = Path.of(screenshot);
        if (!this.screenshot.isAbsolute())
            throw new IllegalArgumentException("Absolute screenshot path required");
    }

    Map<String, ?> poll() throws Exception {
        if (minecraft.player == null || minecraft.level == null)
            return null;
        var owner = minecraft.player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (owner == null)
            throw new IllegalStateException("Player capability unavailable");
        if (owner.getModelRenderTarget() == null)
            return null;
        if (!started) {
            minecraft.options.pauseOnLostFocus = false;
            minecraft.setScreen(null);
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            var connection = minecraft.player.connection;
            var dispatcher = connection.getCommands();
            for (var input : new String[] {"ysmclient molang execute q.", "ysmclient watch state "}) {
                suggestions.put(input, dispatcher.getCompletionSuggestions(
                    dispatcher.parse(input, connection.getSuggestionsProvider())));
            }
            for (var expression : new String[] {"q.recovery_absent()", "0", "false"}) {
                executeCommand("ysmclient molang execute " + expression);
            }
            for (var target : new String[] {"", " @s", " " + minecraft.player.getGameProfile().getName(),
                " " + minecraft.player.getId(), " " + minecraft.player.getStringUUID()}) {
                DebugAnimationScreen.disable();
                executeCommand("ysmclient debug" + target);
                if (DebugAnimationScreen.getTarget() != owner)
                    throw new IllegalStateException("Client debug command selected the wrong owner: " + target);
            }
            executeCommand("ysmclient molang watch add post recovery_missing q.recovery_absent()");
            executeCommand("ysmclient molang watch add post recovery_zero 0");
            executeCommand("ysmclient molang watch add post recovery_false false");
            executeCommand(
                "ysmclient molang watch add post recovery_forbidden ysm.play_sound('recovery_forbidden','minecraft:block.note_block.harp',6);0");
            started = true;
        }
        var actual = new LinkedHashMap<String, String>();
        minecraft.getToasts().clear();
        DebugAnimationScreen.getDebugInfo().enumerate(actual::put);
        if (suggestions.values().stream().anyMatch(result -> !result.isDone())) {
            if (++settled > 400)
                throw new IllegalStateException("Client command suggestions did not complete locally");
            return null;
        }
        var suggestionCounts = new LinkedHashMap<String, Integer>();
        suggestions.forEach((input, result) -> suggestionCounts.put(input, result.join().getList().size()));
        if (suggestions.get("ysmclient molang execute q.").join().getList().stream()
            .noneMatch(suggestion -> suggestion.getText().equals("q.health"))) {
            throw new IllegalStateException("Client command suggestions omitted q.health");
        }
        if (!actual.equals(expected)) {
            if (++settled > 400)
                throw new IllegalStateException("Watch values did not converge: " + actual);
            return null;
        }
        if (++settled < 20)
            return null;
        if (sounds == null)
            sounds = new MolangSoundProbe(owner);
        var audio = sounds.poll();
        if (audio == null)
            return null;
        try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(screenshot);
        }
        var facts = new LinkedHashMap<String, Object>();
        facts.put("watch", actual);
        facts.put("suggestions", suggestionCounts);
        facts.put("debugTargets", 5);
        facts.put("operator", minecraft.player.hasPermissions(2));
        facts.put("audio", audio);
        facts.put("screenshot", screenshot.toString());
        facts.put("ownerActive", owner.isActive());
        facts.put("generation", owner.getExpressionRuntime().generation());
        facts.put("health", minecraft.player.getHealth());
        facts.put("selectedSlot", minecraft.player.getInventory().selected);
        facts.put("world", minecraft.level.dimension().location().toString());
        facts.put("boundary",
            "Forge client-command registration and dispatch, watch rendering and framebuffer; generated transient sound submission and Minecraft SoundManager activity. No human listening, default fault, or exhaustive entity oracle.");
        return facts;
    }

    private static void executeCommand(String command) {
        if (!ClientCommandHandler.runCommand(command)) {
            throw new IllegalStateException("Client command was not consumed locally: " + command);
        }
    }
}
