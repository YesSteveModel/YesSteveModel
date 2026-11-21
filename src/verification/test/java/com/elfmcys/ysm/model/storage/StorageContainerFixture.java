package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

final class StorageContainerFixture {
    private static byte[] bytes;

    static synchronized byte[] bytes() throws Exception {
        if (bytes == null) {
            var source = Path.of(Objects
                .requireNonNull(StorageContainerFixture.class.getResource("/assets/ysm/builtin/default/ysm.json"))
                .toURI()).getParent();
            var root = Files.createTempDirectory("ysm-storage-fixture-");
            try (var vfs = new Directory(source)) {
                var file = ModelParser.parseBuiltinDefault(vfs, root);
                bytes = Files.readAllBytes(file);
            } finally {
                try (var files = Files.walk(root)) {
                    for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.delete(file);
                }
            }
        }
        return bytes.clone();
    }

    static ConvertedSourceIndex produce(ConvertedScope scope, AtomicSharedCache cache, String source) throws Exception {
        var objects = new ConvertedObjectStore(cache);
        try (var temporary = objects.stage()) {
            temporary.file().writeBytes(bytes());
            try (var verified = ManagedContainer.verifyCachedFile(temporary.file())) {
                var stored = objects.commit(new RawCompileResult(verified.identity().modelId(), temporary.path()),
                    new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("fixture")), temporary);
                return new ConvertedSourceIndex(stored.identity(), source, scope.version(), stored.location(),
                    stored.file(), com.elfmcys.ysm.buffer.ArrayBuffer.move(new byte[] {1}).shareArray(),
                    stored.metadata());
            }
        }
    }
}
