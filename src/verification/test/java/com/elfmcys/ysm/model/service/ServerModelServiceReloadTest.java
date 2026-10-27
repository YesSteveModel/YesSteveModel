package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.CatalogReconciler;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogCandidate;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.util.UnsafeUtil;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerModelServiceReloadTest {
    @TempDir
    static Path fixtureTemp;

    @TempDir
    Path temp;

    private static byte[] defaultContainer;

    @BeforeAll
    static void createDefaultFixture() throws Exception {
        var manifest = ServerModelServiceReloadTest.class.getResource("/assets/ysm/builtin/default/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var directory = new Directory(source)) {
            var parsed =
                ModelParser.parseBuiltinDefault(directory, Files.createDirectories(fixtureTemp.resolve("model")));
            defaultContainer = Files.readAllBytes(parsed);
        }
    }

    @Test
    void committedReloadRefreshesExternallyReplacedPreviewWithUnchangedCatalog() throws Exception {
        try (var fixture = open()) {
            var before = fixture.catalog.current().byModelId();
            var initial = new byte[] {1, 2, 3};
            var replacement = new byte[] {4, 5};
            fixture.replacePreview(initial);
            try (var previous = fixture.acquire()) {
                fixture.replacePreview(replacement);
                try (var retained = fixture.acquire()) {
                    assertArrayEquals(initial, copy(retained));
                }

                var result = fixture.reload();

                assertTrue(result.success());
                assertEquals(0, result.errorCount());
                assertEquals(before, fixture.catalog.current().byModelId());
                try (var refreshed = fixture.acquire()) {
                    assertArrayEquals(replacement, copy(refreshed));
                }
                assertArrayEquals(initial, copy(previous));
            }
        }
    }

    @Test
    void failedReloadPreservesRetainedPreviewAndCanRetry() throws Exception {
        try (var fixture = open()) {
            var before = fixture.catalog.current().byModelId();
            var initial = new byte[] {6, 7, 8};
            var replacement = new byte[] {9, 10};
            fixture.replacePreview(initial);
            try (var previous = fixture.acquire()) {
                fixture.replacePreview(replacement);
                fixture.rejectCoordinators.set(true);

                var failed = fixture.reload();

                assertFalse(failed.success());
                assertEquals("Content validation failed", failed.message());
                assertEquals(before, fixture.catalog.current().byModelId());
                try (var retained = fixture.acquire()) {
                    assertArrayEquals(initial, copy(retained));
                }
                assertArrayEquals(initial, copy(previous));

                fixture.rejectCoordinators.set(false);
                assertTrue(fixture.reload().success());
                try (var refreshed = fixture.acquire()) {
                    assertArrayEquals(replacement, copy(refreshed));
                }
                assertArrayEquals(initial, copy(previous));
            }
        }
    }

    private Fixture open() throws Exception {
        var cacheRoot = temp.resolve("cache");
        var root = Files.createDirectories(temp.resolve("models"));
        var intrinsicDefault = ManagedContainer.openResidentDefault(defaultContainer,
            new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
            new ConvertedSourceIndexStore(cacheRoot, "server-reload-test"), new ConvertedObjectStore(cacheRoot));
        var reconciler = new CatalogReconciler(List.of(new ModelCatalogSource(CatalogRootKind.CUSTOM, root, false)),
            resolver, Set.of(intrinsicDefault.modelId()), intrinsicDefault, CatalogIndexSnapshot.empty());
        var rejectCoordinators = new AtomicBoolean();
        ThreadFactory threads = body -> {
            if (rejectCoordinators.get())
                throw new IllegalStateException("Coordinator creation rejected");
            return new Thread(body);
        };
        // Reuse the catalog's package fixture seam without adding a production service interface.
        var constructor = ReloadableModelCatalog.class.getDeclaredConstructor(CatalogReconciler.class,
            CatalogCandidate.class, ThreadFactory.class);
        constructor.setAccessible(true);
        var catalog = constructor.newInstance(reconciler, reconciler.materializeBuiltins(), threads);
        var runtime = new ServerResourceRuntime(new PreviewStore(cacheRoot), failure -> {
            throw new AssertionError(failure);
        });
        // reload() only needs these owners; Minecraft hosting and dispatch are outside this fixture.
        var service = (ServerModelService)UnsafeUtil.getUnsafe().allocateInstance(ServerModelService.class);
        setField(service, "catalog", catalog);
        setField(service, "serverResources", runtime);
        var fixture = new Fixture(catalog, runtime, service, rejectCoordinators,
            intrinsicDefault.representation().containerId(), cacheRoot);
        catalog.activate();
        await(() -> {
            catalog.tick();
            return !catalog.busy();
        });
        assertEquals(1, catalog.current().byModelId().size());
        return fixture;
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static byte[] copy(ServerResourceRuntime.BytesLease lease) {
        var target = ByteBuffer.allocate(lease.size());
        lease.copyTo(0, target);
        return target.array();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline)
            Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "Owner work did not complete");
    }

    private record Fixture(ReloadableModelCatalog catalog, ServerResourceRuntime runtime, ServerModelService service,
        AtomicBoolean rejectCoordinators, Hash256 containerId, Path cacheRoot) implements AutoCloseable {
        private void replacePreview(byte[] bytes) throws Exception {
            var root = Files.createDirectories(cacheRoot.resolve("previews"));
            var staged = Files.write(root.resolve("external.image"), bytes);
            Files.move(staged, root.resolve(containerId + ".image"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        }

        private ServerResourceRuntime.BytesLease acquire() throws Exception {
            var result = runtime.acquirePreview(containerId);
            await(() -> {
                runtime.tick();
                return result.isDone();
            });
            return result.get(1, TimeUnit.SECONDS).orElseThrow();
        }

        private ServerModelService.ReloadOutcome reload() throws Exception {
            CompletableFuture<ServerModelService.ReloadOutcome> result = service.reload();
            await(() -> {
                catalog.tick();
                return result.isDone();
            });
            return result.get(1, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            runtime.close();
            catalog.close();
        }
    }
}
