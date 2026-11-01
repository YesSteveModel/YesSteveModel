package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Real Java file-read calls through the manager; catalog discovery and GPU publication are outside this probe. */
public final class DemandDiskReadVerification {
    private DemandDiskReadVerification() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Expected an output directory");
        var output = Files.createDirectories(Path.of(args[0]).toAbsolutePath().normalize());
        var summary = output.resolve("summary.json");
        Files.deleteIfExists(summary);
        // Repeated verification must not turn the cold controls into warm-cache runs.
        var run = Files.createTempDirectory(output, "run-");
        NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
        try (var image = new com.mojang.blaze3d.platform.NativeImage(1, 1, false)) {
            var access = (com.elfmcys.ysm.mixin.client.NativeImageAccessor)(Object)image;
            require(access.ysm$pixels() != 0 && access.ysm$size() == 4, "NativeImage access agent is required");
        }
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
        setField(com.elfmcys.ysm.YesSteveModel.class, null, "EVENT_BUS",
            net.minecraftforge.eventbus.api.BusBuilder.builder().build());
        com.elfmcys.ysm.client.model.locator.PlayerLocator.init();

        var samples = new ArrayList<Sample>();
        var result = new LinkedHashMap<String, Object>();
        result.put("runDirectory", run.toString());
        try (var input = new Input(run.resolve("fixtures"))) {
            for (var source : List.of("local", "remote", "bake")) {
                var root = Files.createDirectories(run.resolve(source));
                if (source.equals("bake")) {
                    // A fresh loader below must read the persisted cache instead of retaining PreparedTarget values.
                    try (var warm = new Fixture(input, root, false)) {
                        warm.page = warm.coordinator.openPage("warm");
                        warm.addCard(2);
                        warm.await(() -> warm.card.lease != null);
                    }
                }
                try (var fixture = new Fixture(input, root, source.equals("remote"))) {
                    fixture.page = fixture.coordinator.openPage("seed");
                    var rapid = fixture.record(source, "rapid-through-300ms", () -> {
                        for (int index = 0; index < 3; index++) {
                            long start = 100L + index * 300L;
                            fixture.clock.set(start);
                            fixture.page = fixture.coordinator.replacePage(fixture.page, "page-" + index);
                            fixture.addCard(index);
                            fixture.tick();
                            fixture.clock.set(start + 299);
                            fixture.tick();
                            fixture.clock.set(start + 300);
                            fixture.tick();
                        }
                        require(fixture.card.lease == null, "The exact 300 ms boundary must still wait");
                    });
                    require(
                        rapid.totalCalls() == 0 && rapid.acquisitionCalls == 0 && fixture.manager.loadingCount() == 0,
                        "Rapid pages started resource work: " + source);
                    samples.add(rapid);

                    var stable = fixture.record(source, "stable-301ms", () -> {
                        fixture.clock.set(1001);
                        fixture.await(() -> fixture.card.lease != null);
                    });
                    var expected = source.equals("bake") ? "bakeGeometry" : source;
                    require(stable.calls(expected) > 0 && stable.bytes(expected) > 0,
                        "The positive control did not read " + expected);
                    require(
                        stable.groups.stream()
                            .anyMatch(group -> group.kind.equals(expected) && group.bytes > 0
                                && (!source.equals("bake") || group.path.endsWith(".geo.ysm-cache"))
                                && group.stack.stream()
                                    .anyMatch(frame -> source.equals("bake")
                                        ? frame.equals(BakedModelCache.class.getName() + ".read")
                                        : frame.contains(
                                            source.equals("remote") ? "RemoteModelStore" : "FileChunkDataSource"))),
                        "The positive control lacks its production read stack: " + expected);
                    samples.add(stable);

                    // Ready can precede staging validation; settled ticks must not inherit its file reads.
                    fixture.storageWork.awaitIdle();
                    var settled = fixture.record(source, "settled-repeat-ticks", () -> {
                        for (int count = 0; count < 100; count++) {
                            fixture.clock.incrementAndGet();
                            fixture.tick();
                        }
                    });
                    require(settled.totalCalls() == 0 && settled.acquisitionCalls == 0
                        && fixture.manager.loadingCount() == 0, "Settled ticks restarted resource work: " + source);
                    samples.add(settled);

                    var request = fixture.request(input.targets.get(2));
                    try (var anchor = fixture.manager.findReady(request).orElseThrow()) {
                        var ready = fixture.record(source, "ready-before-local-admission", () -> {
                            var start = fixture.clock.get();
                            fixture.page = fixture.coordinator.replacePage(fixture.page, "ready-seed");
                            fixture.clock.set(start + 100);
                            fixture.page = fixture.coordinator.replacePage(fixture.page, "ready-reuse");
                            fixture.addCard(2);
                            fixture.tick();
                            require(fixture.card.lease != null, "A qualified Ready card must install immediately");
                            require(anchor.poll() instanceof AcquireResult.Ready, "Independent Ready anchor was lost");
                        });
                        require(ready.totalCalls() == 0 && ready.acquisitionCalls == 0
                            && fixture.manager.loadingCount() == 0, "Ready reuse started resource work: " + source);
                        samples.add(ready);
                    }
                }
            }
            result.put("metric", "jdk.FileRead calls and bytes, threshold=0; not physical device I/O");
            result.put("chain", "ClientDemandCoordinator -> ClientModelRenderTargetManager -> actual loader/cache");
            result.put("limits",
                List.of("Catalog snapshots are fixture-injected; no directory discovery/admission",
                    "Required builtin initialization is real and precedes recording", "Texture publication is headless",
                    "Derived persistence drains after Ready and before the settled recording",
                    "Monotonic demand time is controlled; filesystem calls and worker completion are real"));
            result.put("models", input.targets.stream().map(target -> target.modelId().toString()).toList());
            result.put("samples", samples);
        }
        Files.writeString(summary, new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("DEMAND_DISK_READ_VERIFIED " + summary);
    }

    private static final class Input implements AutoCloseable {
        final Path localRoot;
        final Path remoteRoot;
        final ManagedContainer builtin;
        final List<ManagedContainer> local = new ArrayList<>();
        final List<RemoteModelContent> remote = new ArrayList<>();
        final List<ClientDemandCoordinator.Target> targets = new ArrayList<>();
        final RemoteModelStore store;

        Input(Path root) throws Exception {
            localRoot = Files.createDirectories(root.resolve("local-models"));
            remoteRoot = Files.createDirectories(root.resolve("remote-cache"));
            store = new RemoteModelStore(remoteRoot);
            try (var directory = resourceDirectory("/assets/ysm/builtin/default")) {
                var memory = ModelParser.parseBuiltinDefaultMemory(directory);
                var bytes = memory.bytes();
                builtin = ManagedContainer.openResidentDefault(bytes,
                    new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            }
            for (var name : List.of("1_alex", "2_steve", "3_default_boy")) {
                var destination = Files.createDirectories(localRoot.resolve(name));
                final Path file;
                try (var directory = resourceDirectory("/assets/ysm/builtin/misc/" + name)) {
                    file = ModelParser.parse(directory, destination, DefaultAnimationFilter.keepAll());
                }
                var content =
                    ManagedContainer.openDirect(file, new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath(name)));
                local.add(content);
                var texture = content.modelFile().requireRenderTarget("player").getTextureNames().stream().sorted()
                    .findFirst().orElseThrow();
                targets.add(new ClientDemandCoordinator.Target(content.modelId(), texture));
                {
                    var prefix = content.representation().metadataPrefix().orElseThrow();
                    var published = store.commitMetadataPrefix(content.representation().identity(), prefix);
                    published.representation().close();
                }
                var asset = content.modelFile().getFileView().getAssetView();
                var manifest = asset.getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME);
                long end = (long)manifest.offset() + manifest.size();
                for (var chunk : asset.getChunkTable().values()) {
                    if (chunk.offset() < end)
                        continue;
                    {
                        var bytes = content.chunks().readStoredVerified(chunk, BufferType.ARRAY);
                        store.publishChunk(content.modelId(), chunk, bytes);
                    }
                }
                remote.add(store.openContent(content.representation().identity()));
            }
            require(targets.stream().map(ClientDemandCoordinator.Target::modelId).distinct().count() == 3,
                "Rapid pages must use three distinct model identities");
            require(targets.stream().noneMatch(target -> target.modelId().equals(builtin.modelId())),
                "Measured pages must not alias the resident builtin default");
        }

        @Override
        public void close() {
            remote.forEach(content -> content.representation().close());
            local.forEach(content -> content.representation().close());
            builtin.representation().close();
            store.close();
        }
    }

    private static final class Fixture
        implements ClientDemandCoordinator.Resources, ClientDemandCoordinator.Selections, AutoCloseable {
        final Input input;
        final Path root;
        final Path bakeRoot;
        final Path expressionRoot;
        final com.elfmcys.ysm.model.storage.DerivedStorageWork storageWork =
            new com.elfmcys.ysm.model.storage.DerivedStorageWork();
        final AtomicLong clock = new AtomicLong();
        final AtomicLong acquisitionCalls = new AtomicLong();
        final Queue<Runnable> host = new ConcurrentLinkedQueue<>();
        final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
        final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        final ClientCatalogManager catalogs;
        final ClientModelRenderTargetManager manager;
        final ClientDemandCoordinator coordinator;
        final List<Card> cards = new ArrayList<>();
        ClientDemandCoordinator.PageScope page;
        Card card;

        Fixture(Input input, Path root, boolean useRemote) throws Exception {
            this.input = input;
            this.root = root;
            bakeRoot = Files.createDirectories(root.resolve("geometry"));
            expressionRoot = Files.createDirectories(root.resolve("expression"));
            var records = new LinkedHashMap<Hash256, CatalogRecord>();
            for (int index = 0; index < 3; index++) {
                ModelContent content = useRemote ? input.remote.get(index) : input.local.get(index);
                var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("page-" + index));
                records.put(content.modelId(),
                    new CatalogRecord(location, new CatalogContentBinding(content.modelId(), content)));
            }
            var builtinRecord = new CatalogRecord(input.builtin.location(),
                new CatalogContentBinding(input.builtin.modelId(), input.builtin));
            records.put(input.builtin.modelId(), builtinRecord);
            var snapshot = new CatalogSnapshot(records, List.of(), ModelScanReport.empty());
            // Discovery has no role in this resource-admission probe; retain the actual immutable catalog projection.
            catalogs = (ClientCatalogManager)UnsafeUtil.getUnsafe().allocateInstance(ClientCatalogManager.class);
            setField(ClientCatalogManager.class, catalogs, "catalog", new ClientCatalogSnapshot(snapshot));
            var modules = new RenderTargetExpressionModuleFactory(
                new ExpressionArtifactStore(expressionRoot, new AtomicSharedCache(expressionRoot, storageWork)),
                ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics());
            var animations = new DefaultAnimationRuntime();
            var loader =
                new ModelRenderTargetLoader(new BakedModelCache(bakeRoot, new AtomicSharedCache(bakeRoot, storageWork)),
                    modules, scheduler, animations);
            manager = new ClientModelRenderTargetManager(catalogs, loader, animations, scheduler, timer, host::add);
            var textureManager = (TextureManager)UnsafeUtil.getUnsafe().allocateInstance(TextureManager.class);
            var cache = new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, failure -> {
                throw new IllegalStateException(failure);
            }, prepared -> {
                prepared.get();
                prepared.close();
                return new HostTexturePublisher.PublishedTextureBinding(new ResourceLocation("ysm", "headless"),
                    textureManager, List.of());
            }, () -> 4, () -> 0);
            var cacheField = ClientModelRenderTargetManager.class.getDeclaredField("renderTargets");
            cacheField.setAccessible(true);
            ((ModelRenderTargetCache)cacheField.get(manager)).close();
            cacheField.set(manager, cache);
            var builtinSnapshot =
                new CatalogSnapshot(Map.of(input.builtin.modelId(), builtinRecord), List.of(), ModelScanReport.empty());
            var required = manager.startRequired(CompletableFuture.completedFuture(
                new ClientCatalogManager.LocalCatalogState(CatalogIndexSnapshot.empty(), builtinSnapshot)));
            awaitRequired(required);
            require(
                catalogs.snapshot().find(input.targets.get(2).modelId()).orElseThrow().hasLocalSource() != useRemote,
                "The fixture selected the wrong manager source branch");
            coordinator = new ClientDemandCoordinator(clock::get, this, this, () -> true);
        }

        void awaitRequired(CompletableFuture<Void> required) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!required.isDone()) {
                managerTick();
                require(System.nanoTime() < deadline, "Required builtin preparation exceeded 60 seconds");
                Thread.sleep(1);
            }
            required.join();
        }

        void managerTick() {
            for (Runnable action; (action = host.poll()) != null;)
                action.run();
            manager.tick();
        }

        void tick() {
            managerTick();
            coordinator.tick();
            for (var receiver : cards) {
                if (receiver.scope != null && receiver.scope.failure() != null)
                    throw new IllegalStateException("Card preparation failed", receiver.scope.failure());
            }
        }

        void await(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!done.getAsBoolean()) {
                tick();
                require(System.nanoTime() < deadline, "Card preparation exceeded 60 seconds");
                Thread.sleep(1);
            }
            managerTick();
        }

        void addCard(int index) {
            card = new Card(this, input.targets.get(index));
            cards.add(card);
            page.card(card, card.target);
        }

        ResourceRequest request(ClientDemandCoordinator.Target target) {
            return manager.request(target.modelId(), "player", target.texture(), ModelResourcePurpose.GUI, null);
        }

        Sample record(String source, String phase, CheckedAction action) throws Exception {
            var file = root.resolve(phase + ".jfr");
            var acquisitionsBefore = acquisitionCalls.get();
            try (var recording = new Recording()) {
                recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
                recording.start();
                action.run();
                recording.stop();
                recording.dump(file);
            }
            var roots = Map.of("local", input.localRoot, "remote", input.remoteRoot, "bakeGeometry", bakeRoot,
                "expression", expressionRoot);
            var groups = new LinkedHashMap<ReadKey, ReadGroup>();
            for (var event : RecordingFile.readAllEvents(file)) {
                if (!event.getEventType().getName().equals("jdk.FileRead"))
                    continue;
                var path = recordedPath(event.getString("path"));
                var kind = roots.entrySet().stream().filter(entry -> path.startsWith(entry.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
                if (kind == null)
                    continue;
                var stack = event.getStackTrace() == null ? List.<String>of()
                    : event.getStackTrace().getFrames().stream()
                        .map(frame -> frame.getMethod().getType().getName() + "." + frame.getMethod().getName())
                        .toList();
                var key = new ReadKey(kind, path.toString(), stack);
                var group = groups.computeIfAbsent(key, ignored -> new ReadGroup(kind, path.toString(), stack));
                group.calls++;
                group.bytes += Math.max(0, event.getLong("bytesRead"));
            }
            var sample = new Sample(source, phase, file.toString(), acquisitionCalls.get() - acquisitionsBefore,
                List.copyOf(groups.values()));
            System.out.println("DEMAND_DISK_SAMPLE " + source + " " + phase + " calls=" + sample.totalCalls());
            return sample;
        }

        public ModelContent content(Hash256 id) {
            return catalogs.snapshot().find(id).map(entry -> entry.content()).orElse(null);
        }

        public ResourceRequest resourceRequest(Hash256 id, String target, String texture, ModelResourcePurpose purpose,
            Class<?> subject) {
            return manager.request(id, target, texture, purpose, subject);
        }

        public ResourceRequest defaultResourceRequest(String target, ModelResourcePurpose purpose, Class<?> subject) {
            throw new AssertionError("The measured pages must not use resident default content");
        }

        public boolean permitsAcquisition(ResourceRequest request) {
            return manager.permitsAcquisition(request);
        }

        public Optional<ResourceLease> findReady(ResourceRequest request) {
            return manager.findReady(request);
        }

        public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request,
            WorkPriority priority) {
            acquisitionCalls.incrementAndGet();
            return manager.getOrStartCached(request, priority);
        }

        public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request,
            WorkPriority priority) {
            acquisitionCalls.incrementAndGet();
            return manager.getOrStartOffline(request, priority);
        }

        public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
            acquisitionCalls.incrementAndGet();
            return manager.getOrStart(request, priority);
        }

        public void reportActiveModelUse(ResourceRequest request) {
            manager.reportActiveUse(request);
        }

        public void reportActiveModelFailure(ModelContent content, boolean fallback) {
            manager.reportActiveFailure(content, fallback);
        }

        public ClientDemandCoordinator.SelectionContext context() {
            return new ClientDemandCoordinator.SelectionContext(this, false, true);
        }

        public ClientDemandCoordinator.Target authority() {
            return new ClientDemandCoordinator.Target(null, "");
        }

        public boolean installed(ClientDemandCoordinator.Target target) {
            return false;
        }

        public void send(ClientDemandCoordinator.Target target, long id) {
            throw new AssertionError("Automatic cards must not select");
        }

        public void reportFailure(Throwable failure) {
            throw new IllegalStateException(failure);
        }

        @Override
        public void close() throws Exception {
            coordinator.close();
            cards.forEach(Card::deactivate);
            manager.close();
            scheduler.shutdown();
            scheduler.awaitTermination();
            timer.shutdown();
            require(timer.awaitTermination(10, TimeUnit.SECONDS), "Notification timer did not retire");
            storageWork.awaitIdle();
            storageWork.close();
        }
    }

    private static final class Card
        implements ClientDemandCoordinator.CardDisplay, ClientDemandCoordinator.Installation {
        final Fixture fixture;
        final ClientDemandCoordinator.Target target;
        ClientDemandCoordinator.EntityScope scope;
        ModelResourceDemand.Mode mode;
        ResourceLease lease;

        Card(Fixture fixture, ClientDemandCoordinator.Target target) {
            this.fixture = fixture;
            this.target = target;
        }

        public void activate(ModelResourceDemand.Mode allowed) {
            mode = allowed;
            if (scope == null || scope.isClosed())
                scope = fixture.coordinator.register(this);
        }

        public void deactivate() {
            if (scope != null)
                scope.close();
            if (lease != null) {
                lease.close();
                lease = null;
            }
        }

        public ClientDemandCoordinator.Assignment assignment() {
            return new ClientDemandCoordinator.Assignment(fixture.request(target), null, mode, true);
        }

        public boolean hasInstallation() {
            return lease != null;
        }

        public boolean matches(ResourceRequest request, boolean full) {
            return lease != null && !full;
        }

        public Throwable failure(ResourceRequest request) {
            return null;
        }

        public ClientDemandCoordinator.InstallationResult install(ResourceRequest request, ResourceLease acquired,
            boolean fallback, boolean full) {
            require(!full && !fallback && acquired.poll() instanceof AcquireResult.Ready, "Unexpected card lease");
            lease = acquired;
            return ClientDemandCoordinator.InstallationResult.INSTALLED;
        }
    }

    private record ReadKey(String kind, String path, List<String> stack) {
    }

    private static final class ReadGroup {
        final String kind;
        final String path;
        final List<String> stack;
        long calls;
        long bytes;

        ReadGroup(String kind, String path, List<String> stack) {
            this.kind = kind;
            this.path = path;
            this.stack = stack;
        }
    }

    private record Sample(String source, String phase, String recording, long acquisitionCalls,
        List<ReadGroup> groups) {
        long totalCalls() {
            return groups.stream().mapToLong(group -> group.calls).sum();
        }

        long calls(String kind) {
            return groups.stream().filter(group -> group.kind.equals(kind)).mapToLong(group -> group.calls).sum();
        }

        long bytes(String kind) {
            return groups.stream().filter(group -> group.kind.equals(kind)).mapToLong(group -> group.bytes).sum();
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static Directory resourceDirectory(String path) throws Exception {
        return new Directory(
            Path.of(Objects.requireNonNull(DemandDiskReadVerification.class.getResource(path)).toURI()));
    }

    private static Path recordedPath(String value) {
        // Windows FileRead events can expose the extended native path used for long cache file names.
        if (value.startsWith("\\\\?\\UNC\\"))
            value = "\\\\" + value.substring(8);
        else if (value.startsWith("\\\\?\\"))
            value = value.substring(4);
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }
}
