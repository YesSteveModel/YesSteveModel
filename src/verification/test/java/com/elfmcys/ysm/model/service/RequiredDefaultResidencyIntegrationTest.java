package com.elfmcys.ysm.model.service;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.api.client.audio.AudioBackend;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.sound.instance.AudioHost;
import com.elfmcys.ysm.client.sound.instance.PlaybackControl;
import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.model.ModelSystem;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelMaterializer;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.audio.ClientAudioRuntime;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetRepository;
import com.elfmcys.ysm.model.resource.client.render.BakedModelCache;
import com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetLoader;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.DerivedStorageWork;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.UnsafeUtil;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketPermission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Permission;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises retained resources with content acquisition denied after required publication. */
@ExtendWith(NativeLibraryExtension.class)
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ysm.requiredResidency", matches = "true")
class RequiredDefaultResidencyIntegrationTest {
    @TempDir
    Path root;

    @Test
    void newIntrinsicInstallAndCompleteSoundConsumptionNeedNoModelIoOrSharedPreparation() throws Exception {
        assertTrue(
            com.elfmcys.ysm.mixin.client.NativeImageAccessor.class
                .isAssignableFrom(com.mojang.blaze3d.platform.NativeImage.class),
            "Run verifyRequiredDefaultResidency for the headless Mixin getters");
        initializeHostTypes();
        if (ClientConfig.MODEL_RETENTION_COUNT == null)
            ClientConfig.init().setConfig(CommentedConfig.inMemory());
        try (var fixture = new Fixture(root)) {
            exerciseResidentConsumers(fixture);
            fixture.close();
            awaitExpressionOwnersReturned(fixture.metrics);
        }
    }

    @SuppressWarnings("removal")
    private void exerciseResidentConsumers(Fixture fixture) throws Exception {
        fixture.manager.startRequired(fixture.catalogs.initialize()).get(60, TimeUnit.SECONDS);
        assertTrue(fixture.resident.residentOnly());
        assertThrows(IllegalStateException.class, fixture.resident::file);
        assertTrue(ClientModelService.current().isEmpty());
        set(ClientModelService.class, null, "INSTANCE", fixture.service);
        fixture.cache.derivedWork().close();
        ((DerivedStorageWork)field(fixture.storage, "derivedWork")).close();
        var before = fixture.metrics.snapshot();
        int builds = fixture.builds.get();
        var target = fixture.manager.defaultRenderTarget();
        var sound = target.assets().sounds().get("tone");
        assertNotNull(sound);
        assertTrue(sound.modelId().isIntrinsicDefault());
        assertTrue(sound.media().chunkInfos().size() > 1, "The consumer must pass the retained first chunk");
        assertEquals(0, fixture.manager.loadingCount());
        var session = fixture.manager.beginSession();
        fixture.manager.bindRemoteFetcher(session, (identity, chunks, receiver) -> {
            fixture.fetches.incrementAndGet();
            throw new AssertionError("Required audio attempted remote acquisition");
        });
        var host = new StreamHost();
        var previous = System.getSecurityManager();
        var denial = new DeniedModelIo(List.of(root, fixture.sourceRoot));
        var request = fixture.manager.request(PublishedModelId.INTRINSIC_DEFAULT, "player", "default",
            ModelResourcePurpose.FULL, null);
        var guiRequest = fixture.manager.request(PublishedModelId.INTRINSIC_DEFAULT, "player", "default",
            ModelResourcePurpose.GUI, null);
        assertEquals(request, guiRequest, "Intrinsic previews must use the resident FULL resource");
        assertFalse(target.animationStore().interpreted());
        var subject = (LivingEntity)UnsafeUtil.getUnsafe().allocateInstance(ArmorStand.class);
        var first = new IntrinsicOwner(subject);
        var second = new IntrinsicOwner(subject);
        var runtime = new ClientAudioRuntime(Runnable::run, fixture.manager::resolveSound, host, null);
        long pcmBytes = 0;
        long nonzero = 0;
        try {
            System.setSecurityManager(denial);
            assertThrows(SecurityException.class, () -> Files.readAllBytes(fixture.sourceRoot.resolve("ysm.json")));
            assertThrows(SecurityException.class, () -> Files.readAllBytes(fixture.container));
            assertThrows(SecurityException.class, () -> Files.readAllBytes(fixture.cacheProbe));
            assertThrows(SecurityException.class, () -> {
                try (var socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", 9), 50);
                }
            });
            assertEquals(3, denial.reads.get());
            assertTrue(denial.network.get() > 0);
            denial.reads.set(0);
            denial.network.set(0);

            for (int attempt = 0; attempt < 2; attempt++) {
                for (int tick = 0; tick < 201; tick++)
                    fixture.manager.tick();
                try (var guiLease = fixture.manager.getOrStart(guiRequest)) {
                    assertSame(target, assertInstanceOf(AcquireResult.Ready.class, guiLease.poll()).target());
                }
            }

            first.install(request, fixture.manager.getOrStart(request));
            second.install(request, fixture.manager.getOrStart(request));
            assertTrue(first.hasInstalledModel());
            assertTrue(second.hasInstalledModel());
            assertNotSame(first.getLoadedGeoModel(), second.getLoadedGeoModel());
            assertNotSame(first.getAnimationData(), second.getAnimationData());
            assertNotSame(first.getExpressionRuntime(), second.getExpressionRuntime());
            var animation = first.getAnimation("idle");
            assertNotNull(animation);
            assertFalse(animation.boneAnimations.isEmpty());
            assertNotNull(second.getAnimation("walk"));
            sampleRotation(first, subject, "idle", "LeftArm", 1.5f, 2, -1);
            sampleRotation(second, subject, "walk", "LeftLeg", 0.5f, 0, 22.49507f);
            assertNotNull(runtime.createPlayback(sound).play(new FixedControl()));
            try (var stream = host.stream) {
                assertNotNull(stream);
                assertEquals(1, stream.getFormat().getChannels());
                for (;;) {
                    awaitReadable(stream);
                    var pcm = stream.read(4095);
                    if (!pcm.hasRemaining())
                        break;
                    pcmBytes += pcm.remaining();
                    while (pcm.hasRemaining())
                        if (pcm.get() != 0)
                            nonzero++;
                }
            }
            assertEquals(sound.media().frames() * Short.BYTES, pcmBytes);
            assertTrue(nonzero > 0, "A silent or empty stream is not a sound-consumption oracle");
            runtime.maintain();
            runtime.close();
            try (var retained = fixture.manager.findReady(request).orElseThrow()) {
                assertTrue(retained.isCurrent(request));
                assertTrue(sound.acquireFirstChunk().size() > 0);
            }
            first.retire();
            second.retire();
            assertFalse(first.hasUnfinishedWork());
            assertFalse(second.hasUnfinishedWork());
            assertFalse(first.hasInstalledModel());
            assertFalse(second.hasInstalledModel());
            assertEquals(0, fixture.manager.loadingCount());
            assertEquals(0, fixture.fetches.get());
            assertEquals(builds, fixture.builds.get(), "No consumer load or bake can start after publication");
            assertArrayEquals(before.stageCalls(), fixture.metrics.snapshot().stageCalls());
            assertEquals(before.portableProgramDecodes(), fixture.metrics.snapshot().portableProgramDecodes());
            assertEquals(0, denial.reads.get());
            assertEquals(0, denial.network.get());
        } finally {
            System.setSecurityManager(previous);
            runtime.close();
            first.retire();
            second.retire();
        }
    }

    private static void awaitReadable(CustomAudioStream stream) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (;;) {
            synchronized (stream) {
                assertFalse(stream.isClosed(), "The production stream closed before EOF");
                if ((int)field(stream, "readyBytes") > 0 || (boolean)field(stream, "sourceComplete"))
                    return;
            }
            assertTrue(System.nanoTime() < deadline, "The production decoder did not make its next PCM block ready");
            Thread.sleep(1);
        }
    }

    private static void awaitExpressionOwnersReturned(ExpressionEngineMetrics metrics) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Arrays.stream(dev.semaweave.compilation.EngineMetrics.Owner.values())
            .anyMatch(owner -> metrics.snapshot().active(owner) != 0)) {
            assertTrue(System.nanoTime() < deadline, "Expression owners remained after service close and Cleaner");
            System.gc();
            Thread.sleep(10);
        }
    }

    private static void sampleRotation(IntrinsicOwner owner, LivingEntity subject, String animationName,
        String boneName, float seconds, int axis, float degrees) {
        var bone = owner.getAnimation(animationName).boneAnimations.stream()
            .filter(value -> value.boneName.equals(boneName)).findFirst().orElseThrow();
        var frame = bone.rotationKeyFrames.stream()
            .filter(value -> Math.abs(value.getEndTick() - seconds * 20) < 0.003f).findFirst().orElseThrow();
        var inputs = new EntityExpressionRuntime.InvocationInputs(
            new com.elfmcys.ysm.expression.host.InvocationInputs<LivingEntity>() {
                @Override
                public LivingEntity entity() {
                    return subject;
                }
            });
        float value =
            owner.getExpressionRuntime().observe(inputs, evaluation -> frame.getLerpPoint(evaluation, 1).get(axis));
        assertEquals(degrees * (axis == 2 ? 1 : -1) * (float)Math.PI / 180, value, 0.00001f,
            animationName + "/" + boneName);
    }

    static void initializeHostTypes() throws Exception {
        SharedConstants.tryDetectVersion();
        var bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        boolean previous = bootstrapped.getBoolean(null);
        bootstrapped.setBoolean(null, true);
        try {
            BuiltInRegistries.REGISTRY.size();
            Class.forName(ArmorStand.class.getName());
        } finally {
            bootstrapped.setBoolean(null, previous);
        }
        for (var name : List.of("PlayerLocator", "FirstPersonLocator", "ProjectileLocator", "VehicleLocator")) {
            var type = Class.forName("com.elfmcys.ysm.client.model.locator." + name);
            if (field(type, null, "INSTANCE") != null)
                continue;
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            var locator = constructor.newInstance();
            var freeze = GeoLocatorType.class.getDeclaredMethod("freeze");
            freeze.setAccessible(true);
            freeze.invoke(locator);
            set(type, null, "INSTANCE", locator);
        }
    }

    private static final class IntrinsicOwner extends CustomHumanoidEntity<LivingEntity> {
        IntrinsicOwner(LivingEntity subject) {
            super(subject, false);
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

        @Override
        protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations) {}
    }

    @SuppressWarnings("removal")
    private static final class DeniedModelIo extends SecurityManager {
        private final List<Path> roots;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger network = new AtomicInteger();

        DeniedModelIo(List<Path> roots) {
            this.roots = roots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        }

        @Override
        public void checkPermission(Permission permission) {
            if (permission instanceof SocketPermission) {
                network.incrementAndGet();
                throw new SecurityException("Network acquisition denied");
            }
        }

        @Override
        public void checkRead(String file) {
            var normalized = Path.of(file).toAbsolutePath().normalize();
            if (roots.stream().anyMatch(normalized::startsWith)) {
                reads.incrementAndGet();
                throw new SecurityException("Model disk reads denied: " + file);
            }
        }

        @Override
        public void checkConnect(String host, int port) {
            network.incrementAndGet();
            throw new SecurityException("Network acquisition denied");
        }

        @Override
        public void checkConnect(String host, int port, Object context) {
            checkConnect(host, port);
        }
    }

    private static final class StreamHost implements AudioHost {
        CustomAudioStream stream;

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public boolean paused() {
            return false;
        }

        @Override
        public HostPlayback submit(CustomAudioStream stream, PlaybackControl control) {
            assertNull(this.stream);
            this.stream = stream;
            return new HostPlayback() {
                @Override
                public boolean active() {
                    return !stream.isClosed();
                }

                @Override
                public void stopPlayback() {
                    try {
                        stream.close();
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                }
            };
        }
    }

    private record FixedControl() implements PlaybackControl {
        @Override
        public boolean isLooping() {
            return false;
        }

        @Override
        public boolean isRetired() {
            return false;
        }

        @Override
        public AudioBackend.Control snapshot(boolean paused) {
            return new AudioBackend.Control(1, 1, 1, 0, 0, 0, true, false, paused);
        }
    }

    static final class Fixture implements AutoCloseable {
        final Path sourceRoot;
        final Path container;
        final Path cacheProbe;
        final ManagedContainer resident;
        final ModelStorageInfra storage;
        final AtomicSharedCache cache;
        final ClientCatalogManager catalogs;
        final ReloadableModelCatalog catalog;
        final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
        final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        final ExpressionEngineMetrics metrics = new ExpressionEngineMetrics();
        final AtomicInteger builds = new AtomicInteger();
        final AtomicInteger fetches = new AtomicInteger();
        final ClientModelRenderTargetManager manager;
        final ModelSystem system;
        final ClientModelService service;
        boolean closed;

        Fixture(Path root) throws Exception {
            this(root, true);
        }

        Fixture(Path root, boolean withSound) throws Exception {
            sourceRoot = Path.of(getClass().getResource("/assets/ysm/builtin/default/ysm.json").toURI()).getParent();
            var raw = root.resolve("raw");
            try (var paths = Files.walk(sourceRoot)) {
                for (var path : paths.toList()) {
                    var destination = raw.resolve(sourceRoot.relativize(path).toString());
                    if (Files.isDirectory(path))
                        Files.createDirectories(destination);
                    else
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (withSound)
                Files.copy(
                    Path.of(getClass().getResource("/assets/ysm/builtin/wine_fox/07_jk/sounds/minuet.ogg").toURI()),
                    Files.createDirectories(raw.resolve("sounds")).resolve("tone.ogg"));
            try (var directory = new Directory(raw)) {
                container = ModelParser.parseBuiltinDefault(directory, Files.createDirectories(root.resolve("model")));
            }
            resident = ManagedContainer.openResidentDefault(Files.readAllBytes(container),
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            var materialized = BuiltinModelMaterializer.materialize(resident);
            var contract = BuiltinModelIndex.of(Map.of(new ModelPath("default"), materialized.modelHash()),
                materialized.animationHashes(), Map.of());
            var builtins = (BuiltinModelCatalog)UnsafeUtil.getUnsafe().allocateInstance(BuiltinModelCatalog.class);
            set(BuiltinModelCatalog.class, builtins, "contract", contract);
            set(BuiltinModelCatalog.class, builtins, "defaultHandle", resident);
            storage =
                ModelStorageInfra.open(AssetPaths.localOnly(root, root.resolve("config"), root.resolve("builtin")));
            catalog = new ReloadableModelCatalog(storage, List.of(), builtins);
            var systemConstructor = ModelSystem.class.getDeclaredConstructor(ModelStorageInfra.class,
                BuiltinModelCatalog.class, ReloadableModelCatalog.class);
            systemConstructor.setAccessible(true);
            system = systemConstructor.newInstance(storage, builtins, catalog);
            catalogs = new ClientCatalogManager(catalog);
            var animations = new DefaultAnimationRuntime(contract);
            cache = new AtomicSharedCache(root.resolve("baked"));
            cacheProbe = root.resolve("baked/probe");
            Files.createDirectories(cacheProbe.getParent());
            Files.write(cacheProbe, new byte[] {1});
            var loader = new ModelRenderTargetLoader(new BakedModelCache(root.resolve("baked"), cache),
                new RenderTargetExpressionModuleFactory(storage.local().expressions(), storage.local().animations(),
                    ExpressionHost.create(HostAvailability.none()), metrics),
                scheduler, animations);
            var consumer = field(loader, "consumerStage");
            var consumerType = consumer.getClass().getInterfaces()[0];
            set(ModelRenderTargetLoader.class, loader, "consumerStage", Proxy.newProxyInstance(
                consumerType.getClassLoader(), new Class<?>[] {consumerType}, (proxy, method, args) -> {
                    builds.incrementAndGet();
                    try {
                        method.setAccessible(true);
                        return method.invoke(consumer, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }));
            manager = new ClientModelRenderTargetManager(catalogs, loader, animations, scheduler, timer, Runnable::run);
            var targets = field(manager, "renderTargets");
            var publisherType = Class.forName("com.elfmcys.ysm.model.resource.client.render.HostTexturePublisher");
            var bindingType = Class.forName(publisherType.getName() + "$PublishedTextureBinding");
            var binding = bindingType.getDeclaredConstructor(ResourceLocation.class, TextureManager.class, List.class);
            binding.setAccessible(true);
            set(targets.getClass(), targets, "texturePublisher", Proxy.newProxyInstance(publisherType.getClassLoader(),
                new Class<?>[] {publisherType}, (proxy, method, args) -> {
                    ((AutoCloseable)args[0]).close();
                    return binding.newInstance(ResourceLocation.tryParse("ysm:residency-test"),
                        UnsafeUtil.getUnsafe().allocateInstance(TextureManager.class), List.of());
                }));
            catalogs.setListener(manager::catalogChanged);
            var serviceConstructor = ClientModelService.class.getDeclaredConstructor(ModelSystem.class,
                ClientResourceScheduler.class, ScheduledThreadPoolExecutor.class, ClientCatalogManager.class,
                ClientAssetRepository.class, ClientModelRenderTargetManager.class, Path.class);
            serviceConstructor.setAccessible(true);
            service = serviceConstructor.newInstance(system, scheduler, timer, catalogs,
                new ClientAssetRepository(catalogs), manager, root);
        }

        @Override
        public void close() throws Exception {
            if (closed)
                return;
            closed = true;
            set(ClientModelService.class, null, "INSTANCE", null);
            service.close();
            system.close();
            timer.shutdownNow();
            assertTrue(timer.awaitTermination(10, TimeUnit.SECONDS));
            scheduler.shutdown();
            scheduler.awaitTermination();
            cache.derivedWork().close();
            storage.close();
            assertEquals(0, manager.loadingCount());
        }
    }

    private static Object field(Object object, String name) throws Exception {
        return field(object.getClass(), object, name);
    }

    private static Object field(Class<?> type, Object object, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Class<?> type, Object object, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
