package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.client.gui.PlayerModelScreen;
import com.elfmcys.ysm.client.gui.overlay.LoadingStateScreen;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.CatalogScanProgress;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/** Holds real scan checkpoints; actual production render methods draw and record their text. */
final class CatalogProgressPageProbe {
    private final Minecraft minecraft;
    private final HostAction action;
    private final ReloadableModelCatalog catalog = ModelRuntime.system().catalog();
    private final LinkedBlockingQueue<CatalogScanProgress> checkpoints = new LinkedBlockingQueue<>();
    private final Semaphore release = new Semaphore(0);
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final CountDownLatch runtimeRelease = new CountDownLatch(1);
    private Consumer<CatalogScanProgress> originalObserver;
    private PlayerModelScreen screen;
    private ResourceLease full;
    private CountDownLatch workersEntered;
    private boolean observing;
    private boolean finalizing;
    private boolean observerRestored;
    private int stage;
    private int ticks;
    private int expected;
    private int nextProcessed;
    private Capture requested;
    private String captured;
    private Throwable renderFailure;
    private RemoteProgressGate remote;
    private java.util.concurrent.CompletableFuture<?> serverReload;

    private record Capture(String name, String phase, int total, int processed) {
    }

    CatalogProgressPageProbe(Minecraft minecraft, HostAction action) {
        this.minecraft = minecraft;
        this.action = action;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    Map<String, ?> poll() throws Exception {
        if (++ticks > 1800) {
            cleanup();
            throw new IllegalStateException("Catalog progress probe timed out at " + stage);
        }
        try {
            if (renderFailure != null)
                throw new IllegalStateException("Production rendering probe failed", renderFailure);
            return advance();
        } catch (Throwable failure) {
            var trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            var output = Path.of(action.require("output"));
            java.nio.file.Files.createDirectories(output);
            java.nio.file.Files.writeString(output.resolve("failure.txt"), "stage=" + stage + "\n" + trace);
            cleanup();
            throw failure;
        }
    }

    private Map<String, ?> advance() throws Exception {
        if (Boolean.parseBoolean(action.arguments().getOrDefault("remote", "false")))
            return advanceRemote();
        var service = ClientModelService.instance();
        if (stage == 0) {
            if (catalog.busy() || runtimePending() != 0)
                return null;
            // This manifest enumerates input paths independently of the progress projection.
            try (var reader = java.nio.file.Files.newBufferedReader(ModelRuntime.paths().builtinIndex())) {
                var manifest = com.google.gson.JsonParser.parseReader(reader);
                var builtinPacks = List.of("misc/ysm-pack.json", "wine_fox/ysm-pack.json");
                for (var path : builtinPacks)
                    require(
                        java.nio.file.Files.isRegularFile(ModelRuntime.paths().builtinModelsResource().resolve(path)),
                        "Missing builtin pack input: " + path);
                expected = manifest.getAsJsonObject().getAsJsonArray("models").size() - 1 + builtinPacks.size() + 4;
                var inputs = new LinkedHashMap<String, Object>();
                inputs.put("builtinModels", manifest.getAsJsonObject().getAsJsonArray("models"));
                inputs.put("excludedBuiltin", "default");
                inputs.put("builtinPacks", builtinPacks);
                inputs.put("localInputs",
                    List.of("progress/first", "progress/duplicate", "progress/bad.mxc", "progress/ysm-pack.json"));
                var output = Path.of(action.require("output"));
                java.nio.file.Files.createDirectories(output);
                java.nio.file.Files.writeString(output.resolve("inputs.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(inputs));
            }
            originalObserver = (Consumer<CatalogScanProgress>)field(catalog, "progressObserver");
            setObserver(value -> {
                checkpoints.add(value);
                try {
                    release.acquire();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            screen = new PlayerModelScreen();
            minecraft.setScreen(screen);
            catalog.reload();
            stage = 1;
            return null;
        }
        if (stage == 1) {
            var value = checkpoints.poll();
            if (value == null)
                return null;
            require(value.phase() == CatalogScanProgress.Phase.DISCOVERING, "Discovery checkpoint missing");
            if (runtimePending() != 0) {
                checkpoints.add(value);
                return null;
            }
            if (!capture("discovering", "DISCOVERING", -1, 0)) {
                checkpoints.add(value);
                return null;
            }
            observing = true;
            release.release();
            stage = 2;
            return null;
        }
        if (stage == 2) {
            var value = checkpoints.poll();
            if (value == null)
                return null;
            require(value.total() == expected && value.processed() == nextProcessed,
                "Unexpected input outcome count: " + value + " expected=" + nextProcessed + "/" + expected);
            var phase = nextProcessed == expected ? "FINALIZING" : "PROCESSING";
            if (!capture("outcome-" + nextProcessed, phase, expected, nextProcessed)) {
                checkpoints.add(value);
                return null;
            }
            nextProcessed++;
            if (value.phase() == CatalogScanProgress.Phase.FINALIZING) {
                finalizing = true;
                setObserver(originalObserver);
                observerRestored = true;
                release.release();
                stage = 3;
            } else {
                release.release();
            }
            return null;
        }
        if (stage == 3) {
            if (catalog.busy() || runtimeModel() == null || runtimePending() != 0)
                return null;
            if (!capture("idle", "IDLE", -1, 0))
                return null;
            var scheduler = (ClientResourceScheduler)field(service, "scheduler");
            var workers = (Thread[])field(scheduler, "workers");
            workersEntered = new CountDownLatch(workers.length - 1);
            for (var index = 1; index < workers.length; index++) {
                scheduler.backgroundExecutor().execute(() -> {
                    workersEntered.countDown();
                    try {
                        runtimeRelease.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            stage = 4;
            return null;
        }
        if (stage == 4) {
            if (workersEntered.getCount() != 0)
                return null;
            var id = runtimeModel();
            var texture = service.catalog().find(id).orElseThrow().displayRepresentation().view().getPlayer()
                .getTextureNames().stream().findFirst().orElseThrow();
            full = service.getOrStart(service.resourceRequest(id, "player", texture, ModelResourcePurpose.FULL, null));
            stage = 5;
            return null;
        }
        if (stage == 5) {
            var pending = runtimePending();
            require(pending > 0, "FULL work did not remain pending behind the real scheduler gate");
            if (!capture("full-pending-catalog-idle", "IDLE", -1, 0))
                return null;
            samples.get(samples.size() - 1).put("runtimePending", pending);
            cleanup();
            stage = 6;
            return null;
        }
        if (stage == 6) {
            minecraft.setScreen(null);
            require(observing && finalizing, "Missing scan phases");
            return Map.of("samples", samples, "expectedInputs", expected, "singleplayer",
                minecraft.hasSingleplayerServer(), "status", "passed", "boundary",
                "Actual HUD/page render calls and framebuffer; explicit scan and runtime gates. No remote transfer gate.");
        }
        return null;
    }

    private Map<String, ?> advanceRemote() throws Exception {
        if (stage == 0) {
            if (catalog.busy() || runtimePending() != 0)
                return null;
            var activation = ClientSessionRuntime.session().activationSnapshot().orElse(null);
            if (activation == null || !activation.terminal())
                return null;
            java.nio.file.Files.createDirectories(Path.of(action.require("output")));
            screen = new PlayerModelScreen();
            minecraft.setScreen(screen);
            remote = new RemoteProgressGate(minecraft);
            java.nio.file.Files.writeString(Path.of(action.require("armedFile")), "armed");
            stage = 1;
        }
        if (stage == 1) {
            if (minecraft.hasSingleplayerServer() && serverReload == null
                && java.nio.file.Files.exists(Path.of(action.require("reloadFile")))) {
                serverReload = minecraft.getSingleplayerServer()
                    .submit(() -> com.elfmcys.ysm.model.service.ServerModelService.instance().reload())
                    .thenCompose(value -> value);
            }
            if (!remote.activationHeld())
                return null;
            if (catalog.busy() || runtimePending() != 0)
                return null;
            if (!capture("remote-activation-catalog-idle", "IDLE", -1, 0))
                return null;
            remote.releaseWork();
            stage = 2;
        }
        if (stage == 2) {
            if (!remote.transferHeld())
                return null;
            if (catalog.busy() || runtimePending() != 0)
                return null;
            if (!capture("remote-transfer-catalog-idle", "IDLE", -1, 0))
                return null;
            remote.releasePackets();
            stage = 3;
        }
        if (stage == 3) {
            if (!ClientSessionRuntime.session().activationSnapshot().orElseThrow().terminal())
                return null;
            if (!capture("remote-settled-catalog-idle", "IDLE", -1, 0))
                return null;
            cleanup();
            minecraft.setScreen(null);
            stage = 4;
            return Map.of("samples", samples, "singleplayer", minecraft.hasSingleplayerServer(), "status", "passed",
                "boundary",
                "Actual queued remote activation, received metadata wire packets and active transfer IDs; real HUD/page draws remain hidden while Local Catalog is idle.");
        }
        return null;
    }

    private boolean capture(String name, String phase, int total, int processed) {
        if (name.equals(captured))
            return true;
        if (requested == null)
            requested = new Capture(name, phase, total, processed);
        return false;
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onRender(net.minecraftforge.event.TickEvent.RenderTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || requested == null)
            return;
        var value = requested;
        requested = null;
        var modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        modelView.pushPose();
        com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix();
        try {
            minecraft.getMainRenderTarget().bindWrite(true);
            modelView.setIdentity();
            modelView.translate(0, 0, -11000);
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
            com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
                new org.joml.Matrix4f().setOrtho(0, minecraft.getWindow().getGuiScaledWidth(),
                    minecraft.getWindow().getGuiScaledHeight(), 0, 1000, 21000),
                com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
            captureNow(value.name(), value.phase(), value.total(), value.processed());
            captured = value.name();
        } catch (Throwable failure) {
            renderFailure = failure;
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix();
            modelView.popPose();
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
            minecraft.getMainRenderTarget().unbindWrite();
        }
    }

    private void captureNow(String name, String phase, int total, int processed) throws Exception {
        var value = ClientModelService.instance().catalogProgress();
        require(phase.equals("IDLE") ? value == null : value != null && value.phase().name().equals(phase)
            && value.total() == total && value.processed() == processed, "Unexpected source snapshot");
        var graphics = new RecordingGraphics(minecraft);
        screen.render(graphics, 1, 1, 0);
        var page = List.copyOf(graphics.texts);
        graphics.texts.clear();
        new LoadingStateScreen().render((ForgeGui)minecraft.gui, graphics, 0, minecraft.getWindow().getGuiScaledWidth(),
            minecraft.getWindow().getGuiScaledHeight());
        var hud = List.copyOf(graphics.texts);
        graphics.flush();
        require(phase.equals("IDLE") ? page.isEmpty() && hud.isEmpty() : page.size() == 1 && hud.size() == 1,
            "Unexpected production text counts: page=" + page + " HUD=" + hud);
        if (!page.isEmpty()) {
            require(hud.get(0).get("text").toString().endsWith(page.get(0).get("text").toString()),
                "UI consumers disagree");
            require(hud.get(0).get("key").equals("gui.yes_steve_model.sync_hint.title"), "Missing HUD title");
            require(
                page.get(0).get("key")
                    .equals("gui.yes_steve_model.catalog_progress." + phase.toLowerCase(java.util.Locale.ROOT)),
                "Wrong stage text key");
            if (phase.equals("PROCESSING"))
                require(page.get(0).get("text").toString().contains(processed + "/" + total), "Wrong displayed n/N");
            require((int)page.get(0).get("color") == net.minecraft.ChatFormatting.DARK_GRAY.getColor(),
                "Page progress is not dark gray");
            var pageX = (int)field(screen, "x");
            require((int)page.get(0).get("x") + (int)page.get(0).get("width") == pageX + 414,
                "Page progress is not right aligned");
            var next =
                screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                    .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                    .filter(widget -> widget.getMessage().getContents() instanceof TranslatableContents translated
                        && translated.getKey().equals("gui.yes_steve_model.next_page"))
                    .findFirst().orElseThrow();
            require((int)page.get(0).get("x") >= next.getX() + next.getWidth(),
                "Page progress overlaps the pagination controls: " + page.get(0));
        }
        var sample = new LinkedHashMap<String, Object>();
        sample.put("event", name);
        sample.put("phase", phase);
        sample.put("processed", processed);
        sample.put("total", total);
        sample.put("page", page);
        sample.put("hud", hud);
        sample.put("runtimePending", runtimePending());
        sample.put("session", ClientSessionRuntime.state().map(Enum::name).orElse("LOCAL"));
        sample.put("singleplayer", minecraft.hasSingleplayerServer());
        if (remote != null)
            sample.put("remote", remote.snapshot());
        samples.add(sample);
        var output = Path.of(action.require("output"));
        java.nio.file.Files.createDirectories(output);
        try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(output.resolve(name + ".png"));
        }
        java.nio.file.Files.writeString(output.resolve("draws.json"),
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(samples));
    }

    private com.elfmcys.ysm.model.domain.Hash256 runtimeModel() {
        var service = ClientModelService.instance();
        return minecraft.hasSingleplayerServer()
            ? service.resolvePath("local:custom/progress/first")
                .or(() -> service.resolvePath("local:custom/progress/duplicate")).orElse(null)
            : service.resolvePath("local:custom/molang/fault").orElse(null);
    }

    private int runtimePending() throws Exception {
        var manager = field(ClientModelService.instance(), "renderTargetManager");
        return (int)manager.getClass().getMethod("loadingCount").invoke(manager);
    }

    private void cleanup() throws Exception {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this);
        if (!observerRestored && originalObserver != null) {
            setObserver(originalObserver);
            observerRestored = true;
        }
        release.release(100_000);
        runtimeRelease.countDown();
        if (remote != null) {
            remote.close();
            remote = null;
        }
        if (full != null) {
            full.close();
            full = null;
        }
    }

    private void setObserver(Consumer<CatalogScanProgress> value) throws Exception {
        var field = ReloadableModelCatalog.class.getDeclaredField("progressObserver");
        field.setAccessible(true);
        field.set(catalog, value);
    }

    private static Object field(Object owner, String name) throws Exception {
        var type = owner.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }

    private static final class RecordingGraphics extends GuiGraphics {
        private final List<Map<String, Object>> texts = new ArrayList<>();

        private RecordingGraphics(Minecraft minecraft) {
            super(minecraft, minecraft.renderBuffers().bufferSource());
        }

        @Override
        public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
            if (text.getContents() instanceof TranslatableContents translated
                && (translated.getKey().startsWith("gui.yes_steve_model.catalog_progress.")
                    || translated.getKey().equals("gui.yes_steve_model.sync_hint.title")))
                texts.add(Map.of("text", text.getString(), "key", translated.getKey(), "x", x, "y", y, "color", color,
                    "width", font.width(text)));
            return super.drawString(font, text, x, y, color, shadow);
        }
    }
}
