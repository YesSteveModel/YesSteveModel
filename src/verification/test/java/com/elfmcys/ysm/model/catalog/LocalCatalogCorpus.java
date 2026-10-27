package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LicenseProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class LocalCatalogCorpus {
    static final int VALID_MODEL_COUNT = 2_000;
    static final String INVALID_PATH = "invalid-corrupt.mxc";
    static final String DUPLICATE_PATH = "zz-duplicate-model-0000.mxc";
    static final String MODIFIED_PATH = "model-0042.mxc";
    static final String EXPLICIT_DELETE_PATH = "model-0044.mxc";
    static final String BURST_MODIFIED_PATH = "model-0043.mxc";
    static final String BURST_DELETE_PATH = "model-0045.mxc";
    static final String ADDED_PATH = "model-added.mxc";
    static final String BURST_ADDED_PATH = "burst-added.mxc";
    static final String LATE_PATH = "late-after-close.mxc";

    private final Path root;
    private final Path defaultFile;
    private final Map<String, ModelFile> initialSources;
    private final ModelFile added;
    private final ModelFile modified;
    private final ModelFile burstAdded;
    private final ModelFile burstModified;
    private final ModelFile late;
    private final InputManifest input;

    private LocalCatalogCorpus(Path root, Path defaultFile, Map<String, ModelFile> initialSources, ModelFile added,
        ModelFile modified, ModelFile burstAdded, ModelFile burstModified, ModelFile late, InputManifest input) {
        this.root = root;
        this.defaultFile = defaultFile;
        this.initialSources = Map.copyOf(initialSources);
        this.added = added;
        this.modified = modified;
        this.burstAdded = burstAdded;
        this.burstModified = burstModified;
        this.late = late;
        this.input = input;
    }

    static LocalCatalogCorpus create(Path workspace) throws Exception {
        var root = Files.createDirectories(workspace.resolve("models"));
        var staged = Files.createDirectories(workspace.resolve("staged"));
        var defaultFile = writeModel(staged.resolve("intrinsic-default.mxc"), 1, "intrinsic-default");
        var initial = new TreeMap<String, ModelFile>();
        for (var index = 0; index < VALID_MODEL_COUNT; index++) {
            var relative = "model-%04d.mxc".formatted(index);
            var file = writeModel(root.resolve(relative), 10_000 + index, relative);
            initial.put(relative, file);
        }
        var duplicate = copyModel(root.resolve("model-0000.mxc"), root.resolve(DUPLICATE_PATH));
        initial.put(DUPLICATE_PATH, duplicate);
        Files.write(root.resolve(INVALID_PATH), new byte[] {0x59, 0x53, 0x4d, 0x00});

        var added = writeModel(staged.resolve(ADDED_PATH), 20_001, ADDED_PATH);
        var modified = writeModel(staged.resolve("modified-payload.mxc"), 20_002, MODIFIED_PATH);
        var burstAdded = writeModel(staged.resolve(BURST_ADDED_PATH), 20_003, BURST_ADDED_PATH);
        var burstModified = writeModel(staged.resolve("burst-modified-payload.mxc"), 20_004, BURST_MODIFIED_PATH);
        var late = writeModel(staged.resolve(LATE_PATH), 20_005, LATE_PATH);

        var defaultIdentity = defaultFile.identity();
        var tree = contentTree(root);
        var expected = new TreeMap<>(initial);
        var actions = new ArrayList<Action>();
        actions.add(action("initial-discovery", "discover", expected, defaultIdentity,
            Map.of("rootTreeSha256", tree.sha256())));
        expected.put(ADDED_PATH, added.at(ADDED_PATH));
        actions.add(action("add-and-overlap", "add", expected, defaultIdentity,
            Map.of("target", ADDED_PATH, "payloadSha256", added.sha256(), "overlap", "explicit reload")));
        expected.put(MODIFIED_PATH, modified.at(MODIFIED_PATH));
        actions.add(action("modify-and-held-content", "modify", expected, defaultIdentity,
            Map.of("target", MODIFIED_PATH, "payloadSha256", modified.sha256(), "retiredIdentity",
                identity(initial.get(MODIFIED_PATH).identity()))));
        expected.remove(EXPLICIT_DELETE_PATH);
        actions.add(action("delete", "delete", expected, defaultIdentity, Map.of("target", EXPLICIT_DELETE_PATH)));
        expected.put(BURST_ADDED_PATH, burstAdded.at(BURST_ADDED_PATH));
        expected.put(BURST_MODIFIED_PATH, burstModified.at(BURST_MODIFIED_PATH));
        expected.remove(BURST_DELETE_PATH);
        actions.add(action("explicit-burst", "explicit-reload", expected, defaultIdentity,
            Map.of("addTarget", BURST_ADDED_PATH, "addPayloadSha256", burstAdded.sha256(), "modifyTarget",
                BURST_MODIFIED_PATH, "modifyPayloadSha256", burstModified.sha256(), "deleteTarget",
                BURST_DELETE_PATH)));
        actions.add(action("root-inventory-failure", "root-failure", expected, defaultIdentity,
            Map.of("fixture", "directory replaced by regular file")));
        actions.add(new Action("startup-default", "startup", Map.of("intent", "required-default-only"),
            List.of(expectedDefault(defaultIdentity))));
        actions.add(new Action("owner-close-late-candidate", "close", Map.of("target", LATE_PATH, "payloadSha256",
            late.sha256(), "order", "worker complete then owner close then owner callback"), List.of()));

        var candidates = new ArrayList<SourceFile>();
        for (var entry : initial.entrySet()) {
            candidates.add(sourceFile(root, entry.getKey(), entry.getValue(), true));
        }
        candidates.add(
            new SourceFile(INVALID_PATH, sha256(Files.readAllBytes(root.resolve(INVALID_PATH))), null, null, false));
        var input = new InputManifest("MMR-DOM-LOCAL-001", VALID_MODEL_COUNT, tree.sha256(), tree.files(),
            List.copyOf(candidates), List.copyOf(actions),
            Map.of("invalidSource", INVALID_PATH, "duplicateSource", DUPLICATE_PATH, "duplicateWinner",
                "model-0000.mxc", "rootFailure", "replace directory with regular file", "trigger",
                "explicit reload after filesystem burst"));
        return new LocalCatalogCorpus(root, defaultFile.path(), initial, added, modified, burstAdded, burstModified,
            late, input);
    }

    Path root() {
        return root;
    }

    Path defaultFile() {
        return defaultFile;
    }

    InputManifest input() {
        return input;
    }

    List<InventoryEntry> expected(String actionId) {
        return input.actions().stream().filter(action -> action.actionId().equals(actionId)).findFirst().orElseThrow()
            .expectedInventory();
    }

    ModelFileIdentity identityAt(String relativePath) {
        return initialSources.get(relativePath).identity();
    }

    void add() throws IOException {
        copy(added.path(), root.resolve(ADDED_PATH));
    }

    void modify() throws IOException {
        copy(modified.path(), root.resolve(MODIFIED_PATH));
    }

    void delete() throws IOException {
        Files.delete(root.resolve(EXPLICIT_DELETE_PATH));
    }

    void fileBurst() throws IOException {
        copy(burstAdded.path(), root.resolve(BURST_ADDED_PATH));
        copy(burstModified.path(), root.resolve(BURST_MODIFIED_PATH));
        Files.delete(root.resolve(BURST_DELETE_PATH));
    }

    void addLateCandidate() throws IOException {
        copy(late.path(), root.resolve(LATE_PATH));
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Action action(String actionId, String operation, Map<String, ModelFile> sources,
        ModelFileIdentity defaultIdentity, Map<String, String> parameters) {
        return new Action(actionId, operation, parameters, expectedInventory(sources, defaultIdentity));
    }

    private static List<InventoryEntry> expectedInventory(Map<String, ModelFile> sources,
        ModelFileIdentity defaultIdentity) {
        var result = new LinkedHashMap<String, InventoryEntry>();
        result.put(defaultIdentity.modelId().toString(), expectedDefault(defaultIdentity));
        sources.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.putIfAbsent(entry.getValue().identity().modelId().toString(),
                inventory(entry.getValue().identity(), CatalogRootKind.CUSTOM, entry.getKey())));
        return result.values().stream().sorted(Comparator.comparing(InventoryEntry::modelId)).toList();
    }

    private static InventoryEntry expectedDefault(ModelFileIdentity identity) {
        return inventory(identity, CatalogRootKind.BUILTIN, "default");
    }

    private static InventoryEntry inventory(ModelFileIdentity identity, CatalogRootKind rootKind, String path) {
        return new InventoryEntry(identity.modelId().toString(), identity.containerId().toString(), rootKind.name(),
            path);
    }

    private static SourceFile sourceFile(Path root, String relative, ModelFile file, boolean valid) throws IOException {
        return new SourceFile(relative, sha256(Files.readAllBytes(root.resolve(relative))),
            file.identity().modelId().toString(), file.identity().containerId().toString(), valid);
    }

    private static ModelFile writeModel(Path path, int seed, String displayName) throws Exception {
        var modelId = hash("local-catalog-model-" + seed);
        var manifest =
            ManifestProto.newBuilder().setCommonAssets(CommonProto.newBuilder().setStringsBlobId(0).build())
                .setInfo(InfoProto.newBuilder()
                    .setThumbnailSource(com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.GENERATED)
                    .setSettings(SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
                        .setDisablePreviewRotation(false).build())
                    .setProperties(PropertiesProto.newBuilder().setModelId(ByteBuffer.wrap(modelId.bytes()))
                        .setFree(false).setOriginVer("").build())
                    .setMetadata(MetadataProto.newBuilder().setName(displayName).setTips("")
                        .setLicense(LicenseProto.newBuilder().setType("").setDesc("").build()).build())
                    .build())
                .addRenderTargets(
                    RenderTargetProto.newBuilder().setTargetId("player").setKind(RenderTargetKind.PLAYER).setBlobId(0)
                        .setSettings(ModelSettingsProto.newBuilder().setHeightScale(0).setWidthScale(0)
                            .setRenderLayersFirst(false).setForceCulling(false).setGuiNoLighting(false)
                            .setMergeMultilineExpr(false).build())
                        .setStats(ModelStatsProto.newBuilder().setBones(0).setCubes(0).setFaces(0).build())
                        .putTextures("main", PBRTextureSetProto.newBuilder().setUv(ImageProto.newBuilder().setBlobId(0)
                            .setFormat("").setWidth(0).setHeight(0).setFrameCount(0).build()).build())
                        .build())
                .build();
        Files.createDirectories(path.getParent());
        try (var writer = new ModelFileWriter();
            var output = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.setManifest(manifest);
            try (var preview = TestPreviews.blank().open()) {
                writer.setThumbnail(preview);
            }
            writer.write(output);
        }
        var identity = identity(path);
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            new ModelFileView(channel);
        }
        return new ModelFile(path, identity, sha256(Files.readAllBytes(path)));
    }

    private static ModelFile copyModel(Path source, Path target) throws Exception {
        Files.copy(source, target);
        return new ModelFile(target, identity(target), sha256(Files.readAllBytes(target)));
    }

    private static ModelFileIdentity identity(Path path) throws IOException {
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return ModelFileIdentityReader.read(channel);
        }
    }

    private static Hash256 hash(String value) {
        try {
            return new Hash256(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("The JVM must provide SHA-256", exception);
        }
    }

    private static TreeHash contentTree(Path root) throws IOException {
        var lines = new ArrayList<String>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(path).toString().replace('\\', '/');
                lines.add(sha256(Files.readAllBytes(path)) + "  " + relative);
            }
        }
        lines.sort(String::compareTo);
        var bytes = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        return new TreeHash(sha256(bytes), List.copyOf(lines));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("The JVM must provide SHA-256", exception);
        }
    }

    record InputManifest(String scenarioId, int validModelCount, String initialTreeSha256, List<String> initialTree,
        List<SourceFile> initialSources, List<Action> actions, Map<String, String> fixtures) {
        InputManifest {
            initialTree = List.copyOf(initialTree);
            initialSources = List.copyOf(initialSources);
            actions = List.copyOf(actions);
            fixtures = Map.copyOf(fixtures);
        }
    }

    record SourceFile(String path, String sha256, String modelId, String containerId, boolean valid) {
    }

    record Action(String actionId, String operation, Map<String, String> parameters,
        List<InventoryEntry> expectedInventory) {
        Action {
            parameters = Map.copyOf(parameters);
            expectedInventory = List.copyOf(expectedInventory);
        }
    }

    record InventoryEntry(String modelId, String containerId, String sourceKind, String sourcePath) {
    }

    private record ModelFile(Path path, ModelFileIdentity identity, String sha256) {
        private ModelFile at(String relativePath) {
            return new ModelFile(Path.of(relativePath), identity, sha256);
        }
    }

    private static String identity(ModelFileIdentity identity) {
        return identity.modelId() + ":" + identity.containerId();
    }

    private record TreeHash(String sha256, List<String> files) {
    }
}
