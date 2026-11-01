package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.format.schema.model.ModelOriginVersion;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.format.parser.pojo.controller.AnimationControllerFile;
import com.elfmcys.ysm.format.parser.pojo.manifest.ModelManifest;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.PBRTextureSet;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.PlayerModelFiles;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.ReplacedModelFiles;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ModelProperties;
import com.elfmcys.ysm.format.media.SoundMediaGeneration;
import com.elfmcys.ysm.format.media.SupportedAudioProbe;
import com.elfmcys.ysm.format.parser.pojo.model.GeoModel;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.common.SoundProto;
import com.elfmcys.ysm.proto.mixel.common.StringPairProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LanguageFileProto;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ProtoUtil;
import com.elfmcys.ysm.util.ResourceTransaction;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Consumer;

final class RawModelAssembler implements Closeable {
    private static final String MANIFEST_FILE_NAME = "ysm.json";

    static final String[] PLAYER_MAIN_ANIMATION_TYPES = {"main", "arm", "fp_arm", "extra", "tac", "carryon", "parcool",
        "swem", "slashblade", "tlm", "immersive_melodies", "irons_spell_books"};

    private final Gson gson = new Gson();
    private final MolangFrontend frontend;
    private final RawModelSource source;
    private final boolean dryRun;
    private final DefaultAnimationFilter defaultAnimationFilter;
    private final boolean recompressImages;
    @Nullable
    private final Path dstDir;
    @Nullable
    private final WritableByteChannel memoryOutput;
    @Nullable
    private final ModelFileWriter writer;
    @Nullable
    private final RawImageCompressor imageCompressor;
    private final List<RawModelDiagnostic> diagnostics = new ArrayList<>();

    private ModelProperties activeProperties;
    private String originVer = ModelOriginVersion.CURRENT_RELEASE;

    void originVer(String version) {
        originVer = Objects.requireNonNull(version, "originVer");
    }

    RawModelAssembler(RawModelSource source, @Nullable Path dstDir, boolean dryRun,
        DefaultAnimationFilter defaultAnimationFilter, boolean recompressImages) {
        this(source, dstDir, dryRun, defaultAnimationFilter, recompressImages, new MolangFrontend());
    }

    RawModelAssembler(RawModelSource source, @Nullable Path dstDir, boolean dryRun,
        DefaultAnimationFilter defaultAnimationFilter, boolean recompressImages, MolangFrontend frontend) {
        this.source = Objects.requireNonNull(source, "source");
        this.frontend = Objects.requireNonNull(frontend, "frontend");
        this.dryRun = dryRun;
        this.defaultAnimationFilter = Objects.requireNonNull(defaultAnimationFilter, "defaultAnimationFilter");
        this.recompressImages = recompressImages;
        this.dstDir = dryRun ? null : Objects.requireNonNull(dstDir, "dstDir");
        this.memoryOutput = null;
        this.writer = dryRun ? null : new ModelFileWriter();
        this.imageCompressor = dryRun || !recompressImages ? null : new RawImageCompressor();
    }

    RawModelAssembler(RawModelSource source, WritableByteChannel memoryOutput,
        DefaultAnimationFilter defaultAnimationFilter, boolean recompressImages, MolangFrontend frontend) {
        this.source = Objects.requireNonNull(source, "source");
        this.frontend = Objects.requireNonNull(frontend, "frontend");
        this.dryRun = false;
        this.defaultAnimationFilter = Objects.requireNonNull(defaultAnimationFilter, "defaultAnimationFilter");
        this.recompressImages = recompressImages;
        this.dstDir = null;
        this.memoryOutput = Objects.requireNonNull(memoryOutput, "memoryOutput");
        this.writer = new ModelFileWriter();
        this.imageCompressor = recompressImages ? new RawImageCompressor() : null;
    }

    Result parse() {
        try {
            var sourceManifest = readManifest();
            activeProperties = sourceManifest.properties;
            if (activeProperties.originVer != null && !activeProperties.originVer.isBlank()) {
                originVer = activeProperties.originVer;
            }

            var manifest = dryRun ? null : ManifestProto.newBuilder();
            writePlayerModel(manifest, sourceManifest.files.player, sourceManifest.properties);
            var info = ModelInfoBuilder.buildInfo(!dryRun, sourceManifest, this::readImageBlob, frontend);
            writeReplacedModels(manifest, sourceManifest);
            try (var common = writeCommonAssets(info, sourceManifest)) {
                writeContainerImages(info, sourceManifest.properties);

                var modelHash = new Hash256(source.aggregateHash());
                if (dryRun) {
                    return new Result(modelHash, null, List.copyOf(diagnostics), originVer);
                }
                var hashId = modelHash.toString();
                ModelInfoBuilder.setProperties(Objects.requireNonNull(info, "info"), sourceManifest.properties,
                    originVer, modelHash);
                Objects.requireNonNull(manifest, "manifest").setInfo(info.build());
                installSounds(manifest, common);
                writer().setManifest(manifest.build());
                if (memoryOutput != null) {
                    writer().write(memoryOutput);
                    return new Result(modelHash, null, List.copyOf(diagnostics), originVer);
                }
                Files.createDirectories(Objects.requireNonNull(dstDir, "dstDir"));
                var output = dstDir.resolve(hashId + ".mxc");
                try (var channel = FileChannel.open(output, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    writer().write(channel);
                }
                return new Result(modelHash, output, List.copyOf(diagnostics), originVer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ModelManifest readManifest() throws IOException {
        var manifest =
            source.readJson(MANIFEST_FILE_NAME, "manifest", gson, ModelManifest.class, false).orElseGet(() -> {
                try {
                    return LegacyManifestBuilder.build(source, gson);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        manifest.normalize();
        return manifest;
    }

    private void writePlayerModel(@Nullable ManifestProto.Builder manifest, PlayerModelFiles files,
        ModelProperties properties) throws IOException {
        if (files == null) {
            throw new IOException("Missing player model files");
        }
        var main =
            readGeoModel(Objects.requireNonNull(files.model.get("main"), "Missing main model path"), true).orElse(null);
        var arm =
            readGeoModel(Objects.requireNonNull(files.model.get("arm"), "Missing arm model path"), true).orElse(null);
        var playerData = dryRun ? null : ModelDataProto.newBuilder();

        if (!dryRun) {
            Objects.requireNonNull(playerData, "playerData").putGeoModels("main",
                ByteBuffer.wrap(ProtoUtil.serializeToArray(Objects.requireNonNull(main, "main").model)));
            playerData.putGeoModels("arm",
                ByteBuffer.wrap(ProtoUtil.serializeToArray(Objects.requireNonNull(arm, "arm").model)));

            if (properties.heightScale == 0.7f && main.heightScale != 0.7f) {
                properties.heightScale = main.heightScale;
            }
            if (properties.widthScale == 0.7f && main.widthScale != 0.7f) {
                properties.widthScale = main.widthScale;
            }
        }

        var defaultTexture = properties.defaultTexture;

        for (var animationType : PLAYER_MAIN_ANIMATION_TYPES) {
            var path = files.animation.get(animationType);
            if (path != null) {
                var animation = readAnimation(path, true).orElse(null);
                if (!dryRun) {
                    animation = filterAnimations(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER,
                        List.of(), animationType, Objects.requireNonNull(animation, "animation"));
                    if (animation.animations().isEmpty()) {
                        continue;
                    }
                    Objects.requireNonNull(playerData, "playerData").putAnimationFiles(animationType,
                        Objects.requireNonNull(animation, "animation"));
                }
            }
        }

        for (var controllerFile : files.animationControllers) {
            var controller = readAnimationController(controllerFile);
            if (!dryRun) {
                var name = fileNameWithoutExtension(controllerFile);
                Objects.requireNonNull(playerData, "playerData").putAnimationControllers(name,
                    Objects.requireNonNull(controller, "controller"));
            }
        }
        var player = dryRun ? null : RenderTargetProto.newBuilder().setTargetId("player")
            .setKind(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER);
        for (var texture : files.texture) {
            var textureSet = readTextureSet(texture);
            if (!dryRun) {
                var textureName = textureName(texture.uv);
                if (defaultTexture.isEmpty()) {
                    defaultTexture = textureName;
                    properties.defaultTexture = textureName;
                }
                Objects.requireNonNull(player, "player").putTextures(textureName,
                    Objects.requireNonNull(textureSet, "textureSet"));
            }
        }

        if (!dryRun) {
            Objects.requireNonNull(player, "player").setSettings(ModelInfoBuilder.modelSettings(properties))
                .setStats(Objects.requireNonNull(main, "main").stats.toProto())
                .setBlobId(writer().addProtoBlob(Objects.requireNonNull(playerData, "playerData").build(), 16));
            Objects.requireNonNull(manifest, "manifest").addRenderTargets(player.build());
        }
    }

    @SuppressWarnings("deprecation")
    private void writeReplacedModels(@Nullable ManifestProto.Builder manifest, ModelManifest sourceManifest)
        throws IOException {
        var projectileIndex = 0;
        for (var model : sourceManifest.files.projectiles.list) {
            writeReplacedModel(manifest, model, "projectile/%04d".formatted(projectileIndex++),
                com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE);
        }
        if (sourceManifest.files.arrow != null) {
            writeReplacedModel(manifest, sourceManifest.files.arrow, "projectile/%04d".formatted(projectileIndex),
                com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE);
        }
        var vehicleIndex = 0;
        for (var model : sourceManifest.files.vehicles.list) {
            writeReplacedModel(manifest, model, "vehicle/%04d".formatted(vehicleIndex++),
                com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.VEHICLE);
        }
    }

    private void writeReplacedModel(@Nullable ManifestProto.Builder manifest, ReplacedModelFiles files, String targetId,
        com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind kind) throws IOException {
        if (files == null || files.match == null || files.match.isEmpty()) {
            return;
        }
        var geo = readGeoModel(files.model, true).orElse(null);
        AnimationFileProto animation = null;
        if (!StringUtils.isBlank(files.animation)) {
            animation = readAnimation(files.animation, true).orElse(null);
        }
        AnimationControllerFileProto controller = null;
        if (!StringUtils.isBlank(files.controller)) {
            controller = readAnimationController(files.controller);
        }

        RenderTargetProto.Builder replaced = null;
        if (!dryRun) {
            replaced = RenderTargetProto.newBuilder().setTargetId(targetId).setKind(kind);
            for (var match : files.match) {
                replaced.addMatch(match);
            }
            var data = ModelDataProto.newBuilder();
            data.putGeoModels("main",
                ByteBuffer.wrap(ProtoUtil.serializeToArray(Objects.requireNonNull(geo, "geo").model)));
            if (animation != null) {
                animation = filterAnimations(kind, files.match, "main", animation);
            }
            if (animation != null && !animation.animations().isEmpty()) {
                data.putAnimationFiles("main", animation);
            }
            if (controller != null) {
                data.putAnimationControllers("main", controller);
            }

            replaced.setBlobId(writer().addProtoBlob(data.build(), 16))
                .setSettings(ModelInfoBuilder.modelSettings(activeProperties)).setStats(geo.stats.toProto());
        }

        var texture = readTextureSet(files.texture);
        if (dryRun) {
            return;
        }
        Objects.requireNonNull(replaced, "replaced").putTextures("default", Objects.requireNonNull(texture, "texture"));
        Objects.requireNonNull(manifest, "manifest").addRenderTargets(replaced.build());
    }

    private AnimationFileProto filterAnimations(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind kind,
        List<String> matches, String animationSet, AnimationFileProto animations) throws IOException {
        return defaultAnimationFilter.apply(kind, matches, animationSet, animations);
    }

    @SuppressWarnings("deprecation")
    private PendingCommon writeCommonAssets(@Nullable InfoProto.Builder info, ModelManifest sourceManifest)
        throws IOException {
        var files = sourceManifest.files;
        final List<RawSoundGroup> sounds;
        if (!StringUtils.isBlank(files.soundPath)) {
            sounds = collectSounds(files.soundPath);
        } else if (files.player != null && !StringUtils.isBlank(files.player.soundPath)) {
            sounds = collectSounds(files.player.soundPath);
        } else {
            sounds = collectSounds("sounds");
        }

        try (var resources = new ResourceTransaction()) {
            sounds.forEach(resources::own);
            var strings = dryRun ? null : StringDataProto.newBuilder();
            writeFunctions(strings, StringUtils.isBlank(files.functionPath) ? "functions" : files.functionPath);
            writeLanguageFiles(info, StringUtils.isBlank(files.languagePath) ? "lang" : files.languagePath);
            var common = dryRun ? null : CommonProto.newBuilder()
                .setStringsBlobId(writer().addProtoBlob(Objects.requireNonNull(strings, "strings").build(), 16));
            var pending = new PendingCommon(common, sounds);
            resources.commit();
            return pending;
        }
    }

    private void writeContainerImages(@Nullable InfoProto.Builder info, ModelProperties properties) throws IOException {
        if (readImage(properties.icon, "icon", false, RawImageCompressor.MODEL_ICON, dryRun ? null : writer()::setIcon)
            && !dryRun) {
            Objects.requireNonNull(info, "info")
                .setIconSource(com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.RAW);
        }
        if (readImage(properties.thumbnail, "thumbnail", false, RawImageCompressor.MODEL_THUMBNAIL,
            dryRun ? null : writer()::setThumbnail) && !dryRun) {
            Objects.requireNonNull(info, "info")
                .setThumbnailSource(com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.RAW);
        }
    }

    @SuppressWarnings("SameParameterValue")
    Optional<GeoBuilder.Result> readGeoModel(String fileName, boolean required) throws IOException {
        if (dryRun) {
            source.readFile(fileName, "model", required);
            return Optional.empty();
        }
        return source.readJson(fileName, "model", gson, GeoModel.class, required).map(m -> {
            try {
                return GeoBuilder.build(m);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @SuppressWarnings("SameParameterValue")
    Optional<AnimationFileProto> readAnimation(String fileName, boolean required) throws IOException {
        if (dryRun) {
            source.readFile(fileName, "animation", required);
            return Optional.empty();
        }
        return source.readJson(fileName, "animation",
            AnimationFile.createGson(activeProperties != null && activeProperties.mergeMultilineExpr),
            AnimationFile.class, required).map(value -> AnimationBuilder.build(value, frontend, fileName));
    }

    @Nullable
    private AnimationControllerFileProto readAnimationController(String fileName) throws IOException {
        if (dryRun) {
            source.readFile(fileName, "controller", true);
            return null;
        }
        return source.readJson(fileName, "controller", gson, AnimationControllerFile.class, true)
            .map(value -> AnimationControllerBuilder.build(value, frontend, fileName)).orElseThrow();
    }

    @Nullable
    private PBRTextureSetProto readTextureSet(PBRTextureSet src) throws IOException {
        if (src == null || StringUtils.isBlank(src.uv)) {
            throw new IOException("Missing texture uv");
        }
        var dst = dryRun ? null : PBRTextureSetProto.newBuilder();
        readImageBlob(src.uv, "texture", RawImageCompressor.LOSSLESS_TEXTURE, dst == null ? null : dst::setUv);
        if (!StringUtils.isBlank(src.normal)) {
            readImageBlob(src.normal, "texture/normal", RawImageCompressor.LOSSLESS_TEXTURE,
                dst == null ? null : dst::setNormal);
        }
        if (!StringUtils.isBlank(src.specular)) {
            readImageBlob(src.specular, "texture/specular", RawImageCompressor.LOSSLESS_TEXTURE,
                dst == null ? null : dst::setSpecular);
        }
        return dst == null ? null : dst.build();
    }

    void readImageBlob(String path, String hashType, RawImageCompressor.Policy policy,
        @Nullable Consumer<ImageProto> consumer) throws IOException {
        readImage(path, hashType, true, policy, img -> {
            var blobId = writer().addImageBlob(img);
            Objects.requireNonNull(consumer, "consumer").accept(ImageProto.newBuilder().setBlobId(blobId)
                .setFormat(img.format().name()).setWidth(img.width()).setHeight(img.height()).setFrameCount(1).build());
        });
    }

    boolean readImage(String path, String type, boolean required, RawImageCompressor.Policy policy,
        @Nullable Consumer<Image> consumer) throws IOException {
        var data = source.readFile(path, type, required);
        if (data.isEmpty()) {
            return false;
        }
        if (dryRun) {
            return true;
        }
        try (var original = Image.probe(data.orElseThrow())) {
            try (
                var stored = recompressImages ? imageCompressor().compress(original, policy, path) : original.share()) {
                Objects.requireNonNull(consumer, "consumer").accept(stored);
            }
        }
        return true;
    }

    private List<RawSoundGroup> collectSounds(String path) throws IOException {
        try (var resources = new ResourceTransaction()) {
            var sounds = new LinkedHashMap<Hash256, RawSoundGroup>();
            var names = new java.util.HashSet<String>();
            var failure = new IOException[1];
            source.collectFiles(path, "", "sound", true, (name, data) -> {
                if (failure[0] != null) {
                    return;
                }
                var inspection = SupportedAudioProbe.inspect(data.nio());
                if (inspection.disposition() == SupportedAudioProbe.Disposition.UNKNOWN) {
                    diagnostics.add(new RawModelDiagnostic(RawModelDiagnostic.Kind.UNKNOWN_AUDIO));
                    return;
                }
                if (!inspection.playable()) {
                    failure[0] = new IOException("Invalid raw sound " + name + ": " + inspection.diagnostic());
                    return;
                }
                if (!names.add(name)) {
                    failure[0] = new IOException("Duplicate raw sound name: " + name);
                    return;
                }
                if (dryRun) {
                    return;
                }
                var identity = ModelHashing.blake3(data);
                var group = sounds.get(identity);
                if (group == null) {
                    group = resources.own(new RawSoundGroup(identity, data.copy(), new ArrayList<>()));
                    sounds.put(identity, group);
                }
                group.names().add(name);
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            var result = List.copyOf(sounds.values());
            resources.commit();
            return result;
        }
    }

    private void installSounds(ManifestProto.Builder manifest, PendingCommon pending) throws IOException {
        var common = Objects.requireNonNull(pending.common(), "common");
        var selected = new ArrayList<SoundProto>();
        for (var group : pending.sounds()) {
            var serial = ByteBuffer.wrap(group.identity().bytes()).getInt();
            try (var generated = SoundMediaGeneration.normalize(group.source(), serial, candidate -> {
                int firstChunkId = writer().nextBlobId();
                var candidateSounds = new ArrayList<>(selected);
                for (var name : group.names()) {
                    candidateSounds.add(candidate.sound(name, firstChunkId));
                }
                var candidateManifest = manifestWithSounds(manifest, common, candidateSounds);
                return writer().serializedLength(candidateManifest, candidate.chunks());
            })) {
                var candidate = generated.candidate();
                int firstChunkId = writer().nextBlobId();
                var ids = writer().addSoundChunks(candidate.chunks());
                if (ids.length == 0 || ids[0] != firstChunkId) {
                    throw new IOException("Sound chunk numbering is not canonical");
                }
                for (var name : group.names()) {
                    selected.add(candidate.sound(name, firstChunkId));
                }
            }
        }
        selected.stream().sorted(java.util.Comparator.comparing(SoundProto::name)).forEach(common::addSounds);
        manifest.setCommonAssets(common.build());
    }

    private static ManifestProto manifestWithSounds(ManifestProto.Builder manifest, CommonProto.Builder common,
        List<SoundProto> sounds) {
        var candidateCommon = CommonProto.newBuilder(common.build());
        sounds.stream().sorted(java.util.Comparator.comparing(SoundProto::name)).forEach(candidateCommon::addSounds);
        return manifest.setCommonAssets(candidateCommon.build()).build();
    }

    private record PendingCommon(@Nullable CommonProto.Builder common,
        List<RawSoundGroup> sounds) implements Closeable {
        @Override
        public void close() {
            sounds.forEach(RawSoundGroup::close);
        }
    }

    private record RawSoundGroup(Hash256 identity, NativeStorage source, List<String> names) implements Closeable {
        @Override
        public void close() {
            source.close();
        }
    }

    private void writeFunctions(@Nullable StringDataProto.Builder strings, String path) {
        source.collectFiles(path, ".molang", "molang-func", true, (name, buf) -> {
            if (!dryRun) {
                Objects.requireNonNull(strings, "strings")
                    .addUserFunctions(UserFunctionProto.newBuilder().setName(name)
                        .setBody(FrontendPrograms.compile(frontend, RawModelSource.readUtf8(buf),
                            MolangFrontend.SourceMode.USER_FUNCTION, path + '/' + name + ".molang"))
                        .build());
            }
        });
    }

    private void writeLanguageFiles(@Nullable InfoProto.Builder info, String path) {
        source.collectFiles(path, ".json", "language", true, (name, buf) -> {
            if (!dryRun) {
                var object = JsonParser.parseString(RawModelSource.readUtf8(buf)).getAsJsonObject();
                var language = LanguageFileProto.newBuilder().setLocale(name);
                for (var entry : object.entrySet()) {
                    if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                        language.putEntries(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                Objects.requireNonNull(info, "info").addLanguageFiles(language.build());
            }
        });
    }

    private ModelFileWriter writer() {
        return Objects.requireNonNull(writer, "writer");
    }

    private RawImageCompressor imageCompressor() {
        return Objects.requireNonNull(imageCompressor, "imageCompressor");
    }

    private static StringPairProto stringPair(String key, String value) {
        return StringPairProto.newBuilder().setKey(key).setValue(value).build();
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        var normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String joinPath(String dir, String name) {
        if (StringUtils.isBlank(dir)) {
            return name;
        }
        return dir + "/" + name;
    }

    private static String textureName(String path) {
        return removeExtension(baseName(path));
    }

    private static String fileNameWithoutExtension(String path) {
        return removeExtension(baseName(path));
    }

    private static String baseName(String path) {
        var normalized = normalizePath(path);
        var index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private static String removeExtension(String path) {
        var index = path.lastIndexOf('.');
        return index >= 0 ? path.substring(0, index) : path;
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.close();
        }
    }

    record Result(Hash256 modelHash, @Nullable Path output, List<RawModelDiagnostic> diagnostics, String originVer) {
    }
}
