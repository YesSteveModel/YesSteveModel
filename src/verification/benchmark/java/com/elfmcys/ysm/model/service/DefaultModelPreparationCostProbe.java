package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.storage.DerivedStorageWork;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayer;

/** Stateful preparation and live-owner costs; GPU publication and game ticks are outside the measured boundary. */
public final class DefaultModelPreparationCostProbe {
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final List<Map<String, Object>> BUILDS = new CopyOnWriteArrayList<>();
    private static Path output;
    private static boolean observed;
    private static String phase;

    private DefaultModelPreparationCostProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Expected an output directory");
        output = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
        set(com.elfmcys.ysm.YesSteveModel.class, null, "EVENT_BUS",
            net.minecraftforge.eventbus.api.BusBuilder.builder().build());
        RequiredDefaultResidencyIntegrationTest.initializeHostTypes();
        com.elfmcys.ysm.config.ClientConfig.init()
            .setConfig(com.electronwill.nightconfig.core.CommentedConfig.inMemory());
        THREADS.setThreadAllocatedMemoryEnabled(true);
        // Class loading is paid once before the retained-heap comparisons.
        run(output.resolve("warmup"), "warmup", false);
        observed = true;
        checkpoint("process-before-input", Map.of());
        run(output.resolve("measured"), "cold", true);
        run(output.resolve("measured"), "disk", false);
        checkpoint("process-after-close", Map.of("serviceUnpublished", ClientModelService.current().isEmpty()));
        Files.writeString(output.resolve("complete.json"), "{\"status\":\"passed\"}\n");
    }

    private static void run(Path root, String run, boolean instances) throws Exception {
        var samples = new ArrayList<Map<String, Object>>();
        var leases = new ArrayList<ResourceLease>();
        var owners = new ArrayList<CostPlayer>();
        var fixture = new RequiredDefaultResidencyIntegrationTest.Fixture(root, false);
        var ordinary = ManagedContainer.openDirect(fixture.container, fixture.resident.location());
        try {
            set(ClientModelService.class, null, "INSTANCE", fixture.service);
            var beforeCatalog = fixture.catalogs.snapshot();
            var intrinsic = beforeCatalog.catalog().intrinsicDefault();
            var record =
                new CatalogRecord(ordinary.location(), new CatalogContentBinding(ordinary.modelId(), ordinary));
            var snapshot = new ClientCatalogSnapshot(new CatalogSnapshot(Map.of(ordinary.modelId(), record), intrinsic,
                List.of(), beforeCatalog.catalog().report()));
            set(ClientCatalogManager.class, fixture.catalogs, "catalog", snapshot);
            fixture.manager.catalogChanged(new ClientCatalogManager.CatalogChange(beforeCatalog, snapshot));
            instrumentBuilds(fixture);
            BUILDS.clear();
            checkpoint(run + "-input", Map.of("sourceModelId", ordinary.modelId().toString(), "containerBytes",
                Files.size(fixture.container), "soundFixture", false));
            measure(run + "-required", fixture, samples,
                () -> fixture.manager.startRequired(fixture.catalogs.initialize()).get(60, TimeUnit.SECONDS));
            var full = fixture.manager.request(PublishedModelId.model(ordinary.modelId()), "player", "default",
                ModelResourcePurpose.FULL, AbstractClientPlayer.class);
            measure(run + "-ordinary-full", fixture, samples, () -> {
                for (var texture : ordinary.modelFile().requireRenderTarget("player").getTextureNames()) {
                    var request = fixture.manager.request(PublishedModelId.model(ordinary.modelId()), "player", texture,
                        ModelResourcePurpose.FULL, AbstractClientPlayer.class);
                    leases.add(await(fixture, request));
                }
            });
            measure(run + "-ordinary-gui", fixture, samples, () -> {
                for (var texture : ordinary.modelFile().requireRenderTarget("player").getTextureNames()) {
                    var request = fixture.manager.request(PublishedModelId.model(ordinary.modelId()), "player", texture,
                        ModelResourcePurpose.GUI, FakePlayer.class);
                    leases.add(await(fixture, request));
                }
            });
            if (instances) {
                var intrinsicRequest = fixture.manager.request(PublishedModelId.INTRINSIC_DEFAULT, "player", "default",
                    ModelResourcePurpose.FULL, null);
                measure(run + "-intrinsic-first-install", fixture, samples, () -> {
                    var owner = new CostPlayer();
                    owner.install(intrinsicRequest, await(fixture, intrinsicRequest));
                    owners.add(owner);
                });
                measure(run + "-ordinary-first-install", fixture, samples, () -> {
                    var owner = new CostPlayer();
                    owner.install(full, await(fixture, full));
                    owners.add(owner);
                });
                measure(run + "-ordinary-next-99", fixture, samples, () -> {
                    for (int index = 0; index < 99; index++) {
                        var owner = new CostPlayer();
                        owner.install(full, await(fixture, full));
                        owners.add(owner);
                    }
                    require(owners.get(1).getLoadedGeoModel() != owners.get(2).getLoadedGeoModel(),
                        "Ordinary instances shared mutable geometry");
                    require(owners.get(1).getExpressionRuntime() != owners.get(2).getExpressionRuntime(),
                        "Ordinary instances shared expression state");
                });
                measure(run + "-retire-instances", fixture, samples, () -> {
                    for (var owner : owners) {
                        owner.retire();
                        require(!owner.hasUnfinishedWork() && !owner.hasInstalledModel(), "Owner did not retire");
                    }
                    owners.clear();
                });
            }
            for (var lease : leases)
                lease.close();
            leases.clear();
            fixture.manager.tick();
            drain(fixture);
            if (observed)
                Files.writeString(output.resolve(run + "-samples.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(samples) + "\n");
        } finally {
            for (var owner : owners)
                owner.retire();
            for (var lease : leases)
                lease.close();
            fixture.close();
            ordinary.representation().close();
        }
        checkpoint(run + "-closed",
            Map.of("loading", fixture.manager.loadingCount(), "schedulerAndTimerTerminated", true), fixture.metrics);
    }

    private static void instrumentBuilds(RequiredDefaultResidencyIntegrationTest.Fixture fixture) throws Exception {
        var loader = field(fixture.manager, "loader");
        var consumer = field(loader, "consumerStage");
        var consumerType = consumer.getClass().getInterfaces()[0];
        set(loader.getClass(), loader, "consumerStage", Proxy.newProxyInstance(consumerType.getClassLoader(),
            new Class<?>[] {consumerType}, (proxy, method, args) -> {
                var started = System.nanoTime();
                Object candidate;
                try {
                    method.setAccessible(true);
                    candidate = method.invoke(consumer, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
                var target = (ModelRenderTarget)field(candidate, "target");
                var request = args[1];
                var item = new LinkedHashMap<String, Object>();
                item.put("phase", phase);
                item.put("intrinsic", field(request, "defaultModel"));
                item.put("purpose", field(request, "purpose").toString());
                item.put("texture", field(request, "selectedTexture").toString());
                item.put("consumerMillis", (System.nanoTime() - started) / 1e6);
                item.put("mainSource", target.animationStore().diagnostics().source().name());
                item.put("mainPrograms", target.animationStore().diagnostics().programLocations().size());
                var arm = target.playerResources() == null ? null : target.armAnimationStore();
                if (arm != null) {
                    item.put("armSource", arm.diagnostics().source().name());
                    item.put("armPrograms", arm.diagnostics().programLocations().size());
                }
                BUILDS.add(item);
                return candidate;
            }));
    }

    private static void measure(String name, RequiredDefaultResidencyIntegrationTest.Fixture fixture,
        List<Map<String, Object>> samples, Work work) throws Exception {
        phase = name;
        var before = fixture.metrics.snapshot();
        var allocations = allocations();
        int buildStart = BUILDS.size();
        long start = System.nanoTime();
        work.run();
        drain(fixture);
        long nanos = System.nanoTime() - start;
        var allocated = allocations();
        long bytes = 0;
        for (var entry : allocated.entrySet())
            bytes += Math.max(0, entry.getValue() - allocations.getOrDefault(entry.getKey(), 0L));
        var after = fixture.metrics.snapshot();
        var sample = new LinkedHashMap<String, Object>();
        sample.put("phase", name);
        sample.put("milliseconds", nanos / 1e6);
        sample.put("survivingThreadAllocatedBytes", bytes);
        sample.put("endedThreads", allocations.keySet().stream().filter(id -> !allocated.containsKey(id)).toList());
        sample.put("builds", List.copyOf(BUILDS.subList(buildStart, BUILDS.size())));
        sample.put("stageCalls", difference(before.stageCalls(), after.stageCalls()));
        sample.put("programDecodes", after.portableProgramDecodes() - before.portableProgramDecodes());
        sample.put("activeOwners", after.activeOwners());
        sample.put("loading", fixture.manager.loadingCount());
        require(fixture.manager.loadingCount() == 0, "Resource work is still loading");
        samples.add(sample);
        checkpoint(name, sample);
    }

    private static ResourceLease await(RequiredDefaultResidencyIntegrationTest.Fixture fixture, ResourceRequest request)
        throws Exception {
        var lease = fixture.manager.getOrStart(request);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!(lease.poll() instanceof AcquireResult.Ready)) {
            if (lease.poll() instanceof AcquireResult.Failed failure)
                throw new IllegalStateException("Preparation failed", failure.failure().cause());
            fixture.manager.tick();
            if (System.nanoTime() > deadline)
                throw new IllegalStateException("Preparation timed out");
            Thread.sleep(1);
        }
        return lease;
    }

    private static void drain(RequiredDefaultResidencyIntegrationTest.Fixture fixture) throws Exception {
        fixture.cache.derivedWork().awaitIdle();
        ((DerivedStorageWork)field(fixture.storage, "derivedWork")).awaitIdle();
    }

    private static Map<Long, Long> allocations() {
        var result = new LinkedHashMap<Long, Long>();
        for (long id : THREADS.getAllThreadIds()) {
            long bytes = THREADS.getThreadAllocatedBytes(id);
            if (bytes >= 0)
                result.put(id, bytes);
        }
        return result;
    }

    private static void checkpoint(String name, Map<String, Object> details) throws Exception {
        checkpoint(name, details, null);
    }

    private static void checkpoint(String name, Map<String, Object> details,
        com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics closedMetrics) throws Exception {
        if (!observed)
            return;
        for (int iteration = 0; iteration < 3; iteration++) {
            System.gc();
            System.runFinalization();
            Thread.sleep(100);
        }
        if (closedMetrics != null) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (java.util.Arrays.stream(closedMetrics.snapshot().activeOwners()).anyMatch(value -> value != 0)) {
                require(System.nanoTime() < deadline, "Closed expression owners were not collected");
                System.gc();
                Thread.sleep(100);
            }
        }
        var result = new LinkedHashMap<String, Object>(details);
        if (closedMetrics != null)
            result.put("activeOwners", closedMetrics.snapshot().activeOwners());
        result.put("phase", name);
        result.put("pid", ProcessHandle.current().pid());
        result.put("heapUsed", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        var pools = new LinkedHashMap<String, Object>();
        for (var pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            pools.put(pool.getName(), Map.of("count", pool.getCount(), "memoryUsed", pool.getMemoryUsed(),
                "totalCapacity", pool.getTotalCapacity()));
        result.put("bufferPools", pools);
        Files.writeString(output.resolve(name + ".json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(result));
        var ack = output.resolve(name + ".ack");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(ack)) {
            if (System.nanoTime() > deadline)
                throw new IllegalStateException("External memory observation timed out: " + name);
            Thread.sleep(10);
        }
    }

    private static long[] difference(long[] before, long[] after) {
        var result = new long[before.length];
        for (int index = 0; index < result.length; index++)
            result[index] = after[index] - before[index];
        return result;
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Class<?> type, Object object, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static final class CostPlayer extends CustomPlayerEntity {
        CostPlayer() throws InstantiationException {
            super((Player)UnsafeUtil.getUnsafe().allocateInstance(RemotePlayer.class), false, false);
        }

        void install(ResourceRequest request, ResourceLease lease) {
            installReadyForPreview(request, lease);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public int getFrameRateLimit() {
            return 60;
        }
    }
}
