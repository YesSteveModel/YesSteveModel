package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.CatalogAddressResolver;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModelExportServiceTest {
    @TempDir
    static Path shared;
    @TempDir
    Path temp;
    static Path raw;
    final List<ManagedContainer> sources = new ArrayList<>();

    @BeforeAll
    static void parse() throws Exception {
        raw = ExportTestFixture.parse(shared.resolve("raw"));
    }

    @AfterEach
    void release() {
        sources.forEach(value -> value.representation().close());
    }

    ManagedContainer model(String path, int marker, PreviewSource preview, CatalogRootKind kind) throws Exception {
        var result = ExportTestFixture.create(raw, temp.resolve("sources/" + sources.size() + ".mxc"), path, marker,
            preview, kind);
        sources.add(result);
        return result;
    }

    @Test
    void directArtifactSuffixIsStable() {
        assertEquals("pack/model.mxc", ModelExportService.withDirectSuffix("pack/model.mxc"));
        assertEquals("pack/model.MXC", ModelExportService.withDirectSuffix("pack/model.MXC"));
        assertEquals("pack/model.ysm.mxc", ModelExportService.withDirectSuffix("pack/model.ysm"));
        assertEquals("pack/model.mxc", ModelExportService.withDirectSuffix("pack/model"));
    }

    @Test
    void catalogNamespacesMapToSeparateHostDirectoriesWithoutColons() throws Exception {
        var root = temp.resolve("export");
        for (var scope : List.of("global", "local"))
            for (var kind : List.of("custom", "auth"))
                assertEquals(root.resolve(scope + "/" + kind + "/pack/model.mxc"),
                    ModelExportService.outputPath(root, scope + ":" + kind + "/pack/model"));
        assertThrows(java.io.IOException.class, () -> ModelExportService.outputPath(root, "default"));
        assertThrows(java.io.IOException.class, () -> ModelExportService.outputPath(root, "builtin:other"));
        assertThrows(IllegalArgumentException.class,
            () -> ModelExportService.outputPath(root, "local:custom/../outside"));
    }

    @Test
    void exactEnvironmentRefusesRetiredOrOtherHostBeforeSourceLookup() {
        var owner = new Object();
        var active = new java.util.concurrent.atomic.AtomicBoolean(false);
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        try (var environment = new ModelExportService.Environment(owner, active::get, () -> {
            lookups.incrementAndGet();
            throw new AssertionError();
        }, request -> {
            throw new AssertionError();
        })) {
            assertThrows(IllegalStateException.class, () -> environment.export("fixture", null));
            active.set(true);
            assertThrows(IllegalStateException.class, () -> environment.require(new Object()));
            assertEquals(0, lookups.get());
        }
    }

    @Test
    void fixedSelectionDoesNotFollowAdditionRemovalOrReplacementAndRejectsBusy() throws Exception {
        var a = model("pack/a.mxc", 1, PreviewSource.RAW, CatalogRootKind.CUSTOM);
        var b = model("pack/sub/b.mxc", 2, PreviewSource.GENERATED, CatalogRootKind.AUTH);
        var c = model("model-😀.mxc", 3, PreviewSource.UNSPECIFIED, CatalogRootKind.CUSTOM);
        var builtin = model("builtin", 4, PreviewSource.RAW, CatalogRootKind.BUILTIN);
        var current = new AtomicReference<>(ExportTestFixture.catalog(List.of(a, b, c, builtin)));
        var requests = new ArrayList<ModelExportService.Request>();
        var outcomes = new ArrayList<CompletableFuture<Path>>();
        var summaries = new ArrayList<ModelWorkSummary>();
        try (var environment = new ModelExportService.Environment(this, () -> true, current::get, request -> {
            requests.add(request);
            var outcome = new CompletableFuture<Path>();
            outcomes.add(outcome);
            return outcome;
        })) {
            assertTrue(environment.exportAll(null, ignored -> {
            }, summaries::add));
            assertFalse(environment.exportAll("*", ignored -> fail(), ignored -> fail()));
            environment.tick();
            assertEquals(1, requests.size());
            var started = current.get().findPath(requests.get(0).path()).orElseThrow();
            var replaced = List.of(a, b, c).stream().filter(value -> value != started).findFirst().orElseThrow();
            var replacement = model(replaced.location().path().value(), replaced == a ? 1 : replaced == b ? 2 : 3,
                PreviewSource.RAW, CatalogRootKind.CUSTOM);
            // Ensure different bytes even when the original also had an author image.
            if (replacement.representation().identity().equals(replaced.representation().identity())) {
                replacement = model(replaced.location().path().value(), replaced == a ? 1 : replaced == b ? 2 : 3,
                    PreviewSource.GENERATED, CatalogRootKind.CUSTOM);
            }
            var added = model("added.mxc", 5, PreviewSource.RAW, CatalogRootKind.CUSTOM);
            current.set(ExportTestFixture.catalog(List.of(started, replacement, added, builtin)));
            outcomes.get(0).complete(temp.resolve("first.mxc"));
            for (int i = 0; i < 5; i++)
                environment.tick();
            assertEquals(1, requests.size());
            var summary = summaries.get(0);
            assertEquals(3, summary.total());
            assertEquals(1, summary.success());
            assertEquals(2, summary.skipped());
            assertEquals(0, summary.unprocessed());
            assertFalse(summary.stopped());
            assertEquals(Set.of("local:custom/pack/a.mxc", "local:auth/pack/sub/b.mxc", "local:custom/model-😀.mxc"),
                summary.items().stream().map(ModelWorkSummary.Item::path).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void emptyPatternDoesNotBecomeAllAndBuiltinSingleIsRejected() throws Exception {
        var a = model("fixture", 1, PreviewSource.RAW, CatalogRootKind.CUSTOM);
        var builtin = model("builtin", 2, PreviewSource.RAW, CatalogRootKind.BUILTIN);
        var summaries = new ArrayList<ModelWorkSummary>();
        try (var environment = new ModelExportService.Environment(this, () -> true,
            () -> ExportTestFixture.catalog(List.of(a, builtin)), request -> {
                throw new AssertionError();
            })) {
            assertTrue(environment.exportAll("", ignored -> fail(), summaries::add));
            environment.tick();
            assertEquals(0, summaries.get(0).total());
            assertThrows(java.util.concurrent.CompletionException.class,
                () -> environment.export("builtin", null).join());
        }
    }

    @Test
    void catalogGlobSelectionMatchesIndependentArtifactSets() throws Exception {
        var paths = List.of("pack/a.mxc", "pack/sub/b.mxc", "Pack/a.mxc", "model-a.mxc", "model-猫.mxc", "model-😀.mxc",
            "model-ab.mxc", "a/b", "a.b", "[a]");
        var published = new ArrayList<ManagedContainer>();
        for (int i = 0; i < paths.size(); i++)
            published.add(model(paths.get(i), i + 1, PreviewSource.RAW, CatalogRootKind.CUSTOM));
        published.add(model("pack/builtin.mxc", 30, PreviewSource.RAW, CatalogRootKind.BUILTIN));
        model("pack/unadmitted.mxc", 31, PreviewSource.UNSPECIFIED, CatalogRootKind.CUSTOM);
        var patterns = new String[] {null, "local:custom/pack/*", "local:custom/model-?.mxc", "local:custom/a?b",
            "local:custom/a.b", "local:custom/[a]", "", "no-match", "local:custom/pack/**", "local:custom/Pack/*",
            "local:custom/model-😀.mxc"};
        var expected =
            List.of(paths.stream().map(path -> "local:custom/" + path).collect(java.util.stream.Collectors.toSet()),
                Set.of("local:custom/pack/a.mxc", "local:custom/pack/sub/b.mxc"),
                Set.of("local:custom/model-a.mxc", "local:custom/model-猫.mxc", "local:custom/model-😀.mxc"),
                Set.of("local:custom/a/b", "local:custom/a.b"), Set.of("local:custom/a.b"), Set.of("local:custom/[a]"),
                Set.<String>of(), Set.<String>of(), Set.of("local:custom/pack/a.mxc", "local:custom/pack/sub/b.mxc"),
                Set.of("local:custom/Pack/a.mxc"), Set.of("local:custom/model-😀.mxc"));
        var catalog = ExportTestFixture.catalog(published);
        for (int index = 0; index < patterns.length; index++) {
            var directory = temp.resolve("selected/" + index);
            var summaries = new ArrayList<ModelWorkSummary>();
            try (var environment = new ModelExportService.Environment(this, () -> true, () -> catalog, request -> {
                ModelExportService.PreparedExport prepared = null;
                try {
                    prepared = ModelExportService.prepare(request, new PreviewStore(temp.resolve("glob-cache")),
                        directory.resolve("artifact-"
                            + paths.indexOf(
                                com.elfmcys.ysm.model.catalog.source.CatalogLocation.parse(request.path()).entry())
                            + ".mxc"));
                    assertNotNull(prepared.completed());
                    return CompletableFuture.completedFuture(prepared.completed());
                } catch (Exception failure) {
                    return CompletableFuture.failedFuture(failure);
                } finally {
                    if (prepared != null)
                        prepared.closeOwned(null);
                }
            })) {
                assertTrue(environment.exportAll(patterns[index], ignored -> {
                }, summaries::add));
                for (int tick = 0; tick < paths.size() + 2; tick++)
                    environment.tick();
                var summary = summaries.get(0);
                assertEquals(expected.get(index), summary.items().stream().map(ModelWorkSummary.Item::path)
                    .collect(java.util.stream.Collectors.toSet()), "pattern=" + patterns[index]);
                assertEquals(expected.get(index).size(), summary.success());
                if (expected.get(index).isEmpty())
                    assertFalse(Files.exists(directory));
                else
                    try (var outputs = Files.walk(directory)) {
                        assertEquals(
                            expected.get(index).stream()
                                .map(path -> "artifact-" + paths.indexOf(path.substring("local:custom/".length()))
                                    + ".mxc")
                                .collect(java.util.stream.Collectors.toSet()),
                            outputs.filter(Files::isRegularFile).map(directory::relativize).map(Path::toString)
                                .map(value -> value.replace('\\', '/')).collect(java.util.stream.Collectors.toSet()));
                    }
            }
        }
    }

    @Test
    void mixedRealArtifactsContinueAfterUnreadableSourceAndReopenIndependently() throws Exception {
        var author = model("author", 1, PreviewSource.RAW, CatalogRootKind.CUSTOM);
        var cached = model("cached", 2, PreviewSource.UNSPECIFIED, CatalogRootKind.CUSTOM);
        var cold = model("cold", 3, PreviewSource.UNSPECIFIED, CatalogRootKind.AUTH);
        var broken = model("broken", 4, PreviewSource.UNSPECIFIED, CatalogRootKind.CUSTOM);
        var catalog = ExportTestFixture.catalog(List.of(author, cached, cold, broken));
        var previews = new PreviewStore(temp.resolve("cache"));
        try (var image = TestPreviews.blank()) {
            previews.accept(cached.representation().containerId(), image.bytes()).close();
        }
        var oldOutput = temp.resolve("outputs/broken.mxc");
        Files.createDirectories(oldOutput.getParent());
        try (var image = TestPreviews.blank()) {
            ModelExporter.export(author, image, oldOutput, "old");
        }
        var oldBytes = Files.readAllBytes(oldOutput);
        Files.delete(broken.file());
        var summaries = new ArrayList<ModelWorkSummary>();
        try (var environment = new ModelExportService.Environment(this, () -> true, () -> catalog, request -> {
            ModelExportService.PreparedExport prepared = null;
            try {
                prepared = ModelExportService.prepare(request, previews, temp.resolve("outputs/"
                    + com.elfmcys.ysm.model.catalog.source.CatalogLocation.parse(request.path()).entry() + ".mxc"));
                var output = prepared.completed();
                if (output == null)
                    try (var generated = TestPreviews.blank()) {
                        output = ModelExportService.finish(prepared, generated);
                    }
                return CompletableFuture.completedFuture(output);
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            } finally {
                if (prepared != null)
                    prepared.closeOwned(null);
            }
        })) {
            assertTrue(environment.exportAll(null, ignored -> {
            }, summaries::add));
            for (int i = 0; i < 10; i++)
                environment.tick();
            var summary = summaries.get(0);
            assertEquals(4, summary.total());
            assertEquals(3, summary.success());
            assertEquals(1, summary.failed());
            assertArrayEquals(oldBytes, Files.readAllBytes(oldOutput));
            for (var source : List.of(author, cached, cold)) {
                var exported = ManagedContainer.openDirect(
                    temp.resolve("outputs/" + source.location().path().value() + ".mxc"), source.location());
                try {
                    DirectContainerAdmission.requireEmbeddedPreview(exported);
                    assertEquals(source.representation().modelId(), exported.representation().modelId());
                    assertNotEquals(source.representation().containerId(), exported.representation().containerId());
                    assertEquals(source.modelFile().getManifest().renderTargets(),
                        exported.modelFile().getManifest().renderTargets());
                    assertEquals(source.modelFile().getManifest().info().metadataUnsafe(),
                        exported.modelFile().getManifest().info().metadataUnsafe());
                    assertEquals(source == author ? PreviewSource.RAW : PreviewSource.GENERATED,
                        exported.modelFile().getThumbnailPreviewSource());
                } finally {
                    exported.representation().close();
                }
            }
        }
    }

    @Test
    void unpublishedDirectIsRejectedBeforeExportWorkWithOrWithoutPreview() throws Exception {
        var hidden = model("hidden", 1, PreviewSource.UNSPECIFIED, CatalogRootKind.CUSTOM);
        var complete = model("complete", 2, PreviewSource.RAW, CatalogRootKind.CUSTOM);
        var root = temp.resolve("direct");
        Files.createDirectories(root);
        Files.copy(hidden.file(), root.resolve("hidden.mxc"));
        Files.copy(complete.file(), root.resolve("complete.mxc"));
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var summaries = new ArrayList<ModelWorkSummary>();
        try (var environment = new ModelExportService.Environment(this, () -> true,
            () -> ExportTestFixture.catalog(List.of()), request -> {
                starts.incrementAndGet();
                return CompletableFuture.completedFuture(temp.resolve("out/hidden.mxc"));
            })) {
            assertTrue(environment.exportAll(null, ignored -> fail(), summaries::add));
            environment.tick();
            assertEquals(0, summaries.get(0).total());
            for (var name : List.of("hidden.mxc", "complete.mxc")) {
                var bytes = Files.readAllBytes(root.resolve(name));
                for (var prefix : List.of("", "local:custom/", "local:auth/", "global:custom/", "global:auth/")) {
                    var failure = assertThrows(java.util.concurrent.CompletionException.class,
                        () -> environment.export(prefix + name, null).join());
                    assertEquals(CatalogAddressResolver.Status.NOT_FOUND,
                        assertInstanceOf(CatalogAddressResolver.Failure.class, failure.getCause()).status());
                }
                assertArrayEquals(bytes, Files.readAllBytes(root.resolve(name)));
            }
            assertEquals(0, starts.get());
            assertFalse(Files.exists(temp.resolve("out")));
        }
    }

    @Test
    void admittedExactItemDoesNotReopenCurrentSamePathRepresentation() throws Exception {
        var original = model("fixture", 1, PreviewSource.RAW, CatalogRootKind.CUSTOM);
        var replacement = model("fixture", 1, PreviewSource.GENERATED, CatalogRootKind.CUSTOM);
        var current = new AtomicReference<>(ExportTestFixture.catalog(List.of(original)));
        var captured = new AtomicReference<ModelExportService.Request>();
        var running = new CompletableFuture<Path>();
        try (var environment = new ModelExportService.Environment(this, () -> true, current::get, request -> {
            captured.set(request);
            return running;
        })) {
            var result = environment.export("fixture", "exact");
            current.set(ExportTestFixture.catalog(List.of(replacement)));
            var prepared = ModelExportService.prepare(captured.get(), new PreviewStore(temp.resolve("cache")),
                temp.resolve("exact.mxc"));
            try {
                running.complete(prepared.completed());
                var exported = ManagedContainer.openDirect(result.join(), original.location());
                try {
                    assertEquals(PreviewSource.RAW, exported.modelFile().getThumbnailPreviewSource());
                } finally {
                    exported.representation().close();
                }
            } finally {
                prepared.closeOwned(null);
            }
        }
    }

    @Test
    void admittedSourceFileChangeFailsRatherThanSubstitutingCatalogInput() throws Exception {
        var original = model("fixture", 1, PreviewSource.RAW, CatalogRootKind.CUSTOM);
        var replacement = model("fixture", 1, PreviewSource.GENERATED, CatalogRootKind.CUSTOM);
        var current = new AtomicReference<>(ExportTestFixture.catalog(List.of(original)));
        var captured = new AtomicReference<ModelExportService.Request>();
        var running = new CompletableFuture<Path>();
        try (var environment = new ModelExportService.Environment(this, () -> true, current::get, request -> {
            captured.set(request);
            return running;
        })) {
            environment.export("fixture", null);
            current.set(ExportTestFixture.catalog(List.of(replacement)));
            Files.copy(replacement.file(), original.file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertThrows(java.io.IOException.class, () -> ModelExportService.prepare(captured.get(),
                new PreviewStore(temp.resolve("cache")), temp.resolve("must-not-exist.mxc")));
            assertFalse(Files.exists(temp.resolve("must-not-exist.mxc")));
        }
    }

    @Test
    void environmentStopAccountsCurrentCancellationAndRemainingItemsWithoutContinuation() throws Exception {
        var models = List.of(model("a", 1, PreviewSource.RAW, CatalogRootKind.CUSTOM),
            model("b", 2, PreviewSource.RAW, CatalogRootKind.CUSTOM),
            model("c", 3, PreviewSource.RAW, CatalogRootKind.CUSTOM));
        var running = new CompletableFuture<Path>();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var summaries = new ArrayList<ModelWorkSummary>();
        var environment =
            new ModelExportService.Environment(this, () -> true, () -> ExportTestFixture.catalog(models), request -> {
                starts.incrementAndGet();
                return running;
            });
        assertTrue(environment.exportAll(null, ignored -> {
        }, summaries::add));
        environment.tick();
        environment.close();
        environment.tick();
        environment.close();
        assertEquals(1, starts.get());
        assertTrue(running.isCancelled());
        var summary = summaries.get(0);
        assertEquals(3, summary.total());
        assertEquals(1, summary.failed());
        assertEquals(2, summary.unprocessed());
        assertTrue(summary.stopped());
        assertEquals(1, summaries.size());
        assertFalse(running.complete(temp.resolve("late.mxc")));
    }

    @Test
    void closeAfterStagingSealsActualMoveWaitsOwnedWorkerAndPreservesPriorOutput() throws Exception {
        var source = model("fixture", 1, PreviewSource.RAW, CatalogRootKind.CUSTOM);
        var output = temp.resolve("output.mxc");
        try (var image = TestPreviews.blank()) {
            ModelExporter.export(source, image, output, "old");
        }
        var old = Files.readAllBytes(output);
        var staged = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var environment = new ModelExportService.Environment(this, () -> true,
            () -> ExportTestFixture.catalog(List.of(source)), request -> {
                assertTrue(request.beginWorker());
                return CompletableFuture.supplyAsync(() -> {
                    try (var image = TestPreviews.blank()) {
                        return ModelExporter.export(source, image, output, "new", replacement -> {
                            staged.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException failure) {
                                throw new AssertionError(failure);
                            }
                            request.commit(output, replacement);
                        });
                    } catch (Exception failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    } finally {
                        request.endWorker();
                    }
                }, executor);
            });
        try {
            var result = environment.export("fixture", null);
            result.whenComplete((ignored, failure) -> {
                if (failure != null)
                    cancelled.countDown();
            });
            assertTrue(staged.await(5, TimeUnit.SECONDS));
            var closed = CompletableFuture.runAsync(environment::close);
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertFalse(closed.isDone());
            release.countDown();
            closed.get(5, TimeUnit.SECONDS);
            assertTrue(result.isCompletedExceptionally());
            assertArrayEquals(old, Files.readAllBytes(output));
            try (var files = Files.list(temp)) {
                assertTrue(files.noneMatch(file -> file.getFileName().toString().contains(".tmp-")));
            }
            assertThrows(IllegalStateException.class, () -> environment.export("fixture", null));
        } finally {
            release.countDown();
            environment.close();
            executor.shutdownNow();
        }
    }

    @Test
    void committedOutputSurvivesFeedbackFailureAndEnvironmentStop() throws Exception {
        var source = model("fixture", 1, PreviewSource.RAW, CatalogRootKind.CUSTOM);
        var output = temp.resolve("output.mxc");
        var late = new CompletableFuture<Path>();
        var environment = new ModelExportService.Environment(this, () -> true,
            () -> ExportTestFixture.catalog(List.of(source)), request -> {
                try (var image = TestPreviews.blank()) {
                    ModelExporter.export(source, image, output, null,
                        replacement -> request.commit(output, replacement));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return late;
            });
        var result = environment.export("fixture", null);
        environment.close();
        assertEquals(output, result.join());
        assertTrue(Files.isRegularFile(output));
        assertFalse(late.completeExceptionally(new IllegalStateException("late feedback")));
    }
}
