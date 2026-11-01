package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.client.texture.StandaloneImageTestHost;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.ClientCatalogScenarioHarness;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.storage.TestPreviews;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Supplies real catalog image chunks and native pixels, with only the GL host replaced. */
public final class CatalogFrameAssets implements AutoCloseable {
    private final ClientCatalogScenarioHarness catalogs;
    private final ClientAssetRepository repository;
    public final ClientCatalogEntry entry;
    public final StandaloneImageTestHost host = new StandaloneImageTestHost();
    public final AtomicInteger decodes = new AtomicInteger();
    public Runnable decoding = () -> {};
    public Runnable registering = () -> {};

    public CatalogFrameAssets(Path root) throws Exception {
        var intrinsic = parse("/assets/ysm/builtin/default/ysm.json", root.resolve("default"), true);
        var parsed = parse("/assets/ysm/builtin/wine_fox/22_elf/ysm.json", root.resolve("model"), false);
        var sourceContent = ManagedContainer.openDirect(parsed,
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("source")));
        Path model;
        var id = sourceContent.modelId();
        try (var preview = TestPreviews.blank()) {
            model = ModelExporter.export(sourceContent, preview, root.resolve("card.mxc"), null);
        } finally {
            sourceContent.representation().close();
        }
        catalogs = ClientCatalogScenarioHarness.open(root.resolve("catalog"), intrinsic, List.of(model));
        entry = catalogs.manager().snapshot().find(id).orElseThrow(() ->
            new AssertionError("Local card source was not admitted: " + catalogs.manager().localState()));
        repository = new ClientAssetRepository(catalogs.manager(), () -> (members, receiver) ->
            CompletableFuture.failedFuture(new AssertionError("Local frame must not download")),
            content -> content, null, Runnable::run, content -> new CompletableFuture<>(),
            content -> CompletableFuture.completedFuture(java.util.Optional.empty()), () -> null);
        var resident = new ResidentImages(new ResidentImages.Host() {
            public CustomTexture create(PreviewStore.EncodedPreview encoded) { return host.create(encoded); }
            public TextureHolder register(CustomTexture texture) {
                var holder = host.register(texture);
                registering.run();
                return holder;
            }
            public CustomTextureManager.Registration observe(CustomTexture texture, Runnable invalidated) {
                return host.observe(texture, invalidated);
            }
            public void release(CustomTexture texture) { host.release(texture); }
        }, source -> {
            decodes.incrementAndGet();
            decoding.run();
            return PreviewStore.prepareTexture(source);
        });
        // The repository and card retain their production owners; only resource supply uses this host.
        var images = ClientAssetRepository.class.getDeclaredField("images");
        images.setAccessible(true);
        images.set(repository, resident);
    }

    public ClientAssetBatch batch() { return new ClientAssetBatch(repository.openBatch()); }

    private static Path parse(String resource, Path output, boolean builtin) throws Exception {
        var source = Path.of(CatalogFrameAssets.class.getResource(resource).toURI()).getParent();
        Files.createDirectories(output);
        try (var vfs = new Directory(source)) {
            return builtin ? ModelParser.parseBuiltinDefault(vfs, output)
                : ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll());
        }
    }

    @Override public void close() {
        catalogs.close();
        host.tick();
        host.tick();
    }
}
