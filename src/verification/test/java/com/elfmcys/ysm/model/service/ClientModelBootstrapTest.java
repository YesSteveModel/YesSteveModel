package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.buffer.SharedBuffer;
import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.ModelSystem;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelMaterializer;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.model.resource.client.RenderTargetResources;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetRepository;
import com.elfmcys.ysm.model.resource.client.render.BakedModelCache;
import com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetLoader;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.model.storage.PlayerPreferences.LocalSelection;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.electronwill.nightconfig.core.CommentedConfig;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.player.AbstractClientPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientModelBootstrapTest {
    @TempDir
    static Path fixtureTemp;

    @TempDir
    Path temp;

    private static Path defaultContainer;
    private static ManagedContainer defaultFixture;
    private static BuiltinModelIndex defaultContract;

    @BeforeAll
    static void createDefaultFixture() throws Exception {
        var manifest = ClientModelBootstrapTest.class.getResource("/assets/ysm/builtin/default/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var directory = new Directory(source)) {
            defaultContainer =
                ModelParser.parseBuiltinDefault(directory, Files.createDirectories(fixtureTemp.resolve("model")));
        }
        defaultFixture = ManagedContainer.openResidentDefault(Files.readAllBytes(defaultContainer), defaultLocation());
        var materialized = BuiltinModelMaterializer.materialize(defaultFixture);
        defaultContract = BuiltinModelIndex.of(Map.of(new ModelPath("default"), materialized.modelHash()),
            materialized.animationHashes(), Map.of());
    }

    @AfterEach
    void clearServiceReference() {
        var current = ClientModelService.current().orElse(null);
        setInstance(null);
        if (current != null) {
            current.close();
        }
    }

    @Test
    void explicitlyInjectedSystemsKeepIndependentDirectoryLayouts() throws Exception {
        try (var first = BootstrapFixture.open(temp.resolve("first"), Stage.SUCCESS);
            var second = BootstrapFixture.open(temp.resolve("second"), Stage.SUCCESS)) {
            assertNotSame(first.system.paths(), second.system.paths());
            assertEquals(temp.resolve("first/ysm/cache"), first.system.storage().local().root());
            assertEquals(temp.resolve("second/ysm/cache"), second.system.storage().local().root());
            assertEquals(temp.resolve("first/ysm/models"), first.system.paths().modelsRoot());
            assertEquals(temp.resolve("second/ysm/models"), second.system.paths().modelsRoot());
        }
    }

    @Test
    void completeRequiredPreparationPrecedesEveryExternalServiceReference() throws Exception {
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            var requiredComplete = new AtomicBoolean();
            var ownerAttached = new AtomicBoolean();
            var result = ClientModelService.publishRequired(() -> fixture.service, service -> {
                assertExternalReferencesEmpty();
                fixture.required.join();
                assertSame(fixture.manager.defaultRenderTarget(), service.defaultRenderTarget());
                assertTrue(service.catalog().models().isEmpty());
                assertTrue(service.catalog().find(PublishedModelId.INTRINSIC_DEFAULT).isPresent());
                requiredComplete.set(true);
            }, service -> {
                assertExternalReferencesEmpty();
                assertTrue(requiredComplete.get());
                fixture.system.attachClientRuntime(service);
                ownerAttached.set(true);
            }, service -> {
                assertExternalReferencesEmpty();
                assertTrue(requiredComplete.get());
                assertTrue(ownerAttached.get());
                setInstance(service);
            }, () -> {
                assertTrue(requiredComplete.get());
                assertSame(fixture.service, ClientModelService.instance());
                fixture.system.catalog().activate();
            });

            assertSame(fixture.service, result);
            assertSame(result, field(fixture.system, "clientRuntime"));
            assertSame(result, ClientModelService.current().orElseThrow());
            assertSame(result, ClientModelService.instance());
            assertSame(fixture.manager.defaultRenderTarget(), result.defaultRenderTarget());
            assertEquals(fixture.requiredTargetCount, fixture.specializations.size());
            assertTrue(
                fixture.specializations.stream().allMatch(value -> value.bindingPlan().subjectAccessor().isPresent()));
            int prepared = fixture.specializations.size();
            var counts = fixture.metrics.snapshot();
            assertEquals(fixture.requiredSetCount,
                counts.stageCalls(dev.semaweave.compilation.EngineMetrics.Stage.SEMANTIC_ANALYSIS));
            assertEquals(fixture.requiredSetCount,
                counts.stageCalls(dev.semaweave.compilation.EngineMetrics.Stage.BACKEND_COMPILATION));
            assertEquals(fixture.requiredSetCount,
                counts.stageCalls(dev.semaweave.compilation.EngineMetrics.Stage.MODULE_LOAD));
            assertEquals(fixture.requiredSetCount, counts.portableProgramDecodes());
            var resident = (ManagedContainer)fixture.service.catalog().catalog().intrinsicDefault().binding().content();
            setField(resident, "chunks", new FailingChunks(AssetLoadException.access("post-startup source blocked")));
            for (var target : defaultFixture.view().getRenderTargets()) {
                for (var texture : target.getTextureNames()) {
                    for (var purpose : com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.values()) {
                        var request = fixture.manager.request(PublishedModelId.INTRINSIC_DEFAULT, target.id(), texture,
                            purpose, null);
                        assertEquals(com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.FULL,
                            request.purpose());
                        assertSame(fixture.specializations.get(0), request.expressionBinding());
                        try (var lease = fixture.manager.getOrStart(request)) {
                            assertInstanceOf(com.elfmcys.ysm.model.resource.client.AcquireResult.Ready.class,
                                lease.poll());
                        }
                        try (var lease = fixture.manager.getOrStartOffline(request)) {
                            assertTrue(lease.isCurrent(request));
                        }
                        try (var lease = fixture.manager.getOrStartCached(request)) {
                            assertTrue(lease.isCurrent(request));
                        }
                    }
                }
            }
            assertTrue(
                fixture.manager.findReady(fixture.manager.request(resident.modelId(), "player", "default")).isEmpty());
            assertEquals(prepared, fixture.specializations.size());
            assertEquals(counts.stageCalls(dev.semaweave.compilation.EngineMetrics.Stage.MODULE_LOAD),
                fixture.metrics.snapshot().stageCalls(dev.semaweave.compilation.EngineMetrics.Stage.MODULE_LOAD));
            fixture.close();
        }
    }

    @Test
    void realDefaultSourceAlsoEntersTheOrdinaryCatalogLifecycle() throws Exception {
        var source = Path.of(getClass().getResource("/assets/ysm/builtin/default/ysm.json").toURI()).getParent();
        var builtinRoot = Files.createDirectories(temp.resolve("builtin"));
        org.apache.commons.io.FileUtils.copyDirectory(source.toFile(), builtinRoot.resolve("default").toFile());
        try (var storage = BootstrapFixture.storage(temp.resolve("ysm/cache"));
            var builtins = BuiltinModelCatalog.open(defaultContract, builtinRoot);
            var catalog = new ReloadableModelCatalog(storage,
                List.of(new ModelCatalogSource(CatalogRootKind.BUILTIN, builtinRoot, false)), builtins)) {
            var required = catalog.current().intrinsicDefault();
            assertTrue(catalog.current().byModelId().isEmpty());
            catalog.activate();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (catalog.busy() && System.nanoTime() < deadline) {
                catalog.tick();
                Thread.sleep(1);
            }
            assertFalse(catalog.busy());
            assertEquals(0, catalog.current().report().errorCount());
            assertEquals(1, catalog.current().byModelId().size());
            var ordinary = catalog.current().byModelId().get(defaultFixture.modelId());
            assertEquals("builtin:default", ordinary.location().value());
            assertNotSame(required.binding().content(), ordinary.binding().content());
            assertSame(required, catalog.current().intrinsicDefault());
            var reload = catalog.reload();
            while (!reload.isDone() && System.nanoTime() < deadline) {
                catalog.tick();
                Thread.sleep(1);
            }
            assertTrue(reload.isDone());
            assertSame(required, catalog.current().intrinsicDefault());
            assertEquals(1, catalog.current().byModelId().size());
        }
    }

    @Test
    void startupInputsUseLocalPublicationBufferedPreferencesAndFullBackgroundPlayerResources() throws Exception {
        if (ClientConfig.MODEL_RETENTION_COUNT == null)
            ClientConfig.init().setConfig(CommentedConfig.inMemory());
        var source = Path.of(getClass().getResource("/assets/ysm/builtin/default/ysm.json").toURI()).getParent();
        var builtinRoot = Files.createDirectories(temp.resolve("builtin"));
        org.apache.commons.io.FileUtils.copyDirectory(source.toFile(), builtinRoot.resolve("default").toFile());
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS, ignored -> {
        }, new ModelCatalogSource(CatalogRootKind.BUILTIN, builtinRoot, false))) {
            fixture.required.join();
            var local = fixture.system.catalog();
            var preferences = fixture.system.storage().preferences();
            var projection = (ClientCatalogManager)field(fixture.service, "catalogManager");
            var inputs = new StartupModelWarmup.ClientInputs(local, preferences, projection, fixture.manager);
            var notifications = new AtomicInteger();
            try (var subscription = inputs.subscribe(notifications::incrementAndGet)) {
                assertEquals(1, notifications.get());
                projection.beginRemote();
                local.activate();
                fixture.finishCatalog();
                assertEquals(0, inputs.catalog().report().errorCount(), inputs.catalog().report().errors().toString());
                var modelId = inputs.catalog().resolve(CatalogLocation.BUILTIN_DEFAULT).orElseThrow();
                assertEquals(defaultFixture.modelId(), modelId);
                var identity = PublishedModelId.model(modelId);
                var ordinary = inputs.catalog().byModelId().get(modelId);
                assertTrue(notifications.get() > 1);
                assertEquals(CatalogLocation.BUILTIN_DEFAULT, ordinary.location());
                assertTrue(projection.snapshot().find(identity).isEmpty());
                assertNull(inputs.acquire(new LocalSelection(identity, "")));
                assertEquals(0, fixture.manager.loadingCount());

                var player = UUID.fromString("4e769875-2df4-40aa-a938-48505b09c6ef");
                var saved = new LocalSelection(identity, "saved-texture-intent");
                var write = preferences.writeSelection(player, saved);
                assertFalse(write.result().toCompletableFuture().isDone());
                try (var query = inputs.readSelection(player)) {
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (query.poll() == null && System.nanoTime() < deadline) {
                        fixture.system.storage().tick(System.nanoTime());
                        Thread.sleep(1);
                    }
                    var completion = Objects.requireNonNull(query.poll(), "Preference read did not finish");
                    assertFalse(completion.closed());
                    assertNull(completion.failure());
                    assertEquals(List.of(saved), completion.result().rows());
                }

                var denied = new PublicationEntry(ordinary.binding().content().representation().identity(),
                    ordinary.entry().path(), CatalogAccess.PUBLIC, false);
                setField(projection, "catalog", new ClientCatalogSnapshot(inputs.catalog(), List.of(),
                    new RemotePublicationSnapshot(List.of(denied), Set.of(), List.of(), Map.of())));
                assertTrue(projection.snapshot().find(identity).orElseThrow().hasLocalSource());
                assertNull(inputs.acquire(saved));
                assertEquals(0, fixture.manager.loadingCount());
                projection.endRemote();

                var expected = fixture.manager.request(identity, RenderTargetIds.PLAYER, saved.texture(),
                    ModelResourcePurpose.FULL, AbstractClientPlayer.class);
                assertTrue(fixture.manager.findReady(expected).isEmpty());
                try (var lease = Objects.requireNonNull(inputs.acquire(saved))) {
                    var actual = (ResourceRequest)field(lease, "originalRequest");
                    assertEquals(expected, actual);
                    assertEquals(AbstractClientPlayer.class,
                        actual.expressionBinding().bindingPlan().rootSubjectType().orElseThrow());
                    var flight = field(field(lease, "state"), "flight");
                    assertEquals(WorkPriority.BACKGROUND,
                        ((ClientResourceScheduler.Dispatch)field(flight, "dispatch")).priority());
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (lease.poll() instanceof AcquireResult.Pending && System.nanoTime() < deadline) {
                        fixture.manager.tick();
                        Thread.sleep(1);
                    }
                    var ready = assertInstanceOf(AcquireResult.Ready.class, lease.poll());
                    assertEquals(identity, ready.target().publishedId());
                    assertNotSame(fixture.manager.defaultRenderTarget(), ready.target());
                }
                fixture.manager.tick();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Stage.class, names = "SUCCESS", mode = EnumSource.Mode.EXCLUDE)
    void eachRequiredFailurePropagatesExactlyWithoutPublication(Stage stage) throws Exception {
        try (var fixture = BootstrapFixture.open(temp, stage)) {
            assertExternalReferencesEmpty();
            var stageCause = terminalCause(fixture.required);
            assertStageCause(stage, stageCause);
            if (stage == Stage.ANIMATIONS) {
                var player = defaultFixture.view().requireRenderTarget(RenderTargetIds.PLAYER).descriptor();
                assertNull(fixture.animations.source(DefaultAnimationKey.domain(player, "main")));
                assertSame(fixture.conflictingAnimation,
                    fixture.animations.source(DefaultAnimationKey.domain(player, "fp_arm")));
            }
            var published = new AtomicBoolean();
            var activated = new AtomicBoolean();

            var thrown = assertThrows(Throwable.class,
                () -> ClientModelService.publishRequired(() -> fixture.service, ignored -> fixture.required.join(),
                    fixture.system::attachClientRuntime, ignored -> published.set(true), () -> activated.set(true)));

            assertSame(stageCause, thrown);
            assertFalse(published.get());
            assertFalse(activated.get());
            assertExternalReferencesEmpty();
            assertTrue(fixture.workers.isTerminated());
            assertThrows(IllegalStateException.class, fixture.manager::defaultRenderTarget);
        }
    }

    @Test
    void requiredBootstrapCompletesWhileTheBackgroundQueueIsBlocked() throws Exception {
        var backgroundStarted = new CompletableFuture<Void>();
        var releaseBackground = new CountDownLatch(1);
        var queuedBackgroundRan = new AtomicBoolean();
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS, scheduler -> {
            scheduler.backgroundExecutor().execute(() -> {
                backgroundStarted.complete(null);
                try {
                    releaseBackground.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            });
            scheduler.backgroundExecutor().execute(() -> queuedBackgroundRan.set(true));
        })) {
            try {
                backgroundStarted.get(5, TimeUnit.SECONDS);
                fixture.required.get(30, TimeUnit.SECONDS);
                assertFalse(queuedBackgroundRan.get());
                assertEquals(fixture.requiredTargetCount, fixture.specializations.size());
                assertSame(fixture.manager.defaultRenderTarget(), fixture.service.defaultRenderTarget());
                assertExternalReferencesEmpty();
            } finally {
                releaseBackground.countDown();
            }
        } finally {
            releaseBackground.countDown();
        }
    }

    @Test
    void closingTheServicePreservesInterruptionAfterBothExecutionDomainsStop() throws Exception {
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            fixture.required.join();
            Thread.currentThread().interrupt();
            try {
                fixture.service.close();
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(fixture.workers.isTerminated());
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> fixture.scheduler.backgroundExecutor().execute(() -> {
                    }));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void activationFailureKeepsThePublishedRequiredServiceAndRepeatedStartDoesNotRetry() throws Exception {
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            var attempts = new AtomicInteger();
            setField(fixture.system.catalog(), "coordinatorThreads", (ThreadFactory)body -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("coordinator startup failed");
            });
            var service = ClientModelService.publishRequired(() -> fixture.service, ignored -> fixture.required.join(),
                fixture.system::attachClientRuntime, ClientModelBootstrapTest::setInstance,
                () -> fixture.system.catalog().activate());

            assertSame(service, ClientModelService.instance());
            assertSame(fixture.manager.defaultRenderTarget(), service.defaultRenderTarget());
            assertFalse(fixture.system.catalog().busy());
            assertEquals(1, attempts.get());
            assertSame(service, ClientModelService.start(fixture.system, HostAvailability.all()));
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void everyConnectionAttemptsReloadWithoutWaitingAndDisconnectPreservesProcessWork() throws Exception {
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            fixture.required.join();
            fixture.system.catalog().activate();
            fixture.finishCatalog();
            var started = new CompletableFuture<Void>();
            var release = new CountDownLatch(1);
            var scans = new AtomicInteger();
            var interrupted = new AtomicBoolean();
            setField(fixture.system.catalog(), "coordinatorThreads", (ThreadFactory)body -> new Thread(() -> {
                scans.incrementAndGet();
                started.complete(null);
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                body.run();
            }));
            try {
                var first = CompletableFuture.supplyAsync(fixture.service::beginConnection).get(5, TimeUnit.SECONDS);
                started.get(5, TimeUnit.SECONDS);
                assertTrue(fixture.system.catalog().busy());
                fixture.service.disconnect(first);
                assertFalse(interrupted.get());
                assertTrue(fixture.system.catalog().busy());
                var reconnect =
                    CompletableFuture.supplyAsync(fixture.service::beginConnection).get(5, TimeUnit.SECONDS);
                fixture.service.disconnect(reconnect);
                assertEquals(1, scans.get());
                release.countDown();
                fixture.finishCatalog();
                assertEquals(1, scans.get());
                var nextWorld = fixture.service.beginConnection();
                fixture.finishCatalog();
                assertEquals(2, scans.get());
                fixture.service.disconnect(nextWorld);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void realDisconnectFollowsInlineAndGuiReadersAndKeepsRequiredResources() throws Exception {
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            fixture.required.join();
            setInstance(fixture.service);
            var connection =
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND);
            var session = new com.elfmcys.ysm.model.session.client.ClientModelSession(
                com.elfmcys.ysm.network.session.SessionMode.AUTO, true,
                com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot.empty());
            session.onServerHello(com.elfmcys.ysm.network.protocol.ProtocolVersion.CURRENT, false);
            session.publishFull(new com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot(List.of(),
                java.util.Set.of(), List.of(), Map.of()));
            var runtime = com.elfmcys.ysm.network.forge.ClientSessionRuntime.class;
            var current = runtime.getDeclaredField("current");
            current.setAccessible(true);
            var type = Class.forName(runtime.getName() + "$ConnectionState");
            var constructor = type.getDeclaredConstructor(net.minecraft.network.Connection.class,
                com.elfmcys.ysm.model.session.client.ClientModelSession.class, Consumer.class, Object.class);
            constructor.setAccessible(true);
            current.set(null, constructor.newInstance(connection, session, (Consumer<String>)ignored -> {
            }, fixture.service.beginConnection()));
            try {
                com.elfmcys.ysm.client.entity.FrameContractReaders.verifyDisconnectAfterInlineAndGuiReaders(
                    () -> com.elfmcys.ysm.network.forge.ClientSessionRuntime.disconnect(connection));
                assertEquals(com.elfmcys.ysm.model.session.client.ClientModelSession.State.CLOSED, session.state());
                assertSame(fixture.manager.defaultRenderTarget(), fixture.service.defaultRenderTarget());
                assertTrue(fixture.service.catalog().find(PublishedModelId.INTRINSIC_DEFAULT).isPresent());
            } finally {
                var owner = current.get(null);
                current.set(null, null);
                if (owner instanceof AutoCloseable closeable)
                    closeable.close();
            }
        }
    }

    @Test
    void controlledSystemCloseSealsBeforeWaitingForCatalogOutsideItsMonitor() throws Exception {
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            fixture.required.join();
            fixture.service.close();
            var system = fixture.system;
            var catalog = system.catalog();
            var runtime = system.serverResources();
            var started = new CompletableFuture<Void>();
            var interrupted = new CompletableFuture<Void>();
            var release = new CountDownLatch(1);
            setField(catalog, "coordinatorThreads", (ThreadFactory)body -> new Thread(() -> {
                started.complete(null);
                var restoreInterrupt = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException failure) {
                        restoreInterrupt = true;
                        interrupted.complete(null);
                    }
                }
                if (restoreInterrupt)
                    Thread.currentThread().interrupt();
                body.run();
            }));
            catalog.activate();
            started.get(5, TimeUnit.SECONDS);
            var closing = CompletableFuture.runAsync(system::close);
            try {
                interrupted.get(5, TimeUnit.SECONDS);
                CompletableFuture.runAsync(() -> {
                    assertThrows(IllegalStateException.class, system::catalog);
                }).get(5, TimeUnit.SECONDS);
                assertFalse((boolean)field(runtime, "closed"));
                assertFalse(closing.isDone());
            } finally {
                release.countDown();
                closing.get(5, TimeUnit.SECONDS);
            }
            assertTrue((boolean)field(runtime, "closed"));
        }
    }

    @Test
    void ownershipRejectionClosesThePrivateCandidateWithoutPublicationOrActivation() throws Exception {
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            AutoCloseable existing = () -> {
            };
            fixture.system.attachClientRuntime(existing);
            var published = new AtomicBoolean();
            var activated = new AtomicBoolean();

            assertThrows(IllegalStateException.class,
                () -> ClientModelService.publishRequired(() -> fixture.service, ignored -> fixture.required.join(),
                    fixture.system::attachClientRuntime, ignored -> published.set(true), () -> activated.set(true)));

            assertSame(existing, field(fixture.system, "clientRuntime"));
            assertFalse(published.get());
            assertFalse(activated.get());
            assertExternalReferencesEmpty();
            assertTrue(fixture.workers.isTerminated());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> fixture.scheduler.backgroundExecutor().execute(() -> {
                }));
        }
    }

    @Test
    void controlledSystemCloseRetainsTheConsumerUntilClientWorkHasTerminated() throws Exception {
        var release = new CountDownLatch(1);
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            fixture.required.join();
            var system = fixture.system;
            var runtime = system.serverResources();
            var consumers = system.storage().scopes().get(0).consumers();
            consumers.register();
            system.attachClientRuntime(fixture.service);
            setInstance(fixture.service);
            var started = new CompletableFuture<Void>();
            fixture.scheduler.backgroundExecutor().execute(() -> {
                started.complete(null);
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            });
            started.get(5, TimeUnit.SECONDS);
            var closing = CompletableFuture.runAsync(system::close);
            try {
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ClientModelService.current().isPresent() && System.nanoTime() < deadline)
                    Thread.sleep(1);
                assertExternalReferencesEmpty();
                assertThrows(IllegalStateException.class, () -> system.attachClientRuntime(() -> {
                }));
                assertTrue(consumers.registered());
                assertFalse((boolean)field(runtime, "closed"));
                assertFalse(closing.isDone());
            } finally {
                release.countDown();
                closing.get(5, TimeUnit.SECONDS);
            }
            assertTrue(fixture.workers.isTerminated());
            assertTrue((boolean)field(runtime, "closed"));
            assertFalse(consumers.registered());
        } finally {
            release.countDown();
        }
    }

    @Test
    void clientCloseFailurePreservesTheFirstCauseAndStillClosesLaterOwners() throws Exception {
        try (var fixture = BootstrapFixture.open(temp, Stage.SUCCESS)) {
            fixture.required.join();
            fixture.service.close();
            var runtime = fixture.system.serverResources();
            var consumers = fixture.system.storage().scopes().get(0).consumers();
            consumers.register();
            var failure = new IOException("client close failed");
            fixture.system.attachClientRuntime(() -> {
                throw failure;
            });
            var handle = fixture.builtins.defaultContent();
            setField(fixture.builtins, "defaultHandle", null);
            try {
                assertSame(failure, assertThrows(IOException.class, fixture.system::close));
                assertEquals(1, failure.getSuppressed().length);
                assertInstanceOf(NullPointerException.class, failure.getSuppressed()[0]);
                assertTrue((boolean)field(runtime, "closed"));
                assertFalse(consumers.registered());
                assertNull(field(fixture.system, "clientRuntime"));
            } finally {
                setField(fixture.builtins, "defaultHandle", handle);
                handle.representation().close();
            }
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void assertStageCause(Stage stage, Throwable cause) {
        switch (stage) {
            case CHUNK_READ -> {
                assertTrue(cause instanceof AssetLoadException);
                assertEquals("required chunk read failed", cause.getMessage());
            }
            case CONSUMER_BUILD -> {
                assertTrue(cause instanceof AssetLoadException);
                assertEquals("required consumer build failed", cause.getMessage());
            }
            case DEFAULT_TARGET -> {
                assertTrue(cause instanceof IllegalStateException);
                assertEquals("Builtin default model contains no player render target", cause.getMessage());
            }
            case ANIMATIONS -> {
                assertTrue(cause instanceof IllegalStateException);
                assertTrue(cause.getMessage().startsWith("Conflicting default source domain:"));
            }
            case SUCCESS -> throw new AssertionError("Success has no failure cause");
        }
    }

    private static Throwable terminalCause(CompletableFuture<Void> terminal) {
        var error = assertThrows(CompletionException.class, terminal::join);
        return unwrap(error);
    }

    private static void assertExternalReferencesEmpty() {
        assertTrue(ClientModelService.current().isEmpty());
        assertThrows(IllegalStateException.class, ClientModelService::instance);
    }

    private static Throwable unwrap(Throwable error) {
        var current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static CatalogLocation defaultLocation() {
        return new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default"));
    }

    private static void setInstance(ClientModelService service) {
        setField(ClientModelService.class, null, "INSTANCE", service);
    }

    private static void setField(Object owner, String name, Object value) {
        setField(owner.getClass(), owner, name, value);
    }

    private static void setField(Class<?> type, Object owner, String name, Object value) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private enum Stage {
        SUCCESS, CHUNK_READ, CONSUMER_BUILD, DEFAULT_TARGET, ANIMATIONS
    }

    private static final class BootstrapFixture implements AutoCloseable {
        private final ScheduledThreadPoolExecutor workers;
        private final ClientResourceScheduler scheduler;
        private final BuiltinModelCatalog builtins;
        private final ModelSystem system;
        private final ClientModelRenderTargetManager manager;
        private final ClientModelService service;
        private final CompletableFuture<Void> required;
        private final List<ModelRenderTarget> targets;
        private final DefaultAnimationRuntime animations;
        private final DefaultAnimationRuntime.Source conflictingAnimation;
        private final int requiredTargetCount;
        private final List<ExpressionBinding> specializations;
        private final ExpressionEngineMetrics metrics;
        private final int requiredSetCount;

        private BootstrapFixture(ScheduledThreadPoolExecutor workers, ClientResourceScheduler scheduler,
            BuiltinModelCatalog builtins, ModelSystem system, ClientModelRenderTargetManager manager,
            ClientModelService service, CompletableFuture<Void> required, List<ModelRenderTarget> targets,
            DefaultAnimationRuntime animations, DefaultAnimationRuntime.Source conflictingAnimation,
            int requiredTargetCount, List<ExpressionBinding> specializations, ExpressionEngineMetrics metrics,
            int requiredSetCount) {
            this.workers = workers;
            this.scheduler = scheduler;
            this.builtins = builtins;
            this.system = system;
            this.manager = manager;
            this.service = service;
            this.required = required;
            this.targets = targets;
            this.animations = animations;
            this.conflictingAnimation = conflictingAnimation;
            this.requiredTargetCount = requiredTargetCount;
            this.specializations = specializations;
            this.metrics = metrics;
            this.requiredSetCount = requiredSetCount;
        }

        private static BootstrapFixture open(Path root, Stage stage) throws Exception {
            return open(root, stage, ignored -> {
            });
        }

        private static BootstrapFixture open(Path root, Stage stage, Consumer<ClientResourceScheduler> beforeRequired,
            ModelCatalogSource... sourceOverrides) throws Exception {
            var handle = ManagedContainer.openResidentDefault(Files.readAllBytes(defaultContainer), defaultLocation());
            var gameCacheRoot = root.resolve("ysm/cache");
            var storage = storage(gameCacheRoot);
            var builtins = builtins(handle);
            var workers = new ScheduledThreadPoolExecutor(1);
            var scheduler = new ClientResourceScheduler(2);
            workers.setRemoveOnCancelPolicy(true);
            ReloadableModelCatalog catalogOwner = null;
            ClientModelService service = null;
            ModelSystem system = null;
            var targets = Collections.synchronizedList(new java.util.ArrayList<ModelRenderTarget>());
            try {
                var sources = sourceOverrides.length == 0
                    ? List.of(new ModelCatalogSource(CatalogRootKind.MODELS, root.resolve("custom"), true),
                        new ModelCatalogSource(CatalogRootKind.MODELS, root.resolve("auth"), true))
                    : List.of(sourceOverrides);
                catalogOwner = new ReloadableModelCatalog(storage, sources, builtins);
                system = system(storage, builtins, catalogOwner);
                var catalogs = new ClientCatalogManager(catalogOwner);
                var animations = new DefaultAnimationRuntime(defaultContract);
                DefaultAnimationRuntime.Source conflictingAnimation = null;
                if (stage == Stage.ANIMATIONS) {
                    var player = handle.view().requireRenderTarget(RenderTargetIds.PLAYER).descriptor();
                    conflictingAnimation = new DefaultAnimationRuntime.Source(new Hash256(new byte[32]), "player",
                        com.elfmcys.ysm.expression.inventory.ExpressionSet.FP_ARM, ModelDataProto.newBuilder().build(),
                        null);
                    animations
                        .publishSources(Map.of(DefaultAnimationKey.domain(player, "fp_arm"), conflictingAnimation));
                }
                var bakedRoot = root.resolve("baked");
                var cache = track(new AtomicSharedCache(bakedRoot));
                var metrics = new ExpressionEngineMetrics();
                var loader =
                    new ModelRenderTargetLoader(new BakedModelCache(bakedRoot, cache),
                        new RenderTargetExpressionModuleFactory(storage.local().expressions(),
                            storage.local().animations(), ExpressionHost.create(HostAvailability.all()), metrics),
                        scheduler, animations);
                var specializations = Collections.synchronizedList(new java.util.ArrayList<ExpressionBinding>());
                installBuildStage(loader, stage, handle.representation().modelId(), targets, specializations);
                if (stage == Stage.CHUNK_READ) {
                    var cause = AssetLoadException.content("required chunk read failed");
                    setField(handle, "chunks", new FailingChunks(cause));
                } else if (stage == Stage.DEFAULT_TARGET) {
                    removePlayerTarget(handle.view());
                }
                var contentStore = new RuntimeContentStore(ignored -> {
                });
                var manager = new ClientModelRenderTargetManager(catalogs, loader, animations, scheduler, workers,
                    Runnable::run, contentStore);
                catalogs.setListener(manager::catalogChanged);
                service = service(system, scheduler, workers, catalogs,
                    new ClientAssetRepository(catalogs, contentStore), manager, root.resolve("remote"));
                beforeRequired.accept(scheduler);
                var required = manager.startRequired(catalogs.initialize());
                var requiredTargetCount =
                    handle.view().getRenderTargets().stream().mapToInt(target -> target.getTextureNames().size()).sum();
                return new BootstrapFixture(workers, scheduler, builtins, system, manager, service, required, targets,
                    animations, conflictingAnimation, requiredTargetCount, specializations, metrics,
                    handle.view().getRenderTargets().stream()
                        .mapToInt(target -> target.descriptor()
                            .kind() == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER ? 2 : 1)
                        .sum());
            } catch (Throwable error) {
                if (service != null) {
                    service.close();
                } else {
                    if (catalogOwner != null) {
                        catalogOwner.close();
                    }
                    workers.shutdown();
                    scheduler.shutdown();
                    scheduler.awaitTermination();
                }
                if (system != null) {
                    system.close();
                } else {
                    builtins.close();
                }
                targets.forEach(ModelRenderTarget::close);
                throw error;
            }
        }

        private static ModelSystem system(ModelStorageInfra storage, BuiltinModelCatalog builtins,
            ReloadableModelCatalog catalog) throws Exception {
            var constructor = ModelSystem.class.getDeclaredConstructor(ModelStorageInfra.class,
                BuiltinModelCatalog.class, ReloadableModelCatalog.class);
            constructor.setAccessible(true);
            return constructor.newInstance(storage, builtins, catalog);
        }

        private void finishCatalog() throws Exception {
            var catalog = system.catalog();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (catalog.busy() && System.nanoTime() < deadline) {
                catalog.tick();
                Thread.sleep(1);
            }
            assertFalse(catalog.busy(), "Catalog scan did not finish");
        }

        private static ModelStorageInfra storage(Path gameCacheRoot) throws Exception {
            var game = gameCacheRoot.toAbsolutePath().normalize().getParent().getParent();
            return ModelStorageInfra
                .open(com.elfmcys.ysm.AssetPaths.localOnly(game, game.resolve("config"), game.resolve("builtin")));
        }

        private static BuiltinModelCatalog builtins(ManagedContainer handle) throws Exception {
            var catalog = (BuiltinModelCatalog)UnsafeUtil.getUnsafe().allocateInstance(BuiltinModelCatalog.class);
            setField(catalog, "contract", defaultContract);
            setField(catalog, "defaultHandle", handle);
            return catalog;
        }

        private static ClientModelService service(ModelSystem system, ClientResourceScheduler scheduler,
            ScheduledThreadPoolExecutor workers, ClientCatalogManager catalogs, ClientAssetRepository assets,
            ClientModelRenderTargetManager manager, Path remoteRoot) throws Exception {
            var constructor = ClientModelService.class.getDeclaredConstructor(ModelSystem.class,
                ClientResourceScheduler.class, ScheduledThreadPoolExecutor.class, ClientCatalogManager.class,
                ClientAssetRepository.class, ClientModelRenderTargetManager.class, Path.class);
            constructor.setAccessible(true);
            return constructor.newInstance(system, scheduler, workers, catalogs, assets, manager, remoteRoot);
        }

        private static void installBuildStage(ModelRenderTargetLoader loader, Stage stage, ModelId modelHash,
            List<ModelRenderTarget> targets, List<ExpressionBinding> specializations) throws Exception {
            var buildStageType = Class.forName(ModelRenderTargetLoader.class.getName() + "$ConsumerStage");
            var buildStage = Proxy.newProxyInstance(buildStageType.getClassLoader(), new Class<?>[] {buildStageType},
                (proxy, method, args) -> {
                    if (!method.getName().equals("build")) {
                        return switch (method.getName()) {
                            case "toString" -> "RequiredBootstrapBuildStage";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new AssertionError(method);
                        };
                    }
                    if (stage == Stage.CONSUMER_BUILD) {
                        throw AssetLoadException.content("required consumer build failed");
                    }
                    var request = args[1];
                    var targetIdMethod = request.getClass().getDeclaredMethod("targetId");
                    targetIdMethod.setAccessible(true);
                    var targetId = (String)targetIdMethod.invoke(request);
                    var specializationMethod = request.getClass().getDeclaredMethod("expressionBinding");
                    specializationMethod.setAccessible(true);
                    specializations.add((ExpressionBinding)specializationMethod.invoke(request));
                    var pin = (AutoCloseable)args[2];
                    var valueMethod = pin.getClass().getDeclaredMethod("value");
                    valueMethod.setAccessible(true);
                    var prepared = valueMethod.invoke(pin);
                    var sourcesMethod = prepared.getClass().getDeclaredMethod("defaultSources");
                    sourcesMethod.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var sources = (Map<String, DefaultAnimationRuntime.Source>)sourcesMethod.invoke(prepared);
                    var store = (AnimationStore)field(field(prepared, "main"), "animationStore");
                    var target = target(modelHash, targetId, (stage == Stage.SUCCESS || stage == Stage.ANIMATIONS)
                        && targetId.equals(RenderTargetIds.PLAYER), store, sources, pin);
                    targets.add(target);
                    return candidate(target);
                });
            setField(loader, "consumerStage", buildStage);
        }

        private static Object candidate(ModelRenderTarget target) throws Exception {
            var candidateType = Class.forName(ModelRenderTargetLoader.class.getName() + "$ModelCandidate");
            var testing = candidateType.getDeclaredMethod("testing", ModelRenderTarget.class);
            testing.setAccessible(true);
            return testing.invoke(null, target);
        }

        private static ModelRenderTarget target(ModelId modelHash, String targetId, boolean player,
            AnimationStore store, Map<String, DefaultAnimationRuntime.Source> sources, AutoCloseable pin)
            throws Exception {
            return new ModelRenderTarget(modelHash, targetId, player ? playerResources() : new EmptyResources(),
                (com.elfmcys.ysm.model.resource.client.CommonAsset)UnsafeUtil.getUnsafe()
                    .allocateInstance(com.elfmcys.ysm.model.resource.client.CommonAsset.class),
                store,
                (com.elfmcys.ysm.format.schema.model.views.ModelInfoView)UnsafeUtil.getUnsafe()
                    .allocateInstance(com.elfmcys.ysm.format.schema.model.views.ModelInfoView.class),
                null, null, pin, sources);
        }

        private static PlayerModelResources playerResources() throws Exception {
            var resources = (PlayerModelResources)UnsafeUtil.getUnsafe().allocateInstance(PlayerModelResources.class);
            setField(resources, "animations", AnimationStore.emptyView());
            setField(resources, "fpArmAnimations", AnimationStore.emptyView());
            setField(resources, "variants", new com.elfmcys.ysm.util.FifoHashMap<>(new String[0],
                new com.elfmcys.ysm.model.resource.client.PlayerModelVariant[0]));
            return resources;
        }

        private static void removePlayerTarget(ModelFileView view) {
            var targets =
                view.getRenderTargets().stream().filter(target -> !target.id().equals(RenderTargetIds.PLAYER)).toList();
            var byId = new LinkedHashMap<String, Object>();
            targets.forEach(target -> byId.put(target.id(), target));
            setField(view, "renderTargets", targets);
            setField(view, "renderTargetsById", Map.copyOf(byId));
        }

        @Override
        public void close() {
            service.close();
            system.close();
            targets.forEach(ModelRenderTarget::close);
            targets.clear();
            assertTrue(workers.isTerminated());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> scheduler.backgroundExecutor().execute(() -> {
                }));
            var released = metrics.snapshot();
            for (var owner : dev.semaweave.compilation.EngineMetrics.Owner.values())
                assertEquals(0, released.active(owner), owner.name());
        }
    }

    private record EmptyResources() implements RenderTargetResources {
    }

    private record FailingChunks(AssetLoadException cause) implements ChunkDataSource {
        @Override
        public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
            throw cause;
        }

        @Override
        public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            throw cause;
        }
    }

}
