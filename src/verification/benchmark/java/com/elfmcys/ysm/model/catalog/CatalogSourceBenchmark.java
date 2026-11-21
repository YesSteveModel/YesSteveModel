package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.model.catalog.source.*;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.storage.*;
import com.elfmcys.ysm.natives.NativeRuntime;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CatalogSourceBenchmark {
    @State(Scope.Thread)
    public static class Source {
        @Param({"directory", "zip", "7z"})
        public String kind;
        Path temporary;
        AtomicSharedCache cache;
        ConvertedSourceIndexStore indexes;
        ModelSourceResolver resolver;
        SourceObservation observation;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
            NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
            temporary = Files.createTempDirectory("ysm-source-benchmark-");
            var fixture = Path
                .of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/misc/1_alex/ysm.json")).toURI())
                .getParent();
            Path source;
            if (kind.equals("directory")) {
                source = temporary.resolve("model");
                try (var paths = Files.walk(fixture)) {
                    for (var path : paths.toList()) {
                        var target = source.resolve(fixture.relativize(path));
                        if (Files.isDirectory(path))
                            Files.createDirectories(target);
                        else
                            Files.copy(path, target);
                    }
                }
            } else if (kind.equals("zip")) {
                source = temporary.resolve("model.zip");
                try (var zip = new ZipOutputStream(Files.newOutputStream(source)); var paths = Files.walk(fixture)) {
                    for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                        zip.putNextEntry(new ZipEntry(fixture.relativize(path).toString().replace('\\', '/')));
                        Files.copy(path, zip);
                        zip.closeEntry();
                    }
                }
            } else {
                source = temporary.resolve("model.7z");
                try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/catalog-archives/solid.7z"))) {
                    Files.copy(input, source);
                }
            }
            cache = new AtomicSharedCache(temporary.resolve("cache"));
            indexes = new ConvertedSourceIndexStore(ConvertedScope.of(temporary, "source-benchmark"), cache);
            resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), indexes,
                new ConvertedObjectStore(cache));
            observation = ModelSourceDiscovery.observe(new ModelSourceKey(
                new CatalogRootIdentity(CatalogRootKind.MODELS, temporary),
                new ModelPath(source.getFileName().toString()),
                kind.equals("directory") ? ModelSourceKind.CURRENT_RAW_DIRECTORY : ModelSourceKind.CURRENT_RAW_ARCHIVE),
                source);
            try (var content = resolver.resolveMaterialized(observation).resolvedContent()) {
            }
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            if (cache != null)
                cache.close();
            var root = temporary.toAbsolutePath().normalize();
            if (!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
                throw new IllegalStateException("Benchmark cleanup escaped its temporary directory");
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    @State(Scope.Thread)
    public static class Warm extends Source {}

    @State(Scope.Thread)
    public static class Cold extends Source {
        @Setup(Level.Invocation)
        public void removeCandidate() throws Exception {
            indexes.replaceScopes(Set.of("models"), List.of());
        }
    }

    @Benchmark
    public ModelId warm(Warm input) throws Exception {
        try (var content = input.resolver.resolveMaterialized(input.observation).resolvedContent()) {
            return content.content().modelId();
        }
    }

    @Benchmark
    public ModelId cold(Cold input) throws Exception {
        try (var content = input.resolver.resolveMaterialized(input.observation).resolvedContent()) {
            return content.content().modelId();
        }
    }
}
