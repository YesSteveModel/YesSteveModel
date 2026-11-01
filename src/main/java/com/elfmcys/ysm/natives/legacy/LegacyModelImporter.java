package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import com.elfmcys.ysm.format.media.SoundMediaGeneration;
import com.elfmcys.ysm.format.media.SupportedAudioProbe;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.common.SoundProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.util.ProtoUtil;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owns native result validation and the mandatory staged legacy representation. */
public final class LegacyModelImporter {
    public RawCompileResult stage(Path source, Path outputDirectory) {
        try (var directory = com.elfmcys.ysm.util.DirectoryAccess
            .open(com.elfmcys.ysm.util.DirectoryAccess.ensureDirectory(outputDirectory))) {
            return stage(source, directory);
        } catch (IOException failure) {
            throw LegacyModelImportException.publicationFailure("Failed to open legacy staging directory", failure);
        }
    }

    public RawCompileResult stage(Path source, com.elfmcys.ysm.util.DirectoryAccess directory) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(directory, "directory");
        final NativeLegacyProtocol.Response response;
        try {
            response = NativeLegacyImporter.invoke(source);
        } catch (NativeLegacyProtocol.ProtocolException failure) {
            throw new LegacyModelImportException(NativeLegacyStatus.RESULT_PROTOCOL, failure.getMessage(), failure);
        }
        try (response) {
            if (response instanceof NativeLegacyProtocol.Failure failure) {
                throw new LegacyModelImportException(failure.status(), failure.diagnostic());
            }
            return stageSuccess((NativeLegacyProtocol.Success)response, directory);
        }
    }

    private static RawCompileResult stageSuccess(NativeLegacyProtocol.Success success,
        com.elfmcys.ysm.util.DirectoryAccess directory) {
        Path output = null;
        String staging = null;
        Object stagingIdentity = null;
        FileChannel stagedChannel = null;
        try {
            var descriptor = success.descriptor();
            var records = descriptor.records();
            var payloads = success.payloads();
            var mapper = new LegacyFormalModelMapper();
            var manifest = mapper.manifest(parseManifest(payloads.get(0)));
            var translatedPayloads = new byte[records.size()][];
            var sounds = new ArrayList<LegacySound>();
            var modelId = new Hash256(descriptor.modelId());
            validateManifestIdentity(manifest, modelId);

            output = directory.label().resolve(modelId + ".mxc");
            staging = output.getFileName() + ".tmp-" + UUID.randomUUID();
            stagedChannel = directory.file(staging, true, true, true);
            stagingIdentity = directory.fileIdentity(staging);
            try (var writer = new ModelFileWriter()) {
                for (var index = 1; index < records.size(); index++) {
                    var record = records.get(index);
                    var payload = payloads.get(index);
                    switch (record.kind()) {
                        case STRING_DATA -> {
                            var translated = mapper.strings(parseStringData(payload));
                            translatedPayloads[index] = ProtoUtil.serializeToArray(translated);
                            requireBlobId(writer.addProtoBlob(translated, 16), record);
                        }
                        case MODEL_DATA -> {
                            var translated = mapper.model(parseModelData(payload));
                            translatedPayloads[index] = ProtoUtil.serializeToArray(translated);
                            requireBlobId(writer.addProtoBlob(translated, 16), record);
                        }
                        case BLOB_IMAGE -> {
                            try (var image = image(record, payload)) {
                                requireBlobId(writer.addImageBlob(image), record);
                            }
                        }
                        case NAMED_IMAGE -> {
                            try (var image = image(record, payload)) {
                                if (record.name().equals(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME)) {
                                    writer.setThumbnail(image);
                                } else {
                                    writer.setIcon(image);
                                }
                            }
                        }
                        case SOUND_STREAM -> {
                            validateSound(record, payload.nio());
                            sounds.add(new LegacySound(record, payload));
                        }
                        case MANIFEST ->
                            throw new IOException("Legacy manifest is not the first and only manifest record");
                    }
                }
                manifest = installSounds(writer, manifest, sounds, modelId);
                translatedPayloads[0] = ProtoUtil.serializeToArray(manifest);
                writer.setManifest(manifest);
                writer.write(stagedChannel);
            }
            if (stagedChannel.size() > AssetContainerConstant.MAX_FILE_SIZE) {
                throw new IOException("Legacy staged container exceeds the file limit");
            }
            stagedChannel.position(0);
            reopen(stagedChannel, modelId, success, translatedPayloads);
            stagedChannel.force(true);
            directory.move(staging, stagingIdentity, directory, output.getFileName().toString());
            staging = null;
            return new RawCompileResult(modelId, output);
        } catch (LegacyModelImportException failure) {
            deleteStaged(directory, staging, stagingIdentity, failure);
            throw failure;
        } catch (NativeLegacyProtocol.ProtocolException failure) {
            var wrapped =
                new LegacyModelImportException(NativeLegacyStatus.RESULT_PROTOCOL, failure.getMessage(), failure);
            deleteStaged(directory, staging, stagingIdentity, wrapped);
            throw wrapped;
        } catch (IOException | RuntimeException failure) {
            var wrapped = new LegacyModelImportException(NativeLegacyStatus.PUBLICATION_FAILED,
                "Failed to stage legacy model", failure);
            deleteStaged(directory, staging, stagingIdentity, wrapped);
            throw wrapped;
        } finally {
            if (stagedChannel != null)
                try {
                    stagedChannel.close();
                } catch (IOException ignored) {
                }
        }
    }

    private static ManifestProto parseManifest(LegacyPayloadBuffer payload) {
        try {
            return ManifestProto.parseFrom(ProtoUtil.source(payload));
        } catch (IOException failure) {
            throw new NativeLegacyProtocol.ProtocolException("Legacy manifest payload is invalid", failure);
        } finally {
            Reference.reachabilityFence(payload);
        }
    }

    private static StringDataProto parseStringData(LegacyPayloadBuffer payload) {
        try {
            return StringDataProto.parseFrom(ProtoUtil.source(payload));
        } catch (IOException failure) {
            throw new NativeLegacyProtocol.ProtocolException("Legacy StringData payload is invalid", failure);
        } finally {
            Reference.reachabilityFence(payload);
        }
    }

    private static ModelDataProto parseModelData(LegacyPayloadBuffer payload) {
        try {
            return ModelDataProto.parseFrom(ProtoUtil.source(payload));
        } catch (IOException failure) {
            throw new NativeLegacyProtocol.ProtocolException("Legacy ModelData payload is invalid", failure);
        } finally {
            Reference.reachabilityFence(payload);
        }
    }

    private static void validateManifestIdentity(ManifestProto manifest, Hash256 modelId) {
        var properties = manifest.info().properties();
        if (properties.modelId().remaining() != Hash256.SIZE
            || !com.elfmcys.ysm.util.ProtoBytes.equals(modelId, properties.modelId())) {
            throw new NativeLegacyProtocol.ProtocolException("Legacy manifest identity does not match the descriptor");
        }
    }

    private static Image image(NativeLegacyProtocol.PayloadRecord record, LegacyPayloadBuffer payload) {
        var expected = switch (record.encoding()) {
            case PNG -> Image.Format.PNG;
            case JPEG -> Image.Format.JPEG;
            case WEBP -> Image.Format.WEBP;
            case AVIF -> Image.Format.AVIF;
            case ZTX -> Image.Format.ZTX;
            default -> throw new NativeLegacyProtocol.ProtocolException("Legacy image uses a non-image encoding");
        };
        try (var probed = Image.probe(payload)) {
            if (probed.format() != expected || probed.width() != record.meta0() || probed.height() != record.meta1()) {
                throw new NativeLegacyProtocol.ProtocolException("Legacy image descriptor does not match its bytes");
            }
            return probed.share();
        } catch (UnsupportedEncodingException failure) {
            throw new NativeLegacyProtocol.ProtocolException("Legacy image payload is invalid", failure);
        }
    }

    static void validateSound(NativeLegacyProtocol.PayloadRecord record, ByteBuffer payload) {
        var expected = record.encoding() == NativeLegacyProtocol.PayloadEncoding.OGG_VORBIS
            ? SupportedAudioProbe.Encoding.OGG_VORBIS : SupportedAudioProbe.Encoding.OGG_OPUS;
        var inspection = SupportedAudioProbe.inspect(payload);
        if (!inspection.playable()) {
            throw new NativeLegacyProtocol.ProtocolException(
                "Legacy sound stream is not valid declared Ogg media: " + inspection.diagnostic());
        }
        var actual = inspection.media();
        var sampleRate = Integer.toUnsignedLong(record.meta1());
        // The Java Vorbis probe sees an absolute final granule; native reports
        // decoder-emitted frames after applying a legal non-zero stream origin.
        boolean framesMatch = expected == SupportedAudioProbe.Encoding.OGG_VORBIS
            ? record.meta2() > 0 && record.meta2() <= actual.frames() : record.meta2() == actual.frames();
        if (actual.encoding() != expected || actual.channels() != record.meta0() || actual.sampleRate() != sampleRate
            || !framesMatch) {
            throw new NativeLegacyProtocol.ProtocolException("Legacy sound descriptor does not match its bytes");
        }
    }

    private static ManifestProto installSounds(ModelFileWriter writer, ManifestProto sourceManifest,
        List<LegacySound> sources, Hash256 modelId) throws IOException {
        var baseCommon = CommonProto.newBuilder(sourceManifest.commonAssets());
        if (!baseCommon.build().sounds().isEmpty()) {
            throw new NativeLegacyProtocol.ProtocolException("Legacy source projection contains current sound media");
        }
        var selected = new ArrayList<SoundProto>();
        var manifestBase = ManifestProto.newBuilder(sourceManifest);
        for (var source : sources) {
            int serial = ByteBuffer.wrap(modelId.bytes()).getInt() ^ source.record().logicalId();
            try (var generated = SoundMediaGeneration.normalize(source.payload(), serial, candidate -> {
                int firstChunkId = writer.nextBlobId();
                var candidateSounds = new ArrayList<>(selected);
                candidateSounds.add(candidate.sound(source.record().name(), firstChunkId));
                var candidateManifest = manifestWithSounds(manifestBase, baseCommon, candidateSounds);
                return writer.serializedLength(candidateManifest, candidate.chunks());
            })) {
                var candidate = generated.candidate();
                int firstChunkId = writer.nextBlobId();
                var ids = writer.addSoundChunks(candidate.chunks());
                if (ids.length == 0 || ids[0] != firstChunkId) {
                    throw new IOException("Legacy sound chunk numbering is not canonical");
                }
                selected.add(candidate.sound(source.record().name(), firstChunkId));
            }
        }
        return manifestWithSounds(manifestBase, baseCommon, selected);
    }

    private static ManifestProto manifestWithSounds(ManifestProto.Builder manifest, CommonProto.Builder baseCommon,
        List<SoundProto> sounds) {
        var common = CommonProto.newBuilder(baseCommon.build());
        sounds.stream().sorted(java.util.Comparator.comparing(SoundProto::name)).forEach(common::addSounds);
        return ManifestProto.newBuilder(manifest.build()).setCommonAssets(common.build()).build();
    }

    private static void requireBlobId(int actual, NativeLegacyProtocol.PayloadRecord record) throws IOException {
        if (actual != record.logicalId()) {
            throw new IOException("Legacy blob id does not match canonical order");
        }
    }

    private static void reopen(FileChannel channel, Hash256 modelId, NativeLegacyProtocol.Success success,
        byte[][] translatedPayloads) throws IOException {
        {
            var view = new ModelFileView(channel);
            if (!view.getModelHash().equals(modelId)) {
                throw new IOException("Legacy staged model identity changed");
            }
            view.getCommon().validateSoundContent(() -> false,
                new com.elfmcys.ysm.format.schema.file.FileChunkDataSource(Path.of("legacy-staging"), channel.size(),
                    () -> com.elfmcys.ysm.model.storage.CacheFile.borrow(channel)));
            var asset = view.getFileView().getAssetView();
            var records = success.descriptor().records();
            var payloads = success.payloads();
            var manifestChunk = asset.getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME);
            try (var stored = InlineChunkReader.readPayload(channel, manifestChunk, BufferType.ARRAY)) {
                if (!equal(stored.nio(), ByteBuffer.wrap(translatedPayloads[0]))) {
                    throw new IOException("Translated manifest logical bytes changed");
                }
            }
            for (var index = 1; index < records.size(); index++) {
                var record = records.get(index);
                if (record.kind() == NativeLegacyProtocol.PayloadKind.SOUND_STREAM) {
                    continue;
                }
                var chunkType = switch (record.kind()) {
                    case STRING_DATA, MODEL_DATA, BLOB_IMAGE ->
                        AssetFileConstant.BLOB_CHUNK_PREFIX + Integer.toUnsignedLong(record.logicalId());
                    case NAMED_IMAGE -> record.name();
                    case SOUND_STREAM -> throw new AssertionError();
                    case MANIFEST -> throw new IOException("Legacy result contains an extra manifest record");
                };
                if (chunkType == null) {
                    continue;
                }
                var chunk = asset.getChunkInfo(chunkType);
                if (chunk == null) {
                    throw new IOException("Legacy staged chunk is missing: " + chunkType);
                }
                if (record.kind() == NativeLegacyProtocol.PayloadKind.STRING_DATA
                    || record.kind() == NativeLegacyProtocol.PayloadKind.MODEL_DATA) {
                    if (!chunk.encoding().equals("zstd") || chunk.decodeSize() != translatedPayloads[index].length) {
                        throw new IOException("Legacy protobuf blob storage is not mandatory zstd");
                    }
                } else if (!chunk.encoding().equals(record.encoding().name())
                    || chunk.decodeSize() != packedImageSize(record) || chunk.size() != record.payloadSize()) {
                    throw new IOException("Legacy image storage metadata changed");
                }
                try (var logical = InlineChunkReader.readPayload(channel, chunk, BufferType.ARRAY)) {
                    var expected = translatedPayloads[index] == null ? payloads.get(index).nio()
                        : ByteBuffer.wrap(translatedPayloads[index]);
                    if (!equal(logical.nio(), expected)) {
                        throw new IOException("Legacy staged logical payload changed: " + chunkType);
                    }
                }
            }
        }
    }

    private static boolean equal(ByteBuffer left, ByteBuffer right) {
        return left.duplicate().equals(right.duplicate());
    }

    private static int packedImageSize(NativeLegacyProtocol.PayloadRecord record) {
        return (record.meta0() << 16) | (record.meta1() & 0xffff);
    }

    private static void deleteStaged(com.elfmcys.ysm.util.DirectoryAccess directory, String name, Object identity,
        Throwable failure) {
        if (name == null || identity == null) {
            return;
        }
        try {
            directory.delete(name, identity, false);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private record LegacySound(NativeLegacyProtocol.PayloadRecord record, LegacyPayloadBuffer payload) {
    }
}
