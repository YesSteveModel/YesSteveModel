package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.source.*;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.resource.client.work.*;
import com.elfmcys.ysm.model.storage.*;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingFile;

/** One process per cache combination; actual loader inputs and JDK reads are observed externally. */
public final class GuiPurposeCacheProbe {
    private static volatile boolean observing;
    private static final Queue<Map<String, Object>> INPUTS = new ConcurrentLinkedQueue<>();

    public static void input(Object definition, Object functions, Object config, Object purpose) {
        if (!observing)
            return;
        try {
            var model = (ModelDataProto)definition;
            var settings = (SettingsProto)config;
            var names = model.animationFiles().values().stream().flatMap(file -> file.animations().stream())
                .map(animation -> animation.name()).sorted().toList();
            INPUTS.add(Map.of("purpose", purpose.toString(), "animations", names, "fingerprint",
                com.elfmcys.ysm.expression.inventory.ModelProgramInventory
                    .sourceFingerprint(model, (StringDataProto)functions, settings).value().toString()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static void main(String[] args) throws Exception {
        var root = Files.createDirectories(Path.of(args[0]));
        var output = Files.createDirectories(Path.of(args[1]));
        var modelPath = args[2];
        var operation = args[3];
        NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
        RenderSystem.initRenderThread();
        var events = com.elfmcys.ysm.YesSteveModel.class.getDeclaredField("EVENT_BUS");
        events.setAccessible(true);
        events.set(null, net.minecraftforge.eventbus.api.BusBuilder.builder().build());
        com.elfmcys.ysm.client.model.locator.PlayerLocator.init();
        var builtinRoot = Path
            .of(Objects.requireNonNull(GuiPurposeCacheProbe.class.getResource("/assets/ysm/builtin/default")).toURI());
        var raw = Path.of(
            Objects.requireNonNull(GuiPurposeCacheProbe.class.getResource("/assets/ysm/builtin/" + modelPath)).toURI());
        var inputRoot = Files.createDirectories(root.resolve("input"));
        Path input;
        try (var files = Files.list(inputRoot)) {
            input = files.filter(file -> file.toString().endsWith(".ysm")).findFirst().orElse(null);
        }
        if (input == null) {
            try (var index = GuiPurposeCacheProbe.class.getResourceAsStream("/assets/ysm/builtin-index.json");
                var reader = new java.io.InputStreamReader(Objects.requireNonNull(index),
                    java.nio.charset.StandardCharsets.UTF_8);
                var directory = new Directory(raw)) {
                input = ModelParser.parse(directory, inputRoot, BuiltinModelIndex.read(reader));
            }
        }
        var source =
            ManagedContainer.openDirect(input, new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath(modelPath)));
        var scheduler = new ClientResourceScheduler(2);
        var work = new DerivedStorageWork(DerivedStorageWork.DEFAULT_MAXIMUM_JOBS);
        var defaults = new DefaultAnimationRuntime();
        var host = ExpressionHost.create(HostAvailability.none());
        var bootRoot = Files.createDirectories(output.resolve("boot"));
        var bootCache = new AtomicSharedCache(bootRoot, work);
        var bootLoader = new ModelRenderTargetLoader(new BakedModelCache(bootRoot.resolve("bake"), bootCache),
            new RenderTargetExpressionModuleFactory(new ExpressionArtifactStore(bootRoot, bootCache), host,
                new ExpressionEngineMetrics()),
            scheduler, defaults);
        var cacheRoot = Files.createDirectories(root.resolve("cache"));
        var cache = new AtomicSharedCache(cacheRoot, work);
        var metrics = new ExpressionEngineMetrics();
        var store = new ExpressionArtifactStore(cacheRoot, cache);
        var loader = new ModelRenderTargetLoader(new BakedModelCache(cacheRoot.resolve("bake"), cache),
            new RenderTargetExpressionModuleFactory(store, host, metrics), scheduler, defaults);
        ModelRenderTargetLoader.ModelCandidate boot = null;
        ModelRenderTargetLoader.ModelCandidate fullReady = null;
        try (var directory = new Directory(builtinRoot)) {
            var parsed = ModelParser.parseBuiltinDefaultMemory(directory);
            var bytes = parsed.bytes();
            var builtin = ManagedContainer.openResidentDefault(bytes,
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            try (var representation = builtin.representation()) {
                boot = load(bootLoader, scheduler, builtin, ModelResourcePurpose.FULL, true, false);
                defaults.publishSources(target(boot).defaultSources());
                work.awaitIdle();
                if (operation.equals("full-ready")) {
                    fullReady = load(loader, scheduler, source, ModelResourcePurpose.FULL, false, false);
                    work.awaitIdle();
                }
                var purpose = operation.startsWith("seed-full") || operation.equals("full-read")
                    ? ModelResourcePurpose.FULL : ModelResourcePurpose.GUI;
                var jfr = output.resolve("reads.jfr");
                Map<String, Object> result = new LinkedHashMap<>();
                try (var recording = new Recording()) {
                    recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
                    recording.start();
                    var before = metrics.snapshot()
                        .stageCalls(dev.semaweave.compilation.EngineMetrics.Stage.BACKEND_COMPILATION);
                    com.elfmcys.ysm.benchmark.ReadHandleProbe.clear();
                    observing = true;
                    var start = System.nanoTime();
                    ModelRenderTargetLoader.ModelCandidate candidate = null;
                    try {
                        candidate = load(loader, scheduler, source, purpose, false, operation.equals("cache-only"));
                        var phaseHandles = com.elfmcys.ysm.benchmark.ReadHandleProbe.snapshot();
                        if (phaseHandles.stream().anyMatch(handle -> !(boolean)handle.get("closed")))
                            throw new IllegalStateException("Loader retained an idle read channel");
                        var animationHandles = phaseHandles.stream()
                            .filter(handle -> ((List<?>)handle.get("stack")).stream()
                                .anyMatch(frame -> frame.toString().contains("AnimationControllerCollection.build")))
                            .toList();
                        var byFile = animationHandles.stream().collect(java.util.stream.Collectors
                            .groupingBy(handle -> handle.get("path"), java.util.stream.Collectors.counting()));
                        if (byFile.values().stream().anyMatch(count -> count != 1))
                            throw new IllegalStateException("An animation batch reopened its source: " + byFile);
                        result.put("animationBatchHandles", animationHandles);
                        result.put("loaderHandles", phaseHandles);
                        var model = target(candidate);
                        result.put("source", model.expressions().source());
                        result.put("identity", model.expressions().identity().cacheKey());
                        result.put("binding", model.expressions().expressionBinding().key());
                        result.put("programs", model.expressions().programLocations().size());
                        result.put("animations",
                            model.playerResources().animations().keySet().stream().sorted().toList());
                        work.awaitIdle();
                        if (operation.startsWith("seed-full")) {
                            var keep = operation.equals("seed-full-arm") ? model.armExpressions() : model.expressions();
                            var remove =
                                operation.equals("seed-full-arm") ? model.expressions() : model.armExpressions();
                            var path = ExpressionArtifactStore.class.getDeclaredMethod("path",
                                ExpressionArtifactIdentity.class);
                            path.setAccessible(true);
                            var retained = (Path)path.invoke(store, keep.identity());
                            if (!Files.isRegularFile(retained))
                                throw new IllegalStateException("Seed artifact was not persisted: " + retained);
                            Files.deleteIfExists((Path)path.invoke(store, remove.identity()));
                            result.put("retained", retained.toString());
                        }
                        result.put("ready", true);
                    } catch (IllegalStateException failure) {
                        if (!operation.equals("cache-only"))
                            throw failure;
                        result.put("ready", false);
                        result.put("miss", failure.toString());
                    } finally {
                        observing = false;
                        if (candidate != null)
                            candidate.close();
                    }
                    result.put("durationMs", (System.nanoTime() - start) / 1e6);
                    result.put("compilations",
                        metrics.snapshot().stageCalls(dev.semaweave.compilation.EngineMetrics.Stage.BACKEND_COMPILATION)
                            - before);
                    recording.stop();
                    recording.dump(jfr);
                }
                result.put("inputs", INPUTS);
                result.put("purpose", purpose);
                result.put("operation", operation);
                result.put("model", source.modelId().toString());
                result.put("container", source.representation().containerId().toString());
                result.put("inputSha256", java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
                result.put("cacheRoot", cacheRoot.toString());
                result.put("reads",
                    RecordingFile.readAllEvents(jfr).stream()
                        .filter(event -> event.getEventType().getName().equals("jdk.FileRead"))
                        .map(event -> Map.of("path", event.getString("path"), "bytes", event.getLong("bytesRead")))
                        .toList());
                if (purpose == ModelResourcePurpose.GUI) {
                    if (INPUTS.isEmpty() || INPUTS.stream().anyMatch(entry -> !entry.get("purpose").equals("GUI")))
                        throw new IllegalStateException("GUI used a non-GUI input");
                    for (var event : RecordingFile.readAllEvents(jfr)) {
                        if (event.getEventType().getName().equals("jdk.FileRead")
                            && event.getString("path").replace('\\', '/').contains("/full-v1/"))
                            throw new IllegalStateException("GUI read a FULL partition");
                    }
                }
                Files.writeString(output.resolve("result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result));
            }
        } finally {
            if (fullReady != null)
                fullReady.close();
            if (boot != null)
                boot.close();
            loader.close();
            bootLoader.close();
            scheduler.shutdown();
            scheduler.awaitTermination();
            work.close();
            source.representation().close();
        }
    }

    private static ModelRenderTarget target(ModelRenderTargetLoader.ModelCandidate candidate) throws Exception {
        var field = candidate.getClass().getDeclaredField("target");
        field.setAccessible(true);
        return (ModelRenderTarget)field.get(candidate);
    }

    private static ModelRenderTargetLoader.ModelCandidate load(ModelRenderTargetLoader loader,
        ClientResourceScheduler scheduler, ManagedContainer content, ModelResourcePurpose purpose, boolean builtin,
        boolean cacheOnly) throws Exception {
        var subject = builtin ? net.minecraft.world.entity.LivingEntity.class : purpose == ModelResourcePurpose.FULL
            ? net.minecraft.client.player.AbstractClientPlayer.class : CustomGuiPlayerEntity.FakePlayer.class;
        var key = new RenderTargetKey("player", "", loader.bakeProfile(), loader.expressionBinding(builtin, subject),
            purpose);
        var result = loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.BACKGROUND), content, key, builtin,
            cacheOnly, ModelResourceFailures.none()).get(90, TimeUnit.SECONDS);
        if (result instanceof ModelRenderTargetLoader.LoadResult.Failed failed)
            throw new IllegalStateException("Load failed", failed.failure().cause());
        return ((ModelRenderTargetLoader.LoadResult.Ready)result).candidate();
    }
}
