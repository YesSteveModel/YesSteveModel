package com.elfmcys.ysm.model.resource.client.preview;

import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;

import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@org.junit.jupiter.api.Timeout(30)
class ClientPreviewGeneratorTest {
    private static final Hash256 MODEL_ID = hash(1);
    private static final ResourceRequest REQUEST = new ResourceRequest(MODEL_ID, "player", "", new BakeProfile("test"));

    @Test
    void ownerTickAdmitsEveryStageAndCallbacksOnlyPublishFacts() throws Exception {
        var acquisition = new CompletableFuture<AcquireResult>();
        var lease = new FakeLease();
        var host = new QueueExecutor();
        var workers = new QueueExecutor();
        var pixels = new NativeImage(1, 1, false);
        var expected = TestPreviews.pending();
        try (
            var generator = new ClientPreviewGenerator(ignored -> awaiting(lease, acquisition), (request, acquired) -> {
                assertSame(REQUEST, request);
                assertEquals(0, lease.closes.get());
                return pixels;
            }, image -> {
                image.getPixelRGBA(0, 0);
                return expected;
            }, host, workers)) {
            var result = generator.generate(content());
            assertFalse(result.isDone());
            assertEquals(0, generator.activeOperationCount());

            generator.tick();
            assertEquals(1, generator.activeOperationCount());

            acquisition.complete(new AcquireResult.Ready(target()));
            assertEquals(0, host.size());
            generator.tick();
            assertEquals(1, host.size());
            assertEquals(0, workers.size());
            assertFalse(result.isDone());

            host.runNext();
            assertEquals(1, lease.closes.get());
            assertEquals(0, workers.size());
            generator.tick();
            assertEquals(1, workers.size());
            assertFalse(result.isDone());

            workers.runNext();
            assertFalse(result.isDone());
            assertEquals(1, generator.activeOperationCount());
            generator.tick();
            workers.runNext();
            generator.tick();
            assertEquals(1, result.join().width());
            assertEquals(0, generator.activeOperationCount());
            assertClosed(pixels);
        }
    }

    @Test
    void hostFailureClosesLeaseWithoutStartingEncoding() {
        var failure = new IllegalStateException("host failed");
        var lease = new FakeLease();
        var workers = new QueueExecutor();
        try (var generator = new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> {
            throw failure;
        }, image -> {
            throw new AssertionError("encoding must not start");
        }, Runnable::run, workers)) {
            var result = generator.generate(content());
            generator.tick();
            generator.tick();
            assertFalse(result.isDone());
            generator.tick();
            var thrown = assertThrows(CompletionException.class, result::join);
            assertSame(failure, thrown.getCause());
            assertEquals(1, lease.closes.get());
            assertEquals(0, workers.size());
            assertEquals(0, generator.activeOperationCount());
        }
    }

    @Test
    void encodingFailureClosesPixelsAndLease() {
        var failure = new IllegalStateException("encode failed");
        var lease = new FakeLease();
        var pixels = new NativeImage(1, 1, false);
        try (var generator =
            new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> pixels, image -> {
                throw failure;
            }, Runnable::run, Runnable::run)) {
            var result = generator.generate(content());
            generator.tick();
            generator.tick();
            generator.tick();
            assertFalse(result.isDone());
            generator.tick();
            var thrown = assertThrows(CompletionException.class, result::join);
            assertSame(failure, thrown.getCause());
            assertEquals(1, lease.closes.get());
            assertClosed(pixels);
            assertEquals(0, generator.activeOperationCount());
        }
    }

    @Test
    void previewAndExportShareOneAdmissionUntilOwnerObservedTerminal() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("admission-raw"));
        var source =
            com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("admission-source.mxc"),
                "pack/model", 10, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.RAW,
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        var failure = new java.io.IOException("Export preparation failed");
        var firstAcquisition = new CompletableFuture<AcquireResult>();
        var firstLease = new FakeLease();
        var targetStarts = new AtomicInteger();
        var host = new QueueExecutor();
        var workers = new QueueExecutor();
        var firstPixels = new NativeImage(1, 1, false);
        try (var generator = new ClientPreviewGenerator(ignored -> {
            targetStarts.incrementAndGet();
            return awaiting(firstLease, firstAcquisition);
        }, (request, lease) -> firstPixels, image -> TestPreviews.pending(), host, workers, null, request -> {
            throw failure;
        }, com.elfmcys.ysm.model.service.ModelExportService::finish, 1, 2, 4);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var first = generator.generate(content());
            var second = environment.export("pack/model", null);

            generator.tick();
            assertEquals(1, targetStarts.get());
            assertEquals(1, generator.activeOperationCount());

            firstAcquisition.complete(new AcquireResult.Ready(target()));
            assertEquals(1, targetStarts.get());
            generator.tick();
            host.runNext();
            generator.tick();
            workers.runNext();

            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertEquals(1, targetStarts.get());
            assertEquals(1, generator.activeOperationCount());

            generator.tick();
            workers.runNext();
            generator.tick();
            assertTrue(first.isDone());
            first.join();
            assertEquals(1, targetStarts.get());
            assertEquals(1, generator.activeOperationCount());
            assertFalse(second.isDone());
            assertEquals(1, workers.size());

            workers.runNext();
            assertFalse(second.isDone());
            generator.tick();
            assertSame(failure, assertThrows(CompletionException.class, second::join).getCause());
            assertEquals(0, generator.activeOperationCount());
            assertClosed(firstPixels);
        } finally {
            source.representation().close();
        }
    }

    @Test
    void cancellationClosesLateLeaseBehindProductionTargetFuture() {
        var upstream = new CompletableFuture<AcquireResult>();
        var lease = new FakeLease();
        var host = new QueueExecutor();
        var workers = new QueueExecutor();
        try (var generator = new ClientPreviewGenerator(ignored -> awaiting(lease, upstream), (request, acquired) -> {
            throw new AssertionError("cancelled target must not reach the host");
        }, image -> {
            throw new AssertionError("cancelled target must not reach encoding");
        }, host, workers)) {
            var result = generator.generate(content());
            generator.tick();
            assertEquals(1, generator.activeOperationCount());

            assertEquals(true, result.cancel(false));
            generator.tick();
            assertEquals(1, lease.closes.get());
            assertEquals(0, generator.activeOperationCount());

            // The exact acquisition can finish after its consumer interest was cancelled.
            assertEquals(true, upstream.complete(new AcquireResult.Ready(target())));
            assertEquals(1, lease.closes.get());
            assertEquals(0, host.size());
            assertEquals(0, workers.size());
        }
    }

    @Test
    void closeCancelsAwaitingAcquisitionBeforeDroppingItsToken() {
        var terminal = new CompletableFuture<AcquireResult>();
        var lease = new FakeLease();
        var generator = new ClientPreviewGenerator(ignored -> awaiting(lease, terminal), (request, acquired) -> {
            throw new AssertionError("closed target must not reach the host");
        }, image -> {
            throw new AssertionError("closed target must not reach encoding");
        }, Runnable::run, Runnable::run);
        generator.generate(content());
        generator.tick();

        generator.close();

        assertEquals(1, lease.closes.get());
        assertEquals(0, generator.activeOperationCount());
        assertTrue(terminal.complete(new AcquireResult.Ready(target())));
        assertEquals(1, lease.closes.get());
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temporary;

    @Test
    void encodedImageHitUsesMetadataOnlyAndDoesNotAcquireBakeDrawOrEncode() throws Exception {
        var source = java.nio.file.Path.of(
            java.util.Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default/ysm.json")).toURI())
            .getParent();
        final java.nio.file.Path file;
        try (var input = new com.elfmcys.ysm.format.vfs.Directory(source)) {
            file = com.elfmcys.ysm.format.parser.ModelParser.parseBuiltinDefault(input,
                java.nio.file.Files.createDirectories(temporary.resolve("source")));
        }
        var local = com.elfmcys.ysm.model.storage.ManagedContainer.openDirect(file,
            new com.elfmcys.ysm.model.catalog.source.CatalogLocation(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.AUTH,
                new com.elfmcys.ysm.model.domain.ModelPath("fixture")));
        var store = new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("images"));
        try (
            var remoteStore =
                new com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore(temporary.resolve("remote"));
            var expected = TestPreviews.blank()) {
            var prefix = local.representation().metadataPrefix().orElseThrow();
            var content = remoteStore.commitMetadataPrefix(local.representation().identity(), prefix);
            try {
                store.accept(content.representation().containerId(), expected.bytes());
                try (var generator = new ClientPreviewGenerator(ignored -> {
                    throw new AssertionError("Image hit must not acquire a target");
                }, (request, lease) -> {
                    throw new AssertionError("Image hit must not draw");
                }, pixels -> {
                    throw new AssertionError("Image hit must not encode");
                }, Runnable::run, Runnable::run, store, request -> {
                    throw new AssertionError();
                }, (prepared, preview) -> {
                    throw new AssertionError();
                }, 16, 32, 4)) {
                    var result = generator.resolveEncoded(content, (identity, descriptors, receiver) -> {
                        throw new AssertionError("Image hit must not fetch body");
                    });
                    generator.tick();
                    generator.tick();
                    generator.tick();
                    assertTrue(result.isDone());
                    try (var actual = result.join()) {
                        org.junit.jupiter.api.Assertions.assertArrayEquals(expected.bytes(), actual.bytes());
                    }
                    assertEquals(0, generator.activeOperationCount());
                    try (var files = java.nio.file.Files.walk(remoteStore.root())) {
                        assertTrue(files.noneMatch(path -> path.toString().endsWith(".chunk")));
                    }
                }
            } finally {
                content.representation().close();
            }
        } finally {
            local.representation().close();
        }
    }

    private static ClientPreviewGenerator.AwaitingTarget ready(ResourceLease lease) {
        return awaiting(lease, CompletableFuture.completedFuture(new AcquireResult.Ready(target())));
    }

    @Test
    void encodedColdHostFailureUsesTheProductionOperationAndDoesNotPersist() throws Exception {
        var source = java.nio.file.Path.of(
            java.util.Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default/ysm.json")).toURI())
            .getParent();
        final java.nio.file.Path file;
        try (var input = new com.elfmcys.ysm.format.vfs.Directory(source)) {
            file = com.elfmcys.ysm.format.parser.ModelParser.parseBuiltinDefault(input,
                java.nio.file.Files.createDirectories(temporary.resolve("cold")));
        }
        var content = com.elfmcys.ysm.model.storage.ManagedContainer.openDirect(file,
            new com.elfmcys.ysm.model.catalog.source.CatalogLocation(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.AUTH,
                new com.elfmcys.ysm.model.domain.ModelPath("cold")));
        var lease = new FakeLease();
        var failure = new IllegalStateException("Actual host seam rejected draw");
        try (var generator = new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> {
            throw failure;
        }, pixels -> {
            throw new AssertionError("Failed host must not encode");
        }, Runnable::run, Runnable::run,
            new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("cold-cache")), request -> {
                throw new AssertionError();
            }, (prepared, preview) -> {
                throw new AssertionError();
            }, 16, 32, 4)) {
            var result = generator.resolveEncoded(content, (identity, descriptors, receiver) -> {
                throw new AssertionError("Local input must not fetch");
            });
            for (int i = 0; i < 5; i++)
                generator.tick();
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
            assertEquals(1, lease.closes.get());
            assertEquals(0, generator.activeOperationCount());
            assertFalse(java.nio.file.Files.exists(temporary.resolve("cold-cache/previews")));
        } finally {
            content.representation().close();
        }
    }

    @Test
    void exportEnvironmentStopBeforeHostClosesOnlyItsLeaseAndDoesNotDraw() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("export-raw"));
        var source = com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("export-source.mxc"),
            "fixture", 10, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        var output = temporary.resolve("export-output.mxc");
        var host = new QueueExecutor();
        var lease = new FakeLease();
        try (var generator = new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> {
            throw new AssertionError("Retired export must not draw");
        }, pixels -> {
            throw new AssertionError();
        }, host, Runnable::run, null,
            request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request,
                new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("export-cache")), output),
            com.elfmcys.ysm.model.service.ModelExportService::finish, 16, 32, 4);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var result = environment.export("fixture", null);
            for (int i = 0; i < 3; i++)
                generator.tick();
            assertEquals(1, host.size());
            environment.close();
            assertEquals(0, generator.activeOperationCount());
            assertEquals(1, lease.closes.get());
            assertTrue(result.isCompletedExceptionally());
            host.runNext();
            assertEquals(1, lease.closes.get());
            assertFalse(java.nio.file.Files.exists(output));
        } finally {
            source.representation().close();
        }
    }

    @Test
    void exportEnvironmentStopAfterPixelsClosesPixelsWithoutEncodingOrOutput() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("pixels-raw"));
        var source = com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("pixels-source.mxc"),
            "fixture", 11, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        var output = temporary.resolve("pixels-output.mxc");
        var pixels = new NativeImage(1, 1, false);
        var lease = new FakeLease();
        try (var generator =
            new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> pixels, image -> {
                throw new AssertionError("Retired export must not encode");
            }, Runnable::run, Runnable::run, null,
                request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request,
                    new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("pixels-cache")), output),
                com.elfmcys.ysm.model.service.ModelExportService::finish, 16, 32, 4);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var result = environment.export("fixture", null);
            for (int i = 0; i < 3; i++)
                generator.tick();
            environment.close();
            assertClosed(pixels);
            assertEquals(1, lease.closes.get());
            assertTrue(result.isCompletedExceptionally());
            assertEquals(0, generator.activeOperationCount());
            assertFalse(java.nio.file.Files.exists(output));
        } finally {
            source.representation().close();
        }
    }

    @Test
    void generatedExportPreviewRemainsAvailableInTheIndependentCache() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("generated-cache-raw"));
        var source =
            com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("generated-cache-source.mxc"),
                "fixture", 15, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        var output = temporary.resolve("generated-cache-output.mxc");
        var observed = new ArrayList<Hash256>();
        var store = new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("generated-cache"), observed::add);
        var lease = new FakeLease();
        final byte[] expected;
        try (var encoded = TestPreviews.blank()) {
            expected = encoded.bytes();
        }
        try (
            var generator =
                new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> new NativeImage(1, 1, false),
                    image -> TestPreviews.pending(), Runnable::run, Runnable::run, store,
                    request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request, store, output),
                    com.elfmcys.ysm.model.service.ModelExportService::finish, 16, 32, 4);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            com.elfmcys.ysm.model.storage.PreviewDecodeProbe.reset();
            var result = environment.export("fixture", null);
            for (var tick = 0; tick < 6; tick++)
                generator.tick();
            assertEquals(output, result.join());
            assertTrue(java.nio.file.Files.isRegularFile(output));
            assertEquals(1, lease.closes.get());
            if (Boolean.getBoolean("ysm.previewDecodeProbe")) {
                assertEquals(1, com.elfmcys.ysm.model.storage.PreviewDecodeProbe.count());
                System.out.println("PREVIEW_EXPORT decode=1 stagingMediaDecodes=0");
            }
            var cached = store.load(source.representation().containerId());
            assertTrue(cached.isPresent(),
                "The inherited artifact-storage decision includes export-generated previews");
            try (var preview = cached.orElseThrow()) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(expected, preview.bytes());
            }
            assertTrue(observed.contains(source.representation().containerId()));
        } finally {
            source.representation().close();
        }
    }

    @Test
    void generatedExportSurvivesOptionalCacheWriteFailure() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("optional-cache-raw"));
        var source =
            com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("optional-cache-source.mxc"),
                "fixture", 16, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        var output = temporary.resolve("optional-cache-output.mxc");
        var cacheRoot = temporary.resolve("optional-cache");
        java.nio.file.Files.writeString(cacheRoot, "A file blocks cache directory creation");
        var store = new com.elfmcys.ysm.model.storage.PreviewStore(cacheRoot);
        var lease = new FakeLease();
        try (
            var generator =
                new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> new NativeImage(1, 1, false),
                    image -> TestPreviews.pending(), Runnable::run, Runnable::run, store,
                    request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request, store, output),
                    com.elfmcys.ysm.model.service.ModelExportService::finish, 16, 32, 4);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var result = environment.export("fixture", null);
            for (var tick = 0; tick < 6; tick++)
                generator.tick();
            assertEquals(output, result.join());
            assertEquals(1, lease.closes.get());
            var reopened = com.elfmcys.ysm.model.storage.ManagedContainer.openDirect(output, source.location());
            try {
                com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission.requireEmbeddedPreview(reopened);
            } finally {
                reopened.representation().close();
            }
            assertTrue(store.load(source.representation().containerId()).isEmpty());
        } finally {
            source.representation().close();
        }
    }

    @Test
    void warmExportUsesSameCommitCapabilityAndNoTargetOrHost() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("warm-raw"));
        var source = com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("warm-source.mxc"),
            "fixture", 12, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.RAW,
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        var output = temporary.resolve("warm-output.mxc");
        try (var generator = new ClientPreviewGenerator(ignored -> {
            throw new AssertionError("Warm export must not acquire");
        }, (request, acquired) -> {
            throw new AssertionError("Warm export must not draw");
        }, image -> {
            throw new AssertionError("Warm export must not encode");
        }, Runnable::run, Runnable::run, null,
            request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request,
                new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("warm-cache")), output),
            com.elfmcys.ysm.model.service.ModelExportService::finish, 16, 32, 4);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var result = environment.export("fixture", "warm");
            generator.tick();
            assertTrue(java.nio.file.Files.isRegularFile(output));
            assertFalse(result.isDone());
            environment.close();
            assertEquals(output, result.join());
            assertEquals(0, generator.activeOperationCount());
            var reopened = com.elfmcys.ysm.model.storage.ManagedContainer.openDirect(output, source.location());
            try {
                com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission.requireEmbeddedPreview(reopened);
            } finally {
                reopened.representation().close();
            }
        } finally {
            source.representation().close();
        }
    }

    @Test
    void exportCancellationReleasesOnlyItsSharedPreparationInterest() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("shared-raw"));
        var source = com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("shared-source.mxc"),
            "fixture", 13, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        try (var preparation = new com.elfmcys.ysm.model.resource.client.render.PreviewPreparationFixture();
            var generator = new ClientPreviewGenerator(
                ignored -> new ClientPreviewGenerator.AsyncDetachedTarget(REQUEST, cancelled -> preparation.acquire()),
                (request, acquired) -> {
                    throw new AssertionError("Retired export must not draw");
                }, pixels -> {
                    throw new AssertionError();
                }, Runnable::run, Runnable::run, null,
                request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request,
                    new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("shared-cache")),
                    temporary.resolve("shared-output.mxc")),
                com.elfmcys.ysm.model.service.ModelExportService::finish, 16, 32, 4);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var export = environment.export("fixture", null);
            generator.tick();
            generator.tick();
            var other = preparation.acquire();
            environment.close();
            assertTrue(export.isCompletedExceptionally());
            preparation.finishPreparation();
            assertFalse(other.isCompletedExceptionally());
            assertEquals(1, preparation.inputsClosed.get());
            assertEquals(0, preparation.preparedClosed.get());
            other.join().close();
            assertEquals(1, preparation.preparedClosed.get());
            assertEquals(0, generator.activeOperationCount());
            assertFalse(java.nio.file.Files.exists(temporary.resolve("shared-output.mxc")));
        } finally {
            source.representation().close();
        }
    }

    @Test
    void lateAsyncCandidateHasOneClosingOwnerAfterExportEnvironmentStop() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("late-raw"));
        var source = com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("late-source.mxc"),
            "fixture", 14, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        var loading = new CompletableFuture<
            com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetLoader.ModelCandidate>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        var closes = new AtomicInteger();
        try (var generator = new ClientPreviewGenerator(
            ignored -> new ClientPreviewGenerator.AsyncDetachedTarget(REQUEST, cancelled -> loading),
            (request, acquired) -> {
                throw new AssertionError("Late candidate must not draw");
            }, pixels -> {
                throw new AssertionError();
            }, Runnable::run, Runnable::run, null,
            request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request,
                new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("late-cache")),
                temporary.resolve("late-output.mxc")),
            com.elfmcys.ysm.model.service.ModelExportService::finish, 16, 32, 4);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            environment.export("fixture", null);
            generator.tick();
            generator.tick();
            environment.close();
            var candidate = com.elfmcys.ysm.model.resource.client.render.PreviewPreparationFixture
                .candidate(closes::incrementAndGet);
            assertTrue(loading.complete(candidate));
            assertEquals(1, closes.get());
            candidate.close();
            assertEquals(1, closes.get());
            assertThrows(IllegalStateException.class, candidate::publish);
            assertEquals(0, generator.activeOperationCount());
        } finally {
            source.representation().close();
        }
    }

    @Test
    void heldPixelsDoNotDelayAnotherDecodeAndCancellationReleasesQueuedInput() throws Exception {
        var workers = new QueueExecutor();
        try (var generator = pixelGenerator(workers); var input = TestPreviews.blank();
            var bytes = input.acquireBytes()) {
            var first = generator.resolveRaw(content(), bytes);
            var second = generator.resolveRaw(content(), bytes);
            var cancelled = generator.resolveRaw(content(), bytes);
            generator.tick();
            assertEquals(3, workers.size());
            workers.runNext();
            generator.tick();
            assertTrue(first.isDone());
            try (var image = (com.elfmcys.ysm.model.storage.PreviewStore.EncodedPreview)first.join();
                var host = image.takePixels()) {
                image.close();
                workers.runNext();
                generator.tick();
                assertTrue(second.isDone());
                try (var next = (com.elfmcys.ysm.model.storage.PreviewStore.EncodedPreview)second.join();
                    var pixels = next.takePixels()) {
                    pixels.borrow(data -> host.borrow(retained -> assertEquals(retained, data)));
                }
                assertTrue(cancelled.cancel(false));
                workers.runNext();
                host.borrow(pixels -> assertEquals(4, pixels.remaining()));
            }
            assertTrue(cancelled.isCancelled());
            assertEquals(0, generator.activeOperationCount());
        }
    }

    @Test
    void shutdownReleasesDeliveredPixelsAndQueuedInputWithoutPageTicks() throws Exception {
        var workers = new QueueExecutor();
        var generator = pixelGenerator(workers);
        try (var input = TestPreviews.blank(); var bytes = input.acquireBytes()) {
            var first = generator.resolveRaw(content(), bytes);
            var waiting = generator.resolveRaw(content(), bytes);
            generator.tick();
            workers.runNext();
            generator.tick();
            var image = (com.elfmcys.ysm.model.storage.PreviewStore.EncodedPreview)first.join();
            var pixels = image.takePixels();
            generator.close();
            generator.close();
            assertThrows(IllegalStateException.class, () -> pixels.borrow(ignored -> fail()));
            pixels.close();
            assertTrue(waiting.isCancelled());
            image.close();
        } finally {
            generator.close();
        }
    }

    @Test
    void eagerPolicyIsFixedWhenTheOrdinaryRequestIsSubmitted() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("eager-raw"));
        var source = com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("eager.mxc"),
            "fixture", 30, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        var eager = new java.util.concurrent.atomic.AtomicBoolean();
        var renders = new AtomicInteger();
        var encodes = new AtomicInteger();
        try (var generator = new ClientPreviewGenerator(ignored -> ready(new FakeLease()), (request, acquired) -> {
            renders.incrementAndGet();
            return new NativeImage(1, 1, false);
        }, pixels -> {
            encodes.incrementAndGet();
            return TestPreviews.pending();
        }, Runnable::run, Runnable::run)) {
            var old = generator.resolveLocal(source, !eager.get());
            eager.set(true);
            var skipped = generator.resolveLocal(source, !eager.get());
            for (var tick = 0; tick < 8; tick++)
                generator.tick();
            assertTrue(old.isDone());
            ((com.elfmcys.ysm.model.storage.PreviewStore.EncodedPreview)old.join()).close();
            assertTrue(skipped.isDone());
            assertEquals(null, skipped.join());
            assertEquals(1, renders.get());
            assertEquals(1, encodes.get());
            eager.set(false);
            var restored = generator.resolveLocal(source, !eager.get());
            for (var tick = 0; tick < 8; tick++)
                generator.tick();
            ((com.elfmcys.ysm.model.storage.PreviewStore.EncodedPreview)restored.join()).close();
            assertEquals(2, renders.get());
            assertEquals(2, encodes.get());

        } finally {
            source.representation().close();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ysm.previewDecodeProbe", matches = "true")
    void generatedLossyPreviewUsesItsEncodedPixelsAndOneRealDecode() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("lossy-raw"));
        var source = com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("lossy.mxc"),
            "fixture", 31, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM);
        try (var generator = new ClientPreviewGenerator(ignored -> ready(new FakeLease()), (request, acquired) -> {
            var image = new NativeImage(com.elfmcys.ysm.client.gui.PreviewRenderHost.WIDTH,
                com.elfmcys.ysm.client.gui.PreviewRenderHost.HEIGHT, false);
            for (var y = 0; y < image.getHeight(); y++)
                for (var x = 0; x < image.getWidth(); x++)
                    image.setPixelRGBA(x, y,
                        0xff000000 | (x * 17 & 255) | ((y * 23 & 255) << 8) | ((x + y & 255) << 16));
            return image;
        }, ClientPreviewGenerator::encode, Runnable::run, Runnable::run)) {
            com.elfmcys.ysm.model.storage.PreviewDecodeProbe.reset();
            var output = generator.resolveLocal(source);
            for (var tick = 0; tick < 8; tick++)
                generator.tick();
            assertTrue(output.isDone());
            try (var encoded = (com.elfmcys.ysm.model.storage.PreviewStore.EncodedPreview)output.join();
                var pixels = encoded.takePixels()) {
                assertEquals(1, com.elfmcys.ysm.model.storage.PreviewDecodeProbe.count());
                com.elfmcys.ysm.model.storage.PreviewDecodeProbe.oracle(() -> {
                    try (var image = encoded.open(); var independent = image.decodeToBuffer()) {
                        pixels.borrow(actual -> assertEquals(independent.nio(), actual));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                assertEquals(1, com.elfmcys.ysm.model.storage.PreviewDecodeProbe.count());
            }

            System.out.println("PREVIEW_GENERATED decode=1 independentPixelsMatch=true retained=0");
        } finally {
            source.representation().close();
        }
    }

    private static ClientPreviewGenerator pixelGenerator(Executor workers) {
        return new ClientPreviewGenerator(ignored -> {
            throw new AssertionError("Raw bytes need no model");
        }, (request, lease) -> {
            throw new AssertionError("Raw bytes need no render");
        }, pixels -> {
            throw new AssertionError("Raw bytes need no encoder");
        }, Runnable::run, workers, null, request -> {
            throw new AssertionError();
        }, (prepared, preview) -> {
            throw new AssertionError();
        }, 16, 32, 4);
    }

    private static ClientPreviewGenerator.AwaitingTarget awaiting(ResourceLease lease,
        CompletableFuture<AcquireResult> terminal) {
        return new ClientPreviewGenerator.AwaitingTarget(REQUEST,
            new ClientModelRenderTargetManager.ReadyAcquisition(REQUEST, lease, terminal));
    }

    private static ModelRenderTarget target() {
        try {
            return (ModelRenderTarget)UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
        } catch (InstantiationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertClosed(NativeImage image) {
        assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
    }

    private static ModelContent content() {
        return new ModelContent() {
            @Override
            public Hash256 modelId() {
                return MODEL_ID;
            }

            @Override
            public ModelRepresentation representation() {
                return null;
            }

            @Override
            public ModelFileView modelFile() {
                return null;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }
        };
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static final class FakeLease implements ResourceLease {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public ModelResourcePurpose purpose() {
            return ModelResourcePurpose.FULL;
        }

        @Override
        public AcquireResult poll() {
            return new AcquireResult.Pending();
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            return true;
        }

        @Override
        public void cancelPending() {
            close();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
