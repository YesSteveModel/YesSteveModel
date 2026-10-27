package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKey;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContentStorageBindingTest {
    @TempDir
    Path temp;
    private static final byte[] PNG = Base64.getDecoder()
        .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void observedSourcesConvertedObjectsPreviewsAndRemoteViewsKeepTheirRegion() throws Exception {
        var paths = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of("YSM_HOME", temp.resolve("global").toString()), AssetDirectoryPlatform.Kind.WINDOWS, null);
        assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
        var global = paths.global().orElseThrow();
        var custom = new CatalogRootIdentity(CatalogRootKind.CUSTOM, global.customRoot());
        var auth = new CatalogRootIdentity(CatalogRootKind.AUTH, global.authRoot());
        var local = new CatalogRootIdentity(CatalogRootKind.CUSTOM, paths.customModelsRoot());
        var builtin = new CatalogRootIdentity(CatalogRootKind.BUILTIN, paths.builtinModelsResource());
        var manifest = getClass().getResource("/assets/ysm/builtin/default/ysm.json");
        final Path fixture;
        try (var directory = new Directory(Path.of(Objects.requireNonNull(manifest).toURI()).getParent())) {
            fixture = ModelParser.parseBuiltinDefault(directory, Files.createDirectories(temp.resolve("fixture")));
        }
        final ModelFileIdentity identity;
        try (var verified = ManagedContainer.verifyFile(fixture)) {
            identity = verified.identity();
        }
        try (var storage = ModelStorageInfra.open(paths, "fixture-version")) {
            assertSame(storage.scope(custom), storage.scope(auth));
            assertNotEquals(storage.scope(custom).identity().root(), storage.scope(local).identity().root());
            assertSame(storage.local(), storage.scope(builtin).region());
            storage.registerConverted();
            var bound = new java.util.ArrayList<ManagedContainer>();
            try {
                for (var root : List.of(local, custom, auth, builtin)) {
                    var source = root.configuredAbsoluteRoot().resolve("same.mxc");
                    Files.createDirectories(source.getParent());
                    Files.copy(fixture, source);
                    var key = new ModelSourceKey(root, new ModelPath("same.mxc"), ModelSourceKind.DIRECT_CONTAINER);
                    var binding = storage.binding(ModelSourceDiscovery.observe(key, source));
                    assertSame(root.equals(custom) || root.equals(auth) ? storage.remote() : storage.local(),
                        binding.region());
                    var scope = storage.scope(root);
                    var location = new CatalogLocation(root.rootKind(), new ModelPath("same.mxc"));
                    try (var temporary = scope.objects().createTemporaryDirectory("fixture-")) {
                        var staged = temporary.path().resolve("fixture.mxc");
                        Files.copy(fixture, staged);
                        scope.objects().commit(new RawCompileResult(identity.modelId(), staged), location);
                    }
                    var object = scope.objects().objectPath(identity.modelId(), identity.containerId());
                    var content =
                        ManagedContainer.openIndexed(new CatalogIndexEntry(identity, location, object, binding));
                    bound.add(content);
                    assertEquals(binding, new RuntimeContentStore(ignored -> {
                    }).exact(content).storageBinding());
                    try (var image = com.elfmcys.ysm.model.storage.PreviewStorage.routed().forContent(content)
                        .accept(identity.containerId(), PNG)) {
                        assertArrayEquals(PNG, image.bytes());
                    }
                    try (var reopened = ManagedContainer.verifyFile(object)) {
                        assertEquals(identity, reopened.identity());
                    }
                }
                for (var region : storage.regions()) {
                    assertArrayEquals(PNG, Files
                        .readAllBytes(region.root().resolve("previews").resolve(identity.containerId() + ".image")));
                }
                try (var remote = RemoteModelStore.create(storage);
                    var prefix = bound.get(0).representation().metadataPrefix().orElseThrow()) {
                    remote.commitMetadataPrefix(identity, prefix);
                    var content = remote.probeMetadata(identity).orElseThrow();
                    try (var representation = content.representation()) {
                        assertSame(storage.remote(), content.storageBinding().region());
                        assertSame(storage.remote(), content.cachedOnlyView().storageBinding().region());
                        assertEquals(ContentStorageBinding.Kind.REMOTE, content.storageBinding().kind());
                    }
                    assertTrue(remote.root().startsWith(global.cacheRoot()));
                }
            } finally {
                bound.forEach(content -> content.representation().close());
            }
        }
    }
}
