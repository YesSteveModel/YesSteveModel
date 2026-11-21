package com.elfmcys.ysm.model.resource.client.render;

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
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingFile;

/** Seeds FULL in one process and verifies its physical animation read batches in another. */
public final class FullAnimationReadProbe {
    public static void main(String[] args) throws Exception {
        var root = Files.createDirectories(Path.of(args[0]));
        var output = Files.createDirectories(Path.of(args[1]));
        var modelPath = args[2];
        var operation = args[3];
        if (!Set.of("seed", "read").contains(operation))
            throw new IllegalArgumentException("Expected seed or read");
        NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
        RenderSystem.initRenderThread();
        var events = com.elfmcys.ysm.YesSteveModel.class.getDeclaredField("EVENT_BUS");
        events.setAccessible(true);
        events.set(null, net.minecraftforge.eventbus.api.BusBuilder.builder().build());
        com.elfmcys.ysm.client.model.locator.PlayerLocator.init();
        var builtinRoot = Path.of(
            Objects.requireNonNull(FullAnimationReadProbe.class.getResource("/assets/ysm/builtin/default")).toURI());
        var raw = Path.of(Objects
            .requireNonNull(FullAnimationReadProbe.class.getResource("/assets/ysm/builtin/" + modelPath)).toURI());
        var inputRoot = Files.createDirectories(root.resolve("input"));
        Path input;
        try (var files = Files.list(inputRoot)) {
            input = files.filter(file -> file.toString().endsWith(".ysm")).findFirst().orElse(null);
        }
        if (input == null) {
            try (var index = FullAnimationReadProbe.class.getResourceAsStream("/assets/ysm/builtin-index.json");
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
            new RenderTargetExpressionModuleFactory(new ExpressionArtifactStore(bootRoot, bootCache),
                new AnimationInputStore(bootRoot, bootCache), host, new ExpressionEngineMetrics()),
            scheduler, defaults);
        var cacheRoot = Files.createDirectories(root.resolve("cache"));
        var cache = new AtomicSharedCache(cacheRoot, work);
        var metrics = new ExpressionEngineMetrics();
        var store = new ExpressionArtifactStore(cacheRoot, cache);
        var loader = new ModelRenderTargetLoader(new BakedModelCache(cacheRoot.resolve("bake"), cache),
            new RenderTargetExpressionModuleFactory(store, new AnimationInputStore(cacheRoot, cache), host, metrics),
            scheduler, defaults);
        ModelRenderTargetLoader.ModelCandidate boot = null;
        try (var directory = new Directory(builtinRoot)) {
            var parsed = ModelParser.parseBuiltinDefaultMemory(directory);
            var bytes = parsed.bytes();
            var builtin = ManagedContainer.openResidentDefault(bytes,
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            try (var representation = builtin.representation()) {
                boot = load(bootLoader, scheduler, builtin, true);
                defaults.publishSources(target(boot).defaultSources());
                work.awaitIdle();
                var purpose = ModelResourcePurpose.FULL;
                var jfr = output.resolve("reads.jfr");
                Map<String, Object> result = new LinkedHashMap<>();
                try (var recording = new Recording()) {
                    recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
                    recording.start();
                    var before = metrics.snapshot()
                        .stageCalls(dev.semaweave.compilation.EngineMetrics.Stage.BACKEND_COMPILATION);
                    com.elfmcys.ysm.benchmark.ReadHandleProbe.clear();
                    var start = System.nanoTime();
                    ModelRenderTargetLoader.ModelCandidate candidate = null;
                    try {
                        candidate = load(loader, scheduler, source, false);
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
                        result.put("source", model.animationStore().diagnostics().source());
                        result.put("identity", model.animationStore().diagnostics().identity() == null ? null
                            : model.animationStore().diagnostics().identity().cacheKey());
                        result.put("binding", model.animationStore().metadata().expressionBinding().key());
                        result.put("programs", model.animationStore().diagnostics().programLocations().size());
                        result.put("animations", model.animations().keySet().stream().sorted().toList());
                        work.awaitIdle();
                        if (operation.equals("read")
                            && (model.animationStore().diagnostics().source() != ExpressionArtifactStore.Source.DISK
                                || animationHandles.isEmpty()))
                            throw new IllegalStateException(
                                "FULL disk discovery did not exercise a lazy animation read");
                        result.put("ready", true);
                    } finally {
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
                if (operation.equals("read") && ((Number)result.get("compilations")).longValue() != 0)
                    throw new IllegalStateException("FULL disk discovery recompiled expressions");
                Files.writeString(output.resolve("result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result));
            }
        } finally {
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
        ClientResourceScheduler scheduler, ManagedContainer content, boolean builtin) throws Exception {
        var subject = builtin ? net.minecraft.world.entity.LivingEntity.class
            : net.minecraft.client.player.AbstractClientPlayer.class;
        var key = new RenderTargetKey("player", "", loader.bakeProfile(), loader.expressionBinding(builtin, subject),
            ModelResourcePurpose.FULL);
        var result = loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.BACKGROUND), content, key, builtin,
            false, ModelResourceFailures.none()).get(90, TimeUnit.SECONDS);
        if (result instanceof ModelRenderTargetLoader.LoadResult.Failed failed)
            throw new IllegalStateException("Load failed", failed.failure().cause());
        return ((ModelRenderTargetLoader.LoadResult.Ready)result).candidate();
    }
}
