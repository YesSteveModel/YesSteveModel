package com.elfmcys.ysm.format.container;

import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceMap;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceMaps;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceOpenHashMap;
import java.util.Arrays;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Immutable container metadata. Use AssetContainerReader for parsing and ChunkDataSource for payload I/O. */
public final class AssetContainerView {
    private final int minorVer;
    private final int patchVer;
    private final String qualifierVer;
    private final String schema;
    private final Hash256 containerId;
    private final int alignmentShift;
    private final int containerPreambleSize;
    private final Short2ReferenceMap<String> schemaProperties;
    private final Object2ReferenceMap<String, ChunkInfo> chunkTable;

    AssetContainerView(int minorVer, int patchVer, String qualifierVer, String schema, Hash256 containerId,
        int alignmentShift, int containerPreambleSize, Short2ReferenceMap<String> schemaProperties,
        Object2ReferenceMap<String, ChunkInfo> chunkTable) {
        this.minorVer = minorVer;
        this.patchVer = patchVer;
        this.qualifierVer = qualifierVer;
        this.schema = schema;
        this.containerId = containerId;
        this.alignmentShift = alignmentShift;
        this.containerPreambleSize = containerPreambleSize;
        this.schemaProperties = Short2ReferenceMaps.unmodifiable(new Short2ReferenceOpenHashMap<>(schemaProperties));
        this.chunkTable = Object2ReferenceMaps.unmodifiable(new Object2ReferenceOpenHashMap<>(chunkTable));
    }

    public int getMinorVer() {
        return minorVer;
    }

    public int getPatchVer() {
        return patchVer;
    }

    public String getQualifierVer() {
        return qualifierVer;
    }

    @Nullable
    public ChunkInfo getChunkInfo(String type) {
        return chunkTable.get(type);
    }

    public int getAlignmentShift() {
        return alignmentShift;
    }

    public int getContainerPreambleSize() {
        return containerPreambleSize;
    }

    public Object2ReferenceMap<String, ChunkInfo> getChunkTable() {
        return chunkTable;
    }

    public String getSchema() {
        return schema;
    }

    public Hash256 getContainerId() {
        return containerId;
    }

    @Nullable
    public String getSchemaProperty(short value) {
        return schemaProperties.get(value);
    }

    public boolean sameContents(AssetContainerView other) {
        return this == other
            || minorVer == other.minorVer && patchVer == other.patchVer && qualifierVer.equals(other.qualifierVer)
                && schema.equals(other.schema) && containerId.equals(other.containerId)
                && alignmentShift == other.alignmentShift && containerPreambleSize == other.containerPreambleSize
                && schemaProperties.keySet().equals(other.schemaProperties.keySet())
                && schemaProperties.short2ReferenceEntrySet().stream()
                    .allMatch(entry -> entry.getValue().equals(other.schemaProperties.get(entry.getShortKey())))
                && chunkTable.keySet().equals(other.chunkTable.keySet()) && chunkTable.object2ReferenceEntrySet()
                    .stream().allMatch(entry -> entry.getValue().equals(other.chunkTable.get(entry.getKey())));
    }

    public record ChunkInfo(String type, String encoding, int offset, int size, int decodeSize, int flags,
        int alignSize, int alignmentShift, byte @Nullable [] hash) {
        public ChunkInfo {
            hash = hash == null ? null : hash.clone();
        }

        @Override
        public byte @Nullable [] hash() {
            return hash == null ? null : hash.clone();
        }

        @Override
        public boolean equals(Object object) {
            return this == object || object instanceof ChunkInfo other && type.equals(other.type)
                && encoding.equals(other.encoding) && offset == other.offset && size == other.size
                && decodeSize == other.decodeSize && flags == other.flags && alignSize == other.alignSize
                && alignmentShift == other.alignmentShift && Arrays.equals(hash, other.hash);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(type, encoding, offset, size, decodeSize, flags, alignSize, alignmentShift)
                + Arrays.hashCode(hash);
        }
    }
}
