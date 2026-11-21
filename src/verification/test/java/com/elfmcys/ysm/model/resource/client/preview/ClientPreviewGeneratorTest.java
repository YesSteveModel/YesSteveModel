package com.elfmcys.ysm.model.resource.client.preview;

import com.elfmcys.ysm.model.domain.Hash256;

import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private static final ModelId MODEL_ID = hash(1);
    private static final ResourceRequest REQUEST = new ResourceRequest(MODEL_ID, "player", "", new BakeProfile("test"));

    @Test
    void previewAndExportShareOneAdmissionUntilOwnerObservedTerminal() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("admission-raw"));
        var source =
            com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("admission-source.mxc"),
                "pack/model", 10, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS);
        var failure = new java.io.IOException("Export preparation failed");
        var firstLease = new FakeLease();
        var targetStarts = new AtomicInteger();
        var host = new QueueExecutor();
        var workers = new QueueExecutor();
        var firstPixels = new NativeImage(1, 1, false);
        try (var generator = new ClientPreviewGenerator(ignored -> {
            targetStarts.incrementAndGet();
            return use(firstLease);
        }, (request, lease) -> firstPixels, image -> TestPreviews.pending(), host, workers, null, request -> {
            throw failure;
        }, 1);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var first = generator.resolveLocal(source, true);
            var second = environment.export("pack/model", null);

            generator.tick();
            assertEquals(0, targetStarts.get());
            workers.runNext();
            generator.tick();
            assertEquals(1, targetStarts.get());
            assertFalse(second.isDone());

            firstLease.outcome = new AcquireResult.Ready(target());
            assertEquals(1, targetStarts.get());
            generator.tick();
            host.runNext();
            generator.tick();
            workers.runNext();

            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertEquals(1, targetStarts.get());

            generator.tick();
            workers.runNext();
            generator.tick();
            assertTrue(first.isDone());
            ((AutoCloseable)first.join()).close();
            assertEquals(1, targetStarts.get());
            assertFalse(second.isDone());
            assertEquals(1, workers.size());

            workers.runNext();
            assertFalse(second.isDone());
            generator.tick();
            assertSame(failure, assertThrows(CompletionException.class, second::join).getCause());
            assertClosed(firstPixels);
        } finally {
            source.representation().close();
        }
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
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS,
                new com.elfmcys.ysm.model.domain.ModelPath("fixture")));
        var store = new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("images"),
            com.elfmcys.ysm.testutil.DerivedStorageTestScope
                .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(temporary.resolve("images"))));
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
                }, 16)) {
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

    private static ClientPreviewGenerator.TargetUse ready(FakeLease lease) {
        lease.outcome = new AcquireResult.Ready(target());
        return use(lease);
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
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS,
                new com.elfmcys.ysm.model.domain.ModelPath("cold")));
        var lease = new FakeLease();
        var failure = new IllegalStateException("Actual host seam rejected draw");
        try (var generator = new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> {
            throw failure;
        }, pixels -> {
            throw new AssertionError("Failed host must not encode");
        }, Runnable::run, Runnable::run,
            new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("cold-cache"),
                com.elfmcys.ysm.testutil.DerivedStorageTestScope
                    .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(temporary.resolve("cold-cache")))),
            request -> {
                throw new AssertionError();
            }, 16)) {
            var result = generator.resolveEncoded(content, (identity, descriptors, receiver) -> {
                throw new AssertionError("Local input must not fetch");
            });
            for (int i = 0; i < 5; i++)
                generator.tick();
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
            assertEquals(1, lease.closes.get());
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
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS);
        var output = temporary.resolve("export-output.mxc");
        var host = new QueueExecutor();
        var lease = new FakeLease();
        try (var generator = new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> {
            throw new AssertionError("Retired export must not draw");
        }, pixels -> {
            throw new AssertionError();
        }, host, Runnable::run, null,
            request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request,
                new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("export-cache"),
                    com.elfmcys.ysm.testutil.DerivedStorageTestScope
                        .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(temporary.resolve("export-cache")))),
                output),
            16);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var result = environment.export("fixture", null);
            for (int i = 0; i < 3; i++)
                generator.tick();
            assertEquals(1, host.size());
            environment.close();
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
    void generatedExportPreviewRemainsAvailableInTheIndependentCache() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("generated-cache-raw"));
        var source =
            com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("generated-cache-source.mxc"),
                "fixture", 15, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS);
        var output = temporary.resolve("generated-cache-output.mxc");
        var observed = new ArrayList<Hash256>();
        var store = new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("generated-cache"),
            com.elfmcys.ysm.testutil.DerivedStorageTestScope
                .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(temporary.resolve("generated-cache"))),
            observed::add);
        var lease = new FakeLease();
        final byte[] expected;
        try (var encoded = TestPreviews.blank()) {
            expected = encoded.bytes();
        }
        try (
            var generator =
                new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> new NativeImage(1, 1, false),
                    image -> TestPreviews.pending(), Runnable::run, Runnable::run, store,
                    request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request, store, output), 16);
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
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS);
        var output = temporary.resolve("optional-cache-output.mxc");
        var cacheRoot = temporary.resolve("optional-cache");
        java.nio.file.Files.writeString(cacheRoot, "A file blocks cache directory creation");
        var store =
            new com.elfmcys.ysm.model.storage.PreviewStore(cacheRoot, com.elfmcys.ysm.testutil.DerivedStorageTestScope
                .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(cacheRoot)));
        var lease = new FakeLease();
        try (
            var generator =
                new ClientPreviewGenerator(ignored -> ready(lease), (request, acquired) -> new NativeImage(1, 1, false),
                    image -> TestPreviews.pending(), Runnable::run, Runnable::run, store,
                    request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request, store, output), 16);
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
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS);
        var output = temporary.resolve("warm-output.mxc");
        try (var generator = new ClientPreviewGenerator(ignored -> {
            throw new AssertionError("Warm export must not acquire");
        }, (request, acquired) -> {
            throw new AssertionError("Warm export must not draw");
        }, image -> {
            throw new AssertionError("Warm export must not encode");
        }, Runnable::run, Runnable::run, null,
            request -> com.elfmcys.ysm.model.service.ExportTestFixture.prepare(request,
                new com.elfmcys.ysm.model.storage.PreviewStore(temporary.resolve("warm-cache"),
                    com.elfmcys.ysm.testutil.DerivedStorageTestScope
                        .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(temporary.resolve("warm-cache")))),
                output),
            16);
            var environment = new com.elfmcys.ysm.model.service.ModelExportService.Environment(this, () -> true,
                () -> com.elfmcys.ysm.model.service.ExportTestFixture.catalog(java.util.List.of(source)),
                generator::export)) {
            var result = environment.export("fixture", "warm");
            generator.tick();
            assertTrue(java.nio.file.Files.isRegularFile(output));
            assertFalse(result.isDone());
            environment.close();
            assertEquals(output, result.join());
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
    void cancelledPreviewDoesNotEndAnotherLeaseOrItsTargetOwnedInput() throws Exception {
        var raw = com.elfmcys.ysm.model.service.ExportTestFixture.parse(temporary.resolve("independent-raw"));
        var source =
            com.elfmcys.ysm.model.service.ExportTestFixture.create(raw, temporary.resolve("independent-source.mxc"),
                "fixture", 14, com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS);
        var request = new ResourceRequest(source.modelId(), "player", "", new BakeProfile("test"));
        var host = new QueueExecutor();
        try (var resources = new com.elfmcys.ysm.model.resource.client.render.PreviewResourceFixture(source, request);
            var other = resources.acquire(); var generator = new ClientPreviewGenerator(
                ignored -> new ClientPreviewGenerator.TargetUse(request, resources.acquire()), (use, lease) -> {
                    throw new AssertionError("Cancelled preview must not draw");
                }, pixels -> {
                    throw new AssertionError();
                }, host, Runnable::run, null, ignored -> {
                    throw new AssertionError();
                }, 16)) {
            var preview = generator.resolveLocal(source, true);
            generator.tick();
            generator.tick();
            assertTrue(preview.cancel(false));
            resources.finishPreparation();
            var ready = org.junit.jupiter.api.Assertions.assertInstanceOf(AcquireResult.Ready.class, other.poll());
            assertTrue(ready.target().isStateCompatible(ready.target()));
            assertEquals(com.elfmcys.ysm.model.resource.client.render.PreviewResourceFixture.PIXEL,
                resources.readPixel());
            generator.close();
            assertSame(ready.target(),
                org.junit.jupiter.api.Assertions.assertInstanceOf(AcquireResult.Ready.class, other.poll()).target());
            assertEquals(com.elfmcys.ysm.model.resource.client.render.PreviewResourceFixture.PIXEL,
                resources.readPixel());
            assertEquals(0, host.size());
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
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS);
        var eager = new java.util.concurrent.atomic.AtomicBoolean();
        var renders = new AtomicInteger();
        var encodes = new AtomicInteger();
        try (var generator = new ClientPreviewGenerator(ignored -> ready(new FakeLease()), (request, acquired) -> {
            renders.incrementAndGet();
            return new NativeImage(1, 1, false);
        }, pixels -> {
            encodes.incrementAndGet();
            return TestPreviews.pending();
        }, Runnable::run, Runnable::run, null, request -> {
            throw new AssertionError();
        }, 16)) {
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
            com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS);
        try (var generator = new ClientPreviewGenerator(ignored -> ready(new FakeLease()), (request, acquired) -> {
            var image = new NativeImage(com.elfmcys.ysm.client.gui.PreviewRenderHost.WIDTH,
                com.elfmcys.ysm.client.gui.PreviewRenderHost.HEIGHT, false);
            for (var y = 0; y < image.getHeight(); y++)
                for (var x = 0; x < image.getWidth(); x++)
                    image.setPixelRGBA(x, y,
                        0xff000000 | (x * 17 & 255) | ((y * 23 & 255) << 8) | ((x + y & 255) << 16));
            return image;
        }, pixels -> com.elfmcys.ysm.model.storage.PreviewStore.pending(com.elfmcys.ysm.natives.image.ImageEncoder
            .encodeLossy(pixels, com.elfmcys.ysm.client.gui.PreviewRenderHost.WIDTH,
                com.elfmcys.ysm.client.gui.PreviewRenderHost.HEIGHT)),
            Runnable::run, Runnable::run, null, request -> {
                throw new AssertionError();
            }, 16)) {
            com.elfmcys.ysm.model.storage.PreviewDecodeProbe.reset();
            var output = generator.resolveLocal(source, true);
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
        }, 16);
    }

    private static ClientPreviewGenerator.TargetUse use(FakeLease lease) {
        return new ClientPreviewGenerator.TargetUse(REQUEST, lease);
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
            public ModelId modelId() {
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

    private static ModelId hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return ModelId.fromHash(new Hash256(bytes));
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
        private AcquireResult outcome = new AcquireResult.Pending();

        @Override
        public ModelResourcePurpose purpose() {
            return ModelResourcePurpose.FULL;
        }

        @Override
        public AcquireResult poll() {
            return closes.get() == 0 ? outcome : new AcquireResult.Ended();
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            return closes.get() == 0 && REQUEST.equals(request);
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
