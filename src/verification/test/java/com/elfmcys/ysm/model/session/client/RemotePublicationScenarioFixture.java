package com.elfmcys.ysm.model.session.client;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.remote.RemoteMetadataFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.natives.Blake3;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Test-only real-container corpus and source Adapter for the remote scenario. */
public final class RemotePublicationScenarioFixture {
    private final Map<String, Asset> assets;
    private final Map<ModelFileIdentity, Asset> byIdentity;
    private final SourceFetcher fetcher;

    public static RemotePublicationScenarioFixture create(Path root) throws Exception {
        var assets = new LinkedHashMap<String, Asset>();
        assets.put("default", parse(root, "default", "/assets/ysm/builtin/default/ysm.json", true));
        assets.put("active-exact", parse(root, "active-exact", "/assets/ysm/builtin/misc/1_alex/ysm.json", false));
        assets.put("same-model-local",
            parse(root, "same-model-local", "/assets/ysm/builtin/misc/2_steve/ysm.json", false));
        assets.put("local-exact", parse(root, "local-exact", "/assets/ysm/builtin/misc/3_default_boy/ysm.json", false));
        assets.put("cache-exact",
            parse(root, "cache-exact", "/assets/ysm/builtin/misc/4_default_controllers/ysm.json", false));
        assets.put("server-exact",
            parse(root, "server-exact", "/assets/ysm/builtin/wine_fox/01_taisho_maid/ysm.json", false));
        assets.put("server-failure",
            parse(root, "server-failure", "/assets/ysm/builtin/wine_fox/02_new_year/ysm.json", false));
        assets.put("online-add",
            parse(root, "online-add", "/assets/ysm/builtin/wine_fox/03_astronaut/ysm.json", false));
        assets.put("presentation-rich",
            parse(root, "presentation-rich", "/assets/ysm/builtin/wine_fox/22_elf/ysm.json", false));
        assets.put("same-model-remote", repack(root, assets.get("same-model-local"), "same-model-remote", (byte)'R'));
        assets.put("replacement-old", repack(root, assets.get("server-exact"), "replacement-old", (byte)'O'));
        assets.put("replacement-new", repack(root, assets.get("server-exact"), "replacement-new", (byte)'N'));
        return new RemotePublicationScenarioFixture(assets);
    }

    private RemotePublicationScenarioFixture(Map<String, Asset> source) {
        assets = Map.copyOf(source);
        var indexed = new LinkedHashMap<ModelFileIdentity, Asset>();
        source.values().forEach(asset -> indexed.put(asset.identity(), asset));
        byIdentity = Map.copyOf(indexed);
        fetcher = new SourceFetcher(byIdentity);
        fetcher.fail(asset("server-failure").identity());
        fetcher.hold(asset("replacement-old").identity());
    }

    public Asset asset(String name) {
        var asset = assets.get(name);
        if (asset == null) {
            throw new IllegalArgumentException("Unknown scenario asset: " + name);
        }
        return asset;
    }

    public RemoteMetadataFetcher fetcher() {
        return fetcher;
    }

    public List<SourceRequest> sourceRequests() {
        return fetcher.requests();
    }

    public int sourceRequestCount() {
        return fetcher.requests().size();
    }

    public boolean completeHeld(String assetName) throws Exception {
        return fetcher.completeHeld(asset(assetName).identity());
    }

    public void seed(RemoteModelStore store, String assetName) throws Exception {
        var asset = asset(assetName);
        {
            var prefix = asset.content().representation().metadataPrefix().orElseThrow();
            store.commitMetadataPrefix(asset.identity(), prefix);
        }
    }

    public InaccessibleCandidate inaccessibleLocalCandidate(Path root, String assetName) throws Exception {
        var asset = asset(assetName);
        var file =
            Files.copy(asset.file(), root.resolve("inaccessible-local.mxc"), StandardCopyOption.REPLACE_EXISTING);
        var entry = new CatalogIndexEntry(asset.identity(),
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("inaccessible-local")), file);
        var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) {
            throw new AssertionError("Recorded platform must expose a Windows ACL view");
        }
        var original = List.copyOf(view.getAcl());
        var deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(file))
            .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES).build();
        var denied = new ArrayList<AclEntry>(original.size() + 1);
        denied.add(deny);
        denied.addAll(original);
        view.setAcl(denied);
        return new InaccessibleCandidate(entry, view, original);
    }

    public static CatalogRecord record(Asset asset, String path, CatalogAccess access) {
        var location = new CatalogLocation(
            access == CatalogAccess.AUTHORIZED ? CatalogRootKind.AUTH : CatalogRootKind.CUSTOM, new ModelPath(path));
        return new CatalogRecord(location, new CatalogContentBinding(asset.identity().modelId(), asset.content()));
    }

    private static Asset parse(Path root, String name, String resource, boolean builtin) throws Exception {
        var manifest = RemotePublicationScenarioFixture.class.getResource(resource);
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        var output = Files.createDirectories(root.resolve(name));
        final Path parsed;
        try (var vfs = new Directory(source)) {
            parsed = builtin ? ModelParser.parseBuiltinDefault(vfs, output)
                : ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll());
        }
        var file = parsed;
        if (!builtin) {
            var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath(name));
            var content = ManagedContainer.openDirect(parsed, location);
            try {
                file =
                    ModelExporter.export(content, TestPreviews.blank(), output.resolve(name + "-portable.mxc"), null);
            } finally {
                content.representation().close();
            }
        }
        return open(name, file);
    }

    private static Asset repack(Path root, Asset source, String name, byte summaryMarker) throws Exception {
        var output = Files.createDirectories(root.resolve(name));
        var target = Files.copy(source.file(), output.resolve(name + ".mxc"), StandardCopyOption.REPLACE_EXISTING);
        var bytes = Files.readAllBytes(target);
        var summaryOffset = AssetContainerConstant.HEAD.length;
        if (bytes[summaryOffset] == 0) {
            throw new AssertionError("Scenario fixture requires a non-empty container summary");
        }
        bytes[summaryOffset] = bytes[summaryOffset] == summaryMarker ? (byte)(summaryMarker ^ 1) : summaryMarker;
        final AssetContainerView view;
        try (var channel = FileChannel.open(source.file(), StandardOpenOption.READ)) {
            view = AssetContainerReader.read(channel);
        }
        var verification = view.getChunkInfo(AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
        var containerHash = Blake3.computeHash(ArrayBuffer.borrow(bytes, 0, verification.offset()));
        System.arraycopy(containerHash, 0, bytes, verification.offset(), containerHash.length);
        Files.write(target, bytes);
        var repacked = open(name, target);
        if (!source.identity().modelId().equals(repacked.identity().modelId())
            || source.identity().containerId().equals(repacked.identity().containerId())) {
            throw new AssertionError("Repacked fixture must retain ModelId and replace ContainerId");
        }
        return repacked;
    }

    private static Asset open(String name, Path file) throws Exception {
        final ModelFileIdentity identity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath(name));
        return new Asset(name, file, identity,
            ManagedContainer.openIndexed(new CatalogIndexEntry(identity, location, file)));
    }

    public record Asset(String name, Path file, ModelFileIdentity identity, ManagedContainer content) {
    }

    public record SourceRequest(int ordinal, List<String> identities, String outcome) {
    }

    public record InaccessibleCandidate(CatalogIndexEntry entry, AclFileAttributeView view,
        List<AclEntry> originalAcl) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            view.setAcl(originalAcl);
        }
    }

    private static final class SourceFetcher implements RemoteMetadataFetcher {
        private final Map<ModelFileIdentity, Asset> assets;
        private final Map<ModelFileIdentity, Outcome> outcomes = new LinkedHashMap<>();
        private final List<SourceRequest> requests = new ArrayList<>();
        private final Map<ModelFileIdentity, HeldAction> held = new LinkedHashMap<>();

        private SourceFetcher(Map<ModelFileIdentity, Asset> assets) {
            this.assets = assets;
        }

        private synchronized void fail(ModelFileIdentity identity) {
            outcomes.put(identity, Outcome.FAIL);
        }

        private synchronized void hold(ModelFileIdentity identity) {
            outcomes.put(identity, Outcome.HOLD);
        }

        @Override
        public synchronized CompletableFuture<Void> fetchMetadata(List<ModelFileIdentity> identities,
            MetadataReceiver receiver) {
            var outcome = identities.stream().map(identity -> outcomes.getOrDefault(identity, Outcome.SUCCESS))
                .max(Comparator.comparingInt(Enum::ordinal)).orElse(Outcome.SUCCESS);
            requests.add(new SourceRequest(requests.size() + 1,
                identities.stream().map(RemotePublicationScenarioFixture::identity).toList(), outcome.name()));
            if (outcome == Outcome.FAIL) {
                return CompletableFuture.failedFuture(AssetLoadException.access("controlled server metadata failure"));
            }
            if (outcome == Outcome.HOLD) {
                if (identities.size() != 1) {
                    throw new AssertionError("Held source action must own one exact identity");
                }
                var identity = identities.get(0);
                var action = new HeldAction(identity, receiver, requireAsset(identity));
                held.put(identity, action);
                return action;
            }
            try {
                for (var identity : identities) {
                    publish(receiver, identity, requireAsset(identity));
                }
                return CompletableFuture.completedFuture(null);
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        private synchronized List<SourceRequest> requests() {
            return List.copyOf(requests);
        }

        private synchronized boolean completeHeld(ModelFileIdentity identity) throws Exception {
            var action = held.remove(identity);
            if (action == null) {
                throw new AssertionError("No held source action for " + identity(identity));
            }
            return action.completeInput();
        }

        private Asset requireAsset(ModelFileIdentity identity) {
            var asset = assets.get(identity);
            if (asset == null) {
                throw new AssertionError("No source bytes for " + identity(identity));
            }
            return asset;
        }

        private static void publish(MetadataReceiver receiver, ModelFileIdentity identity, Asset asset)
            throws IOException {
            {
                var prefix = asset.content().representation().metadataPrefix().orElseThrow();
                receiver.accept(identity, prefix);
            }
        }
    }

    private static final class HeldAction extends CompletableFuture<Void> {
        private final ModelFileIdentity identity;
        private final RemoteMetadataFetcher.MetadataReceiver receiver;
        private final Asset asset;

        private HeldAction(ModelFileIdentity identity, RemoteMetadataFetcher.MetadataReceiver receiver, Asset asset) {
            this.identity = identity;
            this.receiver = receiver;
            this.asset = asset;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        private boolean completeInput() throws Exception {
            SourceFetcher.publish(receiver, identity, asset);
            return complete(null);
        }
    }

    private static String identity(ModelFileIdentity identity) {
        return identity.modelId() + ":" + identity.containerId();
    }

    private enum Outcome {
        SUCCESS, HOLD, FAIL
    }
}
