package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime.BytesLease;
import com.elfmcys.ysm.model.service.ServerModelService;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class CatalogLinksPreviewProbe {
    private final Hash256 identity = Hash256.parse("abababababababababababababababababababababababababababababababab");
    private final byte[] oldBytes = {1, 3, 5, 7};
    private final byte[] newBytes = {2, 4, 6, 8};
    private CompletableFuture<java.util.Optional<BytesLease>> read;
    private CompletableFuture<ServerModelService.ReloadOutcome> reload;
    private BytesLease retained;
    private Path file;
    private int phase;

    Map<String, ?> poll() throws Exception {
        var system = ModelRuntime.system();
        var runtime = system.serverResources();
        if (phase == 0) {
            var field = system.previews().getClass().getDeclaredField("cacheRoot");
            field.setAccessible(true);
            file = ((Path)field.get(system.previews())).resolve("previews").resolve(identity + ".image");
            Files.createDirectories(file.getParent());
            replace(oldBytes);
            runtime.invalidatePreview(identity);
            read = runtime.acquirePreview(identity);
            phase = 1;
        }
        if (phase == 1) {
            if (!read.isDone())
                return null;
            retained = read.join().orElseThrow();
            requireBytes(oldBytes, retained);
            replace(newBytes);
            read = runtime.acquirePreview(identity);
            phase = 2;
        }
        if (phase == 2) {
            if (!read.isDone())
                return null;
            try (var beforeReload = read.join().orElseThrow()) {
                requireBytes(oldBytes, beforeReload);
            }
            reload = ServerModelService.instance().reload();
            phase = 3;
        }
        if (phase == 3) {
            if (!reload.isDone())
                return null;
            if (!reload.join().success())
                throw new IllegalStateException("Preview verification reload failed");
            read = runtime.acquirePreview(identity);
            phase = 4;
        }
        if (!read.isDone())
            return null;
        try (var old = retained; var refreshed = read.join().orElseThrow()) {
            requireBytes(newBytes, refreshed);
            requireBytes(oldBytes, retained);
        } finally {
            Files.deleteIfExists(file);
            runtime.invalidatePreview(identity);
        }
        return Map.of("retainedBeforeReload", true, "invalidatedAfterServiceReload", true,
            "oldLeaseStillReadable", true, "mediaDecoded", false);
    }

    private void replace(byte[] bytes) throws Exception {
        var staged = Files.write(file.resolveSibling("catalog-links-preview.stage"), bytes);
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void requireBytes(byte[] expected, BytesLease lease) {
        var buffer = ByteBuffer.allocate(lease.size());
        lease.copyTo(0, buffer);
        if (!Arrays.equals(expected, buffer.array()))
            throw new IllegalStateException("Preview lease bytes differ from the fixed fixture");
    }
}
