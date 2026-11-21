package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Values for the private CACHE/f layout. Index results are candidates, not file or content validation. */
public final class CacheStorageTypes {
    private CacheStorageTypes() {}

    public enum Pool {
        CONVERTED("c"), BAKE("b"), REMOTE("r"), PREVIEW("p");

        private final String directory;

        Pool(String directory) {
            this.directory = directory;
        }

        public String directory() {
            return directory;
        }
    }

    /** The long carries all 64 bits; negative Java values are valid IDs. */
    public static String formatFileId(long fileId) {
        return HexFormat.of().toHexDigits(fileId);
    }

    public static long parseFileId(String text) {
        Objects.requireNonNull(text);
        if (text.length() != 16 || !text.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f'))
            throw new IllegalArgumentException("Invalid cache file ID");
        return Long.parseUnsignedLong(text, 16);
    }

    public record Location(long fileId, UUID instance) {
        public Location {
            Objects.requireNonNull(instance);
        }
    }

    public record File(Location location, SharedArrayBuffer metadata) {
        public File {
            Objects.requireNonNull(location);
            Objects.requireNonNull(metadata);
        }
    }

    public record Converted(Location location, String scope, String cacheVersion, ModelFileIdentity identity,
        boolean reusable) {
        public Converted {
            Objects.requireNonNull(location);
            NativeStorageResult.key(scope);
            NativeStorageResult.key(cacheVersion);
            Objects.requireNonNull(identity);
        }
    }

    public record Source(String source, ModelFileIdentity identity, Optional<Converted> converted,
        SharedArrayBuffer observation, SharedArrayBuffer metadata) {
        public Source {
            NativeStorageResult.key(source);
            if (source.isEmpty())
                throw new IllegalArgumentException("Empty source key");
            Objects.requireNonNull(identity);
            Objects.requireNonNull(converted);
            Objects.requireNonNull(observation);
            Objects.requireNonNull(metadata);
            if (observation.size() + (long)metadata.size() > StorageResult.MAXIMUM_BYTES)
                throw new IllegalArgumentException("Source payload exceeds byte bound");
            if (converted.isPresent() && (!converted.get().identity().equals(identity) || !converted.get().reusable()))
                throw new IllegalArgumentException("Converted source identity mismatch");
        }
    }

    public record SourceReference(String source, ModelFileIdentity identity) {
        public SourceReference {
            NativeStorageResult.key(source);
            if (source.isEmpty())
                throw new IllegalArgumentException("Empty source key");
            Objects.requireNonNull(identity);
        }
    }

    public record Scope(String scope, boolean complete) {
        public Scope {
            NativeStorageResult.key(scope);
        }
    }

    public enum ReferenceState {
        REFERENCED, UNREFERENCED, UNKNOWN
    }

    /** Absence is conclusive only within every registered source domain's maintained coverage. */
    public record References(ReferenceState state, long domains, long incompleteDomains) {
        public References {
            Objects.requireNonNull(state);
            if (domains < 0 || incompleteDomains < 0 || incompleteDomains > domains)
                throw new IllegalArgumentException("Invalid reference coverage");
        }
    }

    public record Maintenance(OptionalLong lastSuccessUtc, long resetRevision, boolean pendingAll) {
        public Maintenance {
            Objects.requireNonNull(lastSuccessUtc);
            if (lastSuccessUtc.isPresent() && lastSuccessUtc.getAsLong() < 0)
                throw new IllegalArgumentException("Negative maintenance time");
        }
    }

    public enum BakeKind {
        GEOMETRY, ANIMATION
    }

    public record Partition(BakeKind kind, String cacheVersion, Hash256 container, String binding, String purpose) {
        public Partition {
            Objects.requireNonNull(kind);
            NativeStorageResult.key(cacheVersion);
            Objects.requireNonNull(container);
            NativeStorageResult.key(binding);
            NativeStorageResult.key(purpose);
        }

        String[] fields() {
            return new String[] {cacheVersion, container.toString(), binding, purpose};
        }
    }

    /** Identity supplied independently by the consumer; no separate host engine ABI gate is introduced. */
    public record BakeKey(Partition partition, String exact, String abi, String capability, String enginePackage,
        String compilation, String frozen) {
        public BakeKey {
            Objects.requireNonNull(partition);
            NativeStorageResult.key(exact);
            NativeStorageResult.key(abi);
            NativeStorageResult.key(capability);
            NativeStorageResult.key(enginePackage);
            NativeStorageResult.key(compilation);
            NativeStorageResult.key(frozen);
        }

        String[] fields() {
            return new String[] {partition.cacheVersion(), partition.container().toString(), partition.binding(),
                partition.purpose(), exact, abi, capability, enginePackage, compilation, frozen};
        }
    }

    public record BakeObject(File file, BakeKey key) {
        public BakeObject {
            Objects.requireNonNull(file);
            Objects.requireNonNull(key);
        }
    }
}
