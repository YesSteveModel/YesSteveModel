package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.format.legacy.LegacyContainerHeader;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKey;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.catalog.source.SourceObservation;
import com.elfmcys.ysm.model.catalog.source.SourceStamp;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LegacyModelImporterCorpusTest {
    @Test
    void stagesReopensAndReadsConfiguredCorpus(@TempDir Path temporary) throws Exception {
        var configured = System.getenv("YSM_LEGACY_CORPUS");
        assumeTrue(configured != null && !configured.isBlank());
        var root = Path.of(configured);
        assertTrue(Files.isDirectory(root), "configured corpus directory");

        final List<Path> sources;
        try (var files = Files.walk(root)) {
            sources = files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".ysm"))
                .sorted().toList();
        }
        assertFalse(sources.isEmpty(), "configured corpus must contain models");
        var cache = temporary.resolve("cache");
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
            new ConvertedSourceIndexStore(cache, "test-version"), new ConvertedObjectStore(cache));
        var ordinal = 0;
        for (var source : sources) {
            final ModelSourceResolver.Resolution routed;
            try {
                routed = resolver.resolve(observation(root, source, ++ordinal));
            } catch (RuntimeException failure) {
                throw new AssertionError("Failed legacy corpus source: " + source, failure);
            }
            assertTrue(routed.convertedIndexEntry().isPresent(), source.toString());
            assertTrue(Files.isRegularFile(routed.entry().backingFile()), source.toString());
            var reopened = ManagedContainer.openIndexed(routed.entry());
            try {
                var chunks = reopened.view().getFileView().getAssetView().getChunkTable();
                assertFalse(chunks.isEmpty(), source.toString());
                System.out.printf("LEGACY_CORPUS source=%s modelId=%s containerId=%s chunks=%d%n",
                    root.relativize(source), reopened.modelId(), reopened.representation().identity().containerId(),
                    chunks.size());
            } finally {
                reopened.representation().close();
            }
        }
    }

    private static SourceObservation observation(Path root, Path source, int ordinal) throws Exception {
        var rootIdentity = new CatalogRootIdentity(CatalogRootKind.CUSTOM, root);
        var target = source.toRealPath();
        var stamp = SourceStamp.captureFile(target);
        var sourceKind = switch (LegacyContainerHeader.probe(source)) {
            case V1_RAW, V2_RAW -> ModelSourceKind.CURRENT_RAW_ARCHIVE;
            case V3_ENCRYPTED -> ModelSourceKind.LEGACY_ARCHIVE;
            case UNSUPPORTED -> ModelSourceKind.UNSUPPORTED_YSM;
        };
        return new SourceObservation(
            new ModelSourceKey(rootIdentity, new ModelPath("corpus/" + ordinal + ".ysm"), sourceKind), source, target, stamp,
            Files.size(source));
    }
}
