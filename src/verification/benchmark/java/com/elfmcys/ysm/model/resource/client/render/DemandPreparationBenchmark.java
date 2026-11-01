package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.*;
import com.elfmcys.ysm.model.domain.*;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.*;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.google.gson.Gson;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.openjdk.jmh.annotations.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures GUI interaction and FULL selection preparation; headless publication and receiving endpoints exclude GPU and
 * entity installation.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1, jvmArgsAppend = "-Xmx3G")
@Threads(1)
public class DemandPreparationBenchmark {
    @State(Scope.Thread)
    public static class Workload implements ClientDemandCoordinator.Resources, ClientDemandCoordinator.Selections {
        @Param({"cold", "derivedHot", "ready", "handoff", "concurrent"})
        public String scenario;
        @Param({"0"})
        public int unrelatedCatalog;
        @Param({"0"})
        public int viewReads;
        ManagedContainer content;
        private Path root;
        private ExpressionHost host;
        ClientResourceScheduler scheduler;
        java.util.function.Supplier<ClientResourceScheduler> schedulerFactory = () -> new ClientResourceScheduler(2);
        ModelRenderTargetLoader loader;
        ExpressionEngineMetrics metrics;
        ModelRenderTargetCache cache;
        private ClientDemandCoordinator coordinator;
        private ClientDemandCoordinator.PageScope page;
        private Receiver player;
        private Receiver card;
        ClientDemandCoordinator.Target a;
        ClientDemandCoordinator.Target b;
        private ClientDemandCoordinator.Target authority;
        private final Map<Hash256, ModelContent> catalog = new HashMap<>();
        private final List<ResourceLease> leases = new ArrayList<>();
        private final Object owner = new Object();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger publications = new AtomicInteger();
        private int reads;
        private int peakLeases;
        private int sends;
        private long offset;
        private long maxTick;
        private long begin;
        private long firstInstall;
        private ResourceLease readyAnchor;

        @Setup(Level.Trial)
        public void trial() throws Exception {
            NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
            NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
            try (var image = new com.mojang.blaze3d.platform.NativeImage(1, 1, false)) {
                var access = (com.elfmcys.ysm.mixin.client.NativeImageAccessor)(Object)image;
                if (access.ysm$pixels() == 0 || access.ysm$size() != 4)
                    throw new IllegalStateException("NativeImage field getter contract changed");
            }
            if (!RenderSystem.isOnRenderThread())
                RenderSystem.initRenderThread();
            var events = com.elfmcys.ysm.YesSteveModel.class.getDeclaredField("EVENT_BUS");
            events.setAccessible(true);
            events.set(null, net.minecraftforge.eventbus.api.BusBuilder.builder().build());
            com.elfmcys.ysm.client.model.locator.PlayerLocator.init();
            host = ExpressionHost.create(HostAvailability.none());
            var builtin =
                Path.of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default")).toURI());
            try (var directory = new Directory(builtin)) {
                var memory = ModelParser.parseBuiltinDefaultMemory(directory);
                var bytes = memory.bytes();
                content = ManagedContainer.openResidentDefault(bytes,
                    new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            }
            var textures = content.modelFile().requireRenderTarget("player").descriptor().textures().keySet().stream()
                .sorted().toList();
            if (textures.size() < 2)
                throw new IllegalStateException("Two texture targets are required");
            a = new ClientDemandCoordinator.Target(content.modelId(), textures.get(0));
            b = new ClientDemandCoordinator.Target(content.modelId(), textures.get(1));
            root = Files.createTempDirectory("ysm-demand-jmh-").toAbsolutePath().normalize();
            catalog.put(content.modelId(), content);
            for (int i = 0; i < unrelatedCatalog; i++) {
                var hash = new byte[Hash256.SIZE];
                java.nio.ByteBuffer.wrap(hash).putInt(i).putInt(0x7fffffff);
                catalog.put(new Hash256(hash), content);
            }
            System.out.println("DEMAND_INPUT " + content.representation().identity() + " textures=" + textures
                + " workers=2 gpu=headless receiver=native-extract content=resident");
        }

        @Setup(Level.Invocation)
        public void invocation() throws Exception {
            player = card = null;
            if (scenario.equals("cold"))
                clearArtifacts();
            scheduler = schedulerFactory.get();
            var shared = new AtomicSharedCache(root);
            metrics = new ExpressionEngineMetrics();
            var modules =
                new RenderTargetExpressionModuleFactory(new ExpressionArtifactStore(root, shared), host, metrics);
            loader = new ModelRenderTargetLoader(new BakedModelCache(root, shared), modules, scheduler,
                new DefaultAnimationRuntime());
            var manager = (TextureManager)UnsafeUtil.getUnsafe().allocateInstance(TextureManager.class);
            cache = new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, error -> {
                throw new IllegalStateException(error);
            }, prepared -> {
                publications.incrementAndGet();
                // Decode and geometry are real; no OpenGL context is created by this benchmark.
                prepared.get();
                prepared.close();
                return new HostTexturePublisher.PublishedTextureBinding(new ResourceLocation("ysm", "headless"),
                    manager, List.of());
            }, () -> 4, () -> 0);
            authority = new ClientDemandCoordinator.Target(null, "");
            coordinator = new ClientDemandCoordinator(() -> System.nanoTime() / 1_000_000L + offset, this, this);
            page = coordinator.openPage("fixed-page");
            if (scenario.equals("ready")) {
                readyAnchor =
                    getOrStart(resourceRequest(a.modelId(), "player", a.texture(), ModelResourcePurpose.FULL, null),
                        WorkPriority.BACKGROUND);
                await(() -> readyAnchor.poll() instanceof AcquireResult.Ready);
            }
            player = new Receiver(this, a, ModelResourcePurpose.FULL, false);
            card = new Receiver(this, scenario.equals("concurrent") ? b : a, ModelResourcePurpose.GUI, true);
            page.card(card, card.target);
            starts.set(0);
            publications.set(0);
            reads = sends = peakLeases = 0;
            maxTick = firstInstall = 0;
        }

        private void tick() {
            var start = System.nanoTime();
            cache.tick();
            coordinator.tick();
            maxTick = Math.max(maxTick, System.nanoTime() - start);
            for (int i = 0; i < viewReads; i++) {
                coordinator.feedback();
                if (card != null)
                    coordinator.feedback(card.target);
            }
            peakLeases = Math.max(peakLeases, leases.size());
        }

        private void await(java.util.function.BooleanSupplier done) throws Exception {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!done.getAsBoolean()) {
                tick();
                for (var lease : leases)
                    if (lease.poll() instanceof AcquireResult.Failed failed)
                        throw new IllegalStateException("Resource preparation failed", failed.failure().cause());
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("Preparation exceeded 60 seconds");
                Thread.sleep(1);
            }
        }

        long run() throws Exception {
            begin = System.nanoTime();
            if (scenario.equals("handoff")) {
                page.pointer(a);
                offset += 301;
                await(() -> card.lease != null);
            }
            coordinator.select(a);
            player.scope = coordinator.register(player, WorkPriority.LOCAL_PLAYER);
            if (scenario.equals("concurrent")) {
                page.pointer(b);
                offset += 301;
            }
            await(() -> player.lease != null && (!scenario.equals("concurrent") || card.lease != null));
            if (coordinator.feedback().waiting())
                throw new IllegalStateException("Receiver installation did not end selection feedback");
            var expected =
                scenario.equals("concurrent") || scenario.equals("handoff") ? 2 : scenario.equals("ready") ? 0 : 1;
            if (starts.get() != expected || publications.get() != expected || sends != 1)
                throw new IllegalStateException("Unexpected duplicate preparation or selection count");
            return player.vertices + card.vertices;
        }

        @TearDown(Level.Invocation)
        public void retire() throws Exception {
            System.out.println("DEMAND_SAMPLE "
                + new Gson().toJson(Map.ofEntries(Map.entry("scenario", scenario), Map.entry("starts", starts.get()),
                    Map.entry("publications", publications.get()), Map.entry("contentReads", reads),
                    Map.entry("peakLeases", peakLeases), Map.entry("firstInstallMs", (firstInstall - begin) / 1e6),
                    Map.entry("playerInstallMs", (player.installedAt - begin) / 1e6),
                    Map.entry("cardInstallMs", (card.installedAt - begin) / 1e6), Map.entry("maxTickMs", maxTick / 1e6),
                    Map.entry("vertices", player.vertices + card.vertices), Map.entry("catalog", unrelatedCatalog),
                    Map.entry("viewReads", viewReads))));
            System.out.println("DEMAND_METRICS " + new Gson().toJson(metrics.snapshot()));
            retireResources();
        }

        void retireResources() throws Exception {
            try {
                coordinator.close();
                player.close();
                card.close();
                if (readyAnchor != null) {
                    readyAnchor.close();
                    readyAnchor = null;
                }
                cache.tick();
                if (!leases.isEmpty() || cache.loadingCount() != 0 || cache.readyCount() != 0)
                    throw new IllegalStateException("Retired demand still owns resources");
            } finally {
                cache.close();
                try {
                    loader.close();
                } finally {
                    scheduler.shutdown();
                    scheduler.awaitTermination();
                }
            }
            System.out.println("DEMAND_RETIRED leases=" + leases.size() + " pending=" + cache.loadingCount() + " ready="
                + cache.readyCount() + " expressionOwners=" + Arrays.toString(metrics.snapshot().activeOwners()));
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            if (content != null)
                content.representation().close();
            clearArtifacts();
            Files.delete(root);
        }

        private void clearArtifacts() throws Exception {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    if (!path.equals(root))
                        Files.delete(path);
            }
        }

        public ModelContent content(Hash256 id) {
            reads++;
            return catalog.get(id);
        }

        public ResourceRequest resourceRequest(Hash256 id, String target, String texture, ModelResourcePurpose purpose,
            Class<?> subject) {
            return new ResourceRequest(id, target, texture, loader.bakeProfile(),
                loader.expressionBinding(AbstractClientPlayer.class), purpose);
        }

        public ResourceRequest defaultResourceRequest(String target, ModelResourcePurpose purpose, Class<?> subject) {
            return resourceRequest(a.modelId(), target, a.texture(), purpose, subject);
        }

        private RenderTargetKey key(ResourceRequest request) {
            return new RenderTargetKey(request.targetId(), request.requestedTexture(), request.bakeProfile(),
                request.expressionBinding(), request.purpose());
        }

        private ResourceLease track(ResourceLease lease) {
            ResourceLease wrapped = new ResourceLease() {
                boolean closed;

                public ModelResourcePurpose purpose() {
                    return lease.purpose();
                }

                public AcquireResult poll() {
                    return lease.poll();
                }

                public boolean isCurrent(ResourceRequest request) {
                    return lease.isCurrent(request);
                }

                public void cancelPending() {
                    lease.cancelPending();
                }

                public void close() {
                    if (!closed) {
                        closed = true;
                        lease.close();
                        leases.remove(this);
                    }
                }
            };
            leases.add(wrapped);
            return wrapped;
        }

        public Optional<ResourceLease> findExactReady(ResourceRequest request) {
            return cache.findReady(request, content, key(request), (identity, key, asked) -> true).map(this::track);
        }

        public Optional<ResourceLease> findReady(ResourceRequest request) {
            var full = new ResourceRequest(request.modelId(), request.targetId(), request.requestedTexture(),
                request.bakeProfile(), request.expressionBinding(), ModelResourcePurpose.FULL);
            var ready = cache.findReady(request, content, key(full), (identity, key, asked) -> true).map(this::track);
            return ready.isPresent() ? ready : findExactReady(request);
        }

        public boolean permitsAcquisition(ResourceRequest request) {
            return true;
        }

        public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request,
            WorkPriority priority) {
            return CompletableFuture.completedFuture(findExactReady(request));
        }

        public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request,
            WorkPriority priority) {
            return CompletableFuture.completedFuture(Optional.of(getOrStart(request, priority)));
        }

        public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
            return track(cache.getOrStart(request, content, key(request), (cancelled, dispatch) -> {
                starts.incrementAndGet();
                return loader.loadAsync(cancelled, dispatch, content, key(request), true, ModelResourceFailures.none());
            }, (identity, key, asked) -> true, owner, priority));
        }

        public void reportActiveModelUse(ResourceRequest request) {}

        public void reportActiveModelFailure(ModelContent content, boolean fallback) {}

        public ClientDemandCoordinator.SelectionContext context() {
            return new ClientDemandCoordinator.SelectionContext(owner, false, true);
        }

        public ClientDemandCoordinator.Target authority() {
            return authority;
        }

        public boolean installed(ClientDemandCoordinator.Target target) {
            return player != null && player.lease != null && target.equals(player.target);
        }

        public void send(ClientDemandCoordinator.Target target, long id) {
            sends++;
            authority = target;
        }

        public void reportFailure(Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class Receiver
        implements ClientDemandCoordinator.Installation, ClientDemandCoordinator.CardDisplay, AutoCloseable {
        final Workload workload;
        final ClientDemandCoordinator.Target target;
        final ModelResourcePurpose purpose;
        final boolean pageOwned;
        ClientDemandCoordinator.EntityScope scope;
        ResourceLease lease;
        long vertices;
        long installedAt;

        Receiver(Workload workload, ClientDemandCoordinator.Target target, ModelResourcePurpose purpose,
            boolean pageOwned) {
            this.workload = workload;
            this.target = target;
            this.purpose = purpose;
            this.pageOwned = pageOwned;
        }

        public ClientDemandCoordinator.Assignment assignment() {
            return new ClientDemandCoordinator.Assignment(
                workload.resourceRequest(target.modelId(), "player", target.texture(), purpose, null), null,
                pageOwned ? ModelResourceDemand.Mode.READY : ModelResourceDemand.Mode.ONLINE, true);
        }

        public void activate(ModelResourceDemand.Mode mode) {
            if (scope == null || scope.isClosed())
                scope = workload.coordinator.register(this);
        }

        public void deactivate() {
            if (scope != null)
                scope.close();
            close();
        }

        public boolean hasInstallation() {
            return lease != null;
        }

        public boolean matches(ResourceRequest request, boolean full) {
            return lease != null;
        }

        public Throwable failure(ResourceRequest request) {
            return null;
        }

        public ClientDemandCoordinator.InstallationResult install(ResourceRequest request, ResourceLease candidate,
            boolean fallback, boolean full) {
            var target = ((AcquireResult.Ready)candidate.poll()).target();
            var variant = target.playerResources().defaultVariant();
            if (candidate.purpose() == ModelResourcePurpose.FULL && variant.armModel() == null)
                throw new IllegalStateException("FULL arm capability is absent");
            try (var output = NativeModelState.create()) {
                for (var model : variant.armModel() == null ? List.of(variant.mainModel())
                    : List.of(variant.mainModel(), variant.armModel())) {
                    if (!output.extract(model.bakedModel(), new AnimatedGeoModel(model).getBoneAttributes()))
                        throw new IllegalStateException("Native extract failed");
                    if (output.getTotalVertexCount() <= 0)
                        throw new IllegalStateException("Native extract produced no geometry");
                    vertices += output.getTotalVertexCount();
                }
            }
            close();
            lease = candidate;
            installedAt = System.nanoTime();
            if (workload.firstInstall == 0)
                workload.firstInstall = System.nanoTime();
            return ClientDemandCoordinator.InstallationResult.INSTALLED;
        }

        public void close() {
            if (lease != null) {
                lease.close();
                lease = null;
            }
        }
    }

    @Benchmark
    public long prepare(Workload workload) throws Exception {
        return workload.run();
    }
}
