package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.client.gui.PlayerModelScreen;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.resource.client.asset.PreviewResult;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Reads actual host facts without installing another production state owner. */
final class PreviewClientProbe {
    private final Minecraft minecraft;
    private final HostAction action;
    private ClientAssetBatch batch;
    private CompletableFuture<PreviewResult> preview;
    private boolean started;
    private int ticks;
    private long startedAt;
    private CompletableFuture<Integer> command;
    private CompletableFuture<Map<String, ?>> status;
    private static final java.util.Map<String, com.elfmcys.ysm.model.resource.client.ResourceLease> warm =
        new java.util.HashMap<>();

    PreviewClientProbe(Minecraft minecraft, HostAction action) {
        this.minecraft = minecraft;
        this.action = action;
    }

    Map<String, ?> poll() throws Exception {
        ticks++;
        return switch (action.name()) {
            case "preview-read" -> read();
            case "preview-state" -> {
                var service = ClientModelService.instance();
                var entry =
                    service.catalog().find(service.resolvePath(action.require("path")).orElseThrow()).orElseThrow();
                yield Map.of("absent",
                    ClientSessionRuntime.session().previewAbsent(entry.displayRepresentation().identity()), "admin",
                    minecraft.player.hasPermissions(2));
            }
            case "preview-runtime" -> runtime();
            case "preview-cost-start" -> {
                PreviewCostProbe.install((com.elfmcys.ysm.model.resource.client.preview.ClientPreviewGenerator)field(
                    ClientModelService.class, "previewOperations", ClientModelService.instance()));
                yield Map.of("installed", true, "systemNanos", System.nanoTime());
            }
            case "preview-cost" -> PreviewCostProbe.snapshot();
            case "preview-warm" -> {
                var service = ClientModelService.instance();
                var lease = warm.computeIfAbsent(action.require("path"), path -> service
                    .getOrStart(service.resourceRequest(service.resolvePath(path).orElseThrow(), "player", "")));
                var result = lease.poll();
                if (result instanceof com.elfmcys.ysm.model.resource.client.AcquireResult.Failed failed)
                    throw new IllegalStateException(failed.failure().toString());
                yield result instanceof com.elfmcys.ysm.model.resource.client.AcquireResult.Ready
                    ? Map.of("ready", true, "path", action.require("path")) : null;
            }
            case "preview-release-warm" -> {
                warm.values().forEach(com.elfmcys.ysm.model.resource.client.ResourceLease::close);
                warm.clear();
                yield Map.of("released", true);
            }
            case "client-command" -> {
                minecraft.player.connection.sendCommand(action.require("command"));
                yield Map.of("sent", true, "admin", minecraft.player.hasPermissions(2));
            }
            case "preview-screen" -> {
                if (!started) {
                    minecraft.setScreen(new PlayerModelScreen());
                    started = true;
                    yield null;
                }
                if (ticks == 40) {
                    var search = (net.minecraft.client.gui.components.EditBox)field(PlayerModelScreen.class,
                        "textField", minecraft.screen);
                    search.setFocused(true);
                    for (var character : action.require("path").toCharArray()) {
                        minecraft.screen.charTyped(character, 0);
                    }
                }
                if (ticks >= 55 && ticks < 60) {
                    var buttons = (java.util.List<?>)field(PlayerModelScreen.class, "modelButtons", minecraft.screen);
                    if (buttons.size() != 1)
                        throw new IllegalStateException("Expected exactly one fixture card");
                    var button = (com.elfmcys.ysm.client.gui.button.CatalogModelButton)buttons.get(0);
                    var window = minecraft.getWindow();
                    var inputX = minecraft.mouseHandler.getClass().getDeclaredField("xpos");
                    var inputY = minecraft.mouseHandler.getClass().getDeclaredField("ypos");
                    inputX.setAccessible(true);
                    inputY.setAccessible(true);
                    inputX.setDouble(minecraft.mouseHandler,
                        (button.getX() + 10) * window.getScreenWidth() / (double)window.getGuiScaledWidth());
                    inputY.setDouble(minecraft.mouseHandler,
                        (button.getY() + 10) * window.getScreenHeight() / (double)window.getGuiScaledHeight());
                }
                if (ticks < 60)
                    yield null;
                var buttons = (java.util.List<?>)field(PlayerModelScreen.class, "modelButtons", minecraft.screen);
                var button = (com.elfmcys.ysm.client.gui.button.CatalogModelButton)buttons.get(0);
                if (!button.isHovered())
                    throw new IllegalStateException("Fixture card was not hovered during host rendering");
                if (button.renderTarget() == null)
                    yield null;
                var renderedModel = button.renderTarget().modelHash().toString();
                try (var image = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                    image.writeToFile(Path.of(action.require("screenshot")));
                }
                minecraft.setScreen(null);
                yield Map.of("actualScreen", PlayerModelScreen.class.getName(), "screenshot",
                    action.require("screenshot"), "hoveredModel", button.modelHash().toString(), "admin",
                    minecraft.player.hasPermissions(2), "renderedModel", renderedModel, "input",
                    "test coordinates in MouseHandler; normal screen rendering and framebuffer capture");
            }
            case "integrated-start" -> integrated();
            case "integrated-command" -> {
                var server = minecraft.getSingleplayerServer();
                if (command == null)
                    command = server.submit(() -> server.getCommands()
                        .performPrefixedCommand(server.createCommandSourceStack(), action.require("command")));
                if (!command.isDone())
                    yield null;
                yield Map.of("result", command.join(), "integrated", !server.isDedicatedServer());
            }
            case "integrated-status" -> {
                var server = minecraft.getSingleplayerServer();
                if (status == null)
                    status = server.submit(() -> {
                        var environment = com.elfmcys.ysm.model.service.ModelExportService
                            .environment(server.createCommandSourceStack());
                        return Map.of("batchActive", field(environment.getClass(), "batch", environment) != null,
                            "requests",
                            ((java.util.Collection<?>)field(environment.getClass(), "requests", environment)).size(),
                            "workers", field(environment.getClass(), "workers", environment));
                    });
                if (!status.isDone())
                    yield null;
                yield status.join();
            }
            default -> throw new IllegalArgumentException(action.name());
        };
    }

    private Map<String, ?> integrated() throws Exception {
        if (!started) {
            var flows = minecraft.createWorldOpenFlows();
            var method = flows.getClass().getMethod("loadLevel", Screen.class, String.class);
            method.invoke(flows, null, action.require("world"));
            started = true;
        }
        if (minecraft.getSingleplayerServer() == null || minecraft.player == null)
            return null;
        return Map.of("integrated", !minecraft.getSingleplayerServer().isDedicatedServer(), "admin",
            minecraft.player.hasPermissions(2));
    }

    private Map<String, ?> read() throws Exception {
        var service = ClientModelService.instance();
        var id = service.resolvePath(action.require("path")).orElse(null);
        if (id == null)
            return null;
        if (batch == null) {
            batch = service.createAssetBatch();
            startedAt = System.nanoTime();
            preview = batch.preview(id);
            batch.submit();
        }
        if (!preview.isDone())
            return null;
        var facts = new LinkedHashMap<String, Object>();
        facts.put("path", action.require("path"));
        facts.put("elapsedMillis", (System.nanoTime() - startedAt) / 1_000_000.0);
        facts.put("containerId",
            service.catalog().find(id).orElseThrow().displayRepresentation().containerId().toString());
        try {
            var result = preview.join();
            facts.put("result", result instanceof PreviewResult.Data ? "DATA" : "ABSENT");
            if (result instanceof PreviewResult.Data data) {
                try (var image = data.image().open(); var pixels = image.decodeToBuffer()) {
                    facts.put("width", image.width());
                    facts.put("height", image.height());
                    var bytes = new byte[image.data().size()];
                    image.data().nio().get(bytes);
                    facts.put("encodedSha256",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    var destination = action.arguments().get("output");
                    if (destination != null)
                        Files.write(Path.of(destination), bytes);
                }
            }
        } catch (java.util.concurrent.CompletionException failure) {
            facts.put("result", "FAILED");
            facts.put("failure", failure.getCause().toString());
        } finally {
            batch.close();
        }
        return facts;
    }

    private Map<String, ?> runtime() throws Exception {
        var current = field(ClientSessionRuntime.class, "current", null);
        var repair = current == null ? null : field(current.getClass(), "previewRepair", current);
        var facts = new LinkedHashMap<String, Object>();
        facts.put("batchActive", repair != null);
        var generator = field(ClientModelService.class, "previewOperations", ClientModelService.instance());
        facts.put("generatorActive", ((Map<?, ?>)field(generator.getClass(), "active", generator)).size());
        facts.put("generatorPending",
            ((java.util.Collection<?>)field(generator.getClass(), "submissions", generator)).size());
        var transfer = current == null ? null : field(current.getClass(), "assetTransfer", current);
        facts.put("transfersActive",
            transfer == null ? 0 : ((Map<?, ?>)field(transfer.getClass(), "active", transfer)).size());
        if (repair != null) {
            var selection = field(repair.getClass(), "selection", repair);
            var results = (java.util.List<?>)field(repair.getClass(), "results", repair);
            facts.put("selectionSize", selection == null ? -1 : ((java.util.List<?>)selection).size());
            facts.put("processed", results.size());
            facts.put("itemActive", field(repair.getClass(), "current", repair) != null);
        }
        facts.put("admin", minecraft.player != null && minecraft.player.hasPermissions(2));
        return facts;
    }

    private static Object field(Class<?> type, String name, Object owner) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The test observer no longer matches its candidate", failure);
        }
    }
}
