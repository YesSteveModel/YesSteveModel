package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.natives.NativeCancellation;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Owns persistent player facts at one fixed business location. Java supplies complete validated values and donor
 * intent.
 *
 * <p>
 * Execution: operations and close are serialized on this object. Cancellation may be requested concurrently through the
 * supplied token. Each operation copies its result before returning; no borrowed result survives the call.
 *
 * <p>
 * Seam: implements player persistence in
 * [preferences](docs/architecture/model-management/storage/preferences.md#seam-contract).
 */
public final class NativePlayerData extends NativeBusinessObject {
    private static final Function<State, NativePlayerData> FACTORY = NativePlayerData::new;

    public enum PreferenceKind {
        ROAMING, FAVOURITE, LOCAL_SELECTION
    }

    public record PreferenceKey(UUID player, PreferenceKind kind, String identity) {
        public PreferenceKey {
            Objects.requireNonNull(player);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(identity);
            switch (kind) {
                case ROAMING -> PublishedModelId.parse(identity);
                case FAVOURITE -> ModelId.parse(identity);
                case LOCAL_SELECTION -> {
                    if (!identity.isEmpty())
                        throw new IllegalArgumentException("Local selection has no model key");
                }
            }
        }

        public static PreferenceKey roaming(UUID player, PublishedModelId identity) {
            return new PreferenceKey(player, PreferenceKind.ROAMING, identity.toString());
        }

        public static PreferenceKey favourite(UUID player, ModelId identity) {
            return new PreferenceKey(player, PreferenceKind.FAVOURITE, identity.toString());
        }

        public static PreferenceKey selection(UUID player) {
            return new PreferenceKey(player, PreferenceKind.LOCAL_SELECTION, "");
        }
    }

    /** The value is a read-only Java snapshot independent of this owner. */
    public record Preference(PreferenceKey key, SharedArrayBuffer value, long updatedUtcSeconds) {
    }

    private NativePlayerData(State state) {
        super(state);
    }

    /** The caller fixes the root and creates its parent directory before opening. */
    public static NativePlayerData open(Path root, NativeCancellation cancelled) {
        var location = Objects.requireNonNull(root).toAbsolutePath().normalize().toString();
        return Objects.requireNonNull(cancelled)
            .borrow(token -> NativeStorageResult.open(() -> nOpen(location, token), handle -> adopt(handle, FACTORY)));
    }

    public StorageResult<Preference> readPreference(PreferenceKey key, NativeCancellation cancelled) {
        Objects.requireNonNull(key);
        return execute(cancelled,
            token -> nReadPreference(get(), key.player().toString(), key.kind().ordinal(), key.identity(), token),
            result -> preferences(result, key.player(), key.kind()));
    }

    /** Replaces one complete value atomically. Runtime acceptance and file publication remain separate decisions. */
    public StorageResult<Preference> putPreference(PreferenceKey key, UniBuffer value, long utcSeconds,
        NativeCancellation cancelled) {
        Objects.requireNonNull(key);
        if (utcSeconds < 0)
            throw new IllegalArgumentException("Storage event time must be nonnegative");
        var bytes = NativeStorageResult.bytes(value);
        return execute(cancelled, token -> nPutPreference(get(), key.player().toString(), key.kind().ordinal(),
            key.identity(), bytes, utcSeconds, token), result -> preferences(result, key.player(), key.kind()));
    }

    /** Atomically returns the existing complete target or inserts and returns the supplied first value. */
    public StorageResult<Preference> insertIfAbsentAndRead(PreferenceKey key, UniBuffer value, long utcSeconds,
        NativeCancellation cancelled) {
        Objects.requireNonNull(key);
        if (utcSeconds < 0)
            throw new IllegalArgumentException("Storage event time must be nonnegative");
        var bytes = NativeStorageResult.bytes(value);
        return execute(cancelled, token -> nInsertIfAbsentAndRead(get(), key.player().toString(), key.kind().ordinal(),
            key.identity(), bytes, utcSeconds, token), result -> preferences(result, key.player(), key.kind()));
    }

    /** Returns a bounded identity-ordered observation; an empty page ends traversal and calls share no snapshot. */
    public StorageResult<Preference> pagePreferences(UUID player, PreferenceKind kind, String afterIdentity,
        NativeCancellation cancelled) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(kind);
        var after = NativeStorageResult.key(afterIdentity);
        if (kind == PreferenceKind.LOCAL_SELECTION)
            throw new IllegalArgumentException("Local selection is a single record");
        return execute(cancelled, token -> nPagePreferences(get(), player.toString(), kind.ordinal(), after, token),
            result -> preferences(result, player, kind));
    }

    private static List<Preference> preferences(NativeStorageResult result, UUID player, PreferenceKind kind) {
        return IntStream.range(0, result.count()).mapToObj(index -> {
            final PreferenceKey key;
            var identity = result.preferenceIdentity(index);
            try {
                key = new PreferenceKey(player, kind, identity);
            } catch (IllegalArgumentException failure) {
                throw NativeStorageResult.invalidValue(failure);
            }
            return new Preference(key, result.preferenceValue(index), result.preferenceTime(index));
        }).toList();
    }

    private static native long nOpen(String root, long cancelled);

    private static native long nReadPreference(long handle, String player, int kind, String identity, long cancelled);

    private static native long nPutPreference(long handle, String player, int kind, String identity, byte[] value,
        long utcSeconds, long cancelled);

    private static native long nInsertIfAbsentAndRead(long handle, String player, int kind, String identity,
        byte[] value, long utcSeconds, long cancelled);

    private static native long nPagePreferences(long handle, String player, int kind, String afterIdentity,
        long cancelled);
}
