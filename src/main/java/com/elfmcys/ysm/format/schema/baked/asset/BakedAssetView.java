package com.elfmcys.ysm.format.schema.baked.asset;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.ChunkDecoding;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import com.elfmcys.ysm.format.schema.file.ChannelReadPhase;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationEntryProto;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;
import com.elfmcys.ysm.proto.baked.asset.BakedAssetManifestProto;
import com.elfmcys.ysm.proto.baked.asset.BakedProgramInputProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import dev.semaweave.ProgramKey;
import dev.semaweave.program.ReadOnlyBytes;
import com.elfmcys.ysm.util.ProtoBytes;
import com.elfmcys.ysm.util.ProtoUtil;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.version.VersionCompatibility;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

/** Metadata view for independent animation input. Animation and program payloads remain unopened. */
public final class BakedAssetView {
    private final AssetContainerView assetView;
    private final BakedAssetManifestProto manifest;
    private final Map<String, BakedAnimationEntryProto> animations;
    private final Map<Integer, BakedProgramInputProto> programs;
    private final List<ProgramInputDescriptor> descriptors;
    private final long fileSize;

    public BakedAssetView(SeekableByteChannel file) throws IOException {
        fileSize = file.size();
        assetView = AssetContainerReader.read(file);
        validateCompleteFile(file, assetView);
        if (!BakedAssetConstant.SCHEMA_ID.equals(assetView.getSchema())) {
            throw new IOException("Schema ID mismatch: " + assetView.getSchema());
        }
        var version = assetView.getSchemaProperty(BakedAssetConstant.PROP_VERSION);
        if (version == null)
            throw new IOException("Version property not found");
        if (!VersionCompatibility.isCompatible(BakedAssetConstant.CURRENT_VERSION.toString(), version, (current,
            candidate) -> new DefaultArtifactVersion(current).equals(new DefaultArtifactVersion(candidate)))) {
            throw new UnsupportedEncodingException("Unsupported baked asset version: \"" + version + "\"");
        }
        var manifestChunk = assetView.getChunkInfo(BakedAssetConstant.MANIFEST_CHUNK_NAME);
        if (manifestChunk == null) {
            throw new IOException("Baked asset contains no manifest");
        }
        if (!manifestChunk.encoding().isEmpty() || manifestChunk.decodeSize() != 0 || manifestChunk.size() == 0) {
            throw new IOException("Invalid baked asset manifest chunk");
        }
        try (var data = InlineChunkReader.readPayload(file, manifestChunk, BufferType.ARRAY)) {
            if (data.size() == 0) {
                throw new IOException("Baked asset contains no manifest");
            }
            if (!(data instanceof ArrayBuffer arrayBuffer)) {
                throw new IOException("Baked asset manifest is not array-backed");
            }
            manifest = BakedAssetManifestProto.parseFrom(ProtoUtil.source(arrayBuffer));
        }
        validateHash(manifest.containerId(), "container id");
        validateHash(manifest.inputHash(), "input hash");
        if (manifest.renderTargetId().isBlank()) {
            throw new IOException("Baked asset contains no render target id");
        }
        var index = new LinkedHashMap<String, BakedAnimationEntryProto>();
        var expectedChunks = new HashSet<String>();
        expectedChunks.add(AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
        expectedChunks.add(BakedAssetConstant.MANIFEST_CHUNK_NAME);
        var animationIndex = 0;
        for (var entry : manifest.animations()) {
            validateHash(entry.sourceHash(), "animation source hash");
            var expectedChunkName = BakedAssetConstant.ANIM_CHUNK_PREFIX + "%06d".formatted(animationIndex++);
            var chunk = assetView.getChunkInfo(entry.chunkName());
            if (entry.name().isBlank() || entry.sourceFile().isBlank() || !entry.chunkName().equals(expectedChunkName)
                || chunk == null || !ChunkDecoding.isZstd(chunk) || chunk.size() == 0 || chunk.decodeSize() == 0
                || !expectedChunks.add(entry.chunkName())
                || index.putIfAbsent(BakedAnimationSet.key(entry.sourceFile(), entry.name()), entry) != null) {
                throw new IOException("Invalid baked animation index entry: " + entry.name());
            }
        }
        var inputIndex = new LinkedHashMap<Integer, BakedProgramInputProto>();
        var inputDescriptors = new ArrayList<ProgramInputDescriptor>();
        var inputLocations = new HashSet<ModelProgramInventory.SemanticLocation>();
        var inputKeys = new HashSet<ProgramKey>();
        for (var entry : manifest.programs()) {
            validateHash(entry.contentHash(), "program content hash");
            var expectedChunkName = BakedAssetConstant.PROGRAM_CHUNK_PREFIX + "%06d".formatted(inputDescriptors.size());
            var chunk = assetView.getChunkInfo(entry.chunkName());
            if (entry.id() != inputDescriptors.size() || entry.byteLength() < 0
                || !entry.targetId().equals(manifest.renderTargetId()) || !entry.chunkName().equals(expectedChunkName)
                || chunk == null || !ChunkDecoding.isZstd(chunk) || chunk.size() <= 0
                || chunk.decodeSize() != entry.byteLength() || !expectedChunks.add(entry.chunkName()))
                throw new IOException("Invalid program input index entry: " + entry.id());
            try {
                var location = new ModelProgramInventory.SemanticLocation(entry.targetId(),
                    ExpressionSet.valueOf(entry.expressionSet()), entry.location(), ProgramRole.valueOf(entry.role()),
                    entry.discriminator());
                var key = new ProgramKey(ReadOnlyBytes.copyOf(ProtoBytes.copy(entry.programKey())));
                var descriptor = new ProgramInputDescriptor(entry.id(), location, key,
                    new Hash256(ProtoBytes.copy(entry.contentHash())), entry.byteLength());
                if (!inputLocations.add(location) || !inputKeys.add(key))
                    throw new IOException("Duplicate program input identity: " + entry.id());
                inputDescriptors.add(descriptor);
                inputIndex.put(entry.id(), entry);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid program input descriptor: " + entry.id(), invalid);
            }
        }
        if (assetView.getChunkTable().size() != expectedChunks.size()
            || !assetView.getChunkTable().keySet().containsAll(expectedChunks)) {
            throw new IOException("Baked asset chunk table does not match its animation index");
        }
        animations = Map.copyOf(index);
        programs = Map.copyOf(inputIndex);
        descriptors = List.copyOf(inputDescriptors);
    }

    public boolean matches(Hash256 containerId, String targetId, Hash256 inputHash) {
        return ProtoBytes.equals(containerId, manifest.containerId()) && targetId.equals(manifest.renderTargetId())
            && ProtoBytes.equals(inputHash, manifest.inputHash());
    }

    @Nullable
    public BakedAnimationProto readAnimation(SeekableByteChannel file, String sourceFile, String animationName)
        throws IOException {
        var entry = animations.get(BakedAnimationSet.key(sourceFile, animationName));
        if (entry == null) {
            return null;
        }
        var chunk = assetView.getChunkInfo(entry.chunkName());
        if (chunk == null) {
            throw new IOException("Baked animation chunk is missing: " + entry.chunkName());
        }
        try (
            var operation = ResourceDiagnostics.operation("animation cache payload read",
                manifest.renderTargetId() + "/" + animationName);
            var data = InlineChunkReader.readPayload(file, chunk, BufferType.ARRAY)) {
            if (data.size() == 0) {
                throw new IOException("Baked animation chunk is missing: " + entry.chunkName());
            }
            if (!(data instanceof ArrayBuffer arrayBuffer)) {
                throw new IOException("Baked animation is not array-backed");
            }
            if (!ProtoBytes.equals(ModelHashing.blake3(arrayBuffer), entry.sourceHash())) {
                throw new IOException("Baked animation source hash mismatch: " + animationName);
            }
            var animation = BakedAnimationProto.parseFrom(ProtoUtil.source(arrayBuffer));
            if (!animationName.equals(animation.name())) {
                throw new IOException("Baked animation name mismatch: " + animationName);
            }
            operation.succeeded();
            return animation;
        }
    }

    public ProgramProto readProgram(SeekableByteChannel file, int id) throws IOException {
        var entry = programs.get(id);
        if (entry == null)
            throw new IOException("Program input is missing: " + id);
        var chunk = assetView.getChunkInfo(entry.chunkName());
        try (var data = InlineChunkReader.readPayload(file, chunk, BufferType.ARRAY)) {
            if (!(data instanceof ArrayBuffer arrayBuffer) || data.size() != entry.byteLength()
                || !ProtoBytes.equals(ModelHashing.blake3(data), entry.contentHash()))
                throw new IOException("Program input payload does not match its descriptor: " + id);
            return ProgramProto.parseFrom(ProtoUtil.source(arrayBuffer));
        }
    }

    public BakedAnimationSet animationSet(Path path) {
        return animationSet(() -> FileChannel.open(path, StandardOpenOption.READ));
    }

    public BakedAnimationSet animationSet(ChannelOpener opener) {
        var files = new LinkedHashMap<String, List<String>>();
        for (var entry : manifest.animations()) {
            files.computeIfAbsent(entry.sourceFile(), ignored -> new ArrayList<>()).add(entry.name());
        }
        files.replaceAll((file, names) -> List.copyOf(names));
        return new BakedAnimationSet(files, descriptors, new BakedAnimationSet.Source() {
            @Override
            public BakedAnimationProto load(String sourceFile, String name) throws IOException {
                try (var phase = openReadPhase()) {
                    return phase.load(sourceFile, name);
                }
            }

            @Override
            public ProgramProto loadProgram(int id) throws IOException {
                try (var phase = openReadPhase()) {
                    return phase.loadProgram(id);
                }
            }

            @Override
            public BakedAnimationSet.ReadSource openReadPhase() {
                return new BakedAnimationSet.ReadSource() {
                    private ChannelReadPhase phase;
                    private boolean closed;
                    private boolean opening;

                    @Override
                    public BakedAnimationProto load(String sourceFile, String name) throws IOException {
                        return channel().read(channel -> readAnimation(channel, sourceFile, name));
                    }

                    @Override
                    public ProgramProto loadProgram(int id) throws IOException {
                        return channel().read(channel -> readProgram(channel, id));
                    }

                    private ChannelReadPhase channel() throws IOException {
                        synchronized (this) {
                            while (opening && !closed) {
                                try {
                                    wait();
                                } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                    throw new InterruptedIOException("Animation channel admission interrupted");
                                }
                            }
                            if (closed)
                                throw new ClosedChannelException();
                            if (phase != null)
                                return phase;
                            opening = true;
                        }
                        final ChannelReadPhase admitted;
                        try {
                            admitted = ChannelReadPhase.open(opener::open, channel -> {
                                if (channel.size() != fileSize)
                                    throw new IOException("Baked animation file length changed after admission");
                            });
                        } catch (IOException | RuntimeException | Error failure) {
                            synchronized (this) {
                                opening = false;
                                closed = true;
                                notifyAll();
                            }
                            throw failure;
                        }
                        synchronized (this) {
                            opening = false;
                            notifyAll();
                            if (!closed) {
                                phase = admitted;
                                return phase;
                            }
                        }
                        admitted.close();
                        throw new ClosedChannelException();
                    }

                    @Override
                    public void close() throws IOException {
                        final ChannelReadPhase released;
                        synchronized (this) {
                            closed = true;
                            released = phase;
                            notifyAll();
                        }
                        if (released != null)
                            released.close();
                    }
                };
            }
        });
    }

    @FunctionalInterface
    public interface ChannelOpener {
        java.nio.channels.SeekableByteChannel open() throws IOException;
    }

    private static void validateHash(ByteBuffer value, String name) throws IOException {
        if (value.remaining() != Hash256.SIZE) {
            throw new IOException("Invalid baked asset " + name);
        }
    }

    private static void validateCompleteFile(SeekableByteChannel file, AssetContainerView view) throws IOException {
        var fileSize = file.size();
        long lastByte = 0;
        for (var chunk : view.getChunkTable().values()) {
            var end = (long)chunk.offset() + chunk.size();
            if (end > fileSize) {
                throw new IOException("Baked asset chunk exceeds file boundary: " + chunk.type());
            }
            lastByte = Math.max(lastByte, end);
        }
        if (lastByte != fileSize) {
            throw new IOException("Baked asset contains trailing or missing chunk data");
        }
    }
}
