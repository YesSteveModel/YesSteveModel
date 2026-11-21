package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.StorageDeliveryException;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.natives.storage.NativePlayerData;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Map;
import java.util.ArrayList;
import com.elfmcys.ysm.natives.NativeCancellation;
import java.util.function.Function;

/**
 * Provides UUID-scoped preferences in one fixed data region.
 *
 * <p>
 * Execution: operations never wait for I/O on the caller. Results describe database effects only; the caller owns
 * runtime adoption in its own tick. Reads observe their logical admission position. No operation resolves the current
 * player or model at completion. The region owner closes DatabaseWork after the write buffer has drained. Roaming
 * operations retain the payload's stable, read-only name/value arrays. Callers must copy before mutation.
 *
 * <p>
 * Seam: implements [player preferences](docs/architecture/model-management/storage/preferences.md#偏好读写与首次继承).
 */
public final class PlayerPreferences {
    private final DatabaseWork work;
    private final DatabaseWork.Resource<NativePlayerData> resource;
    private CacheDirectoryGuard guard;
    private final StorageWriteBuffer writes;

    public PlayerPreferences(Path dataRoot, DatabaseWork work) {
        var root = Objects.requireNonNull(dataRoot).toAbsolutePath().normalize();
        this.work = Objects.requireNonNull(work);
        resource = work.createResource(cancelled -> {
            try {
                if (guard == null)
                    guard = CacheDirectoryGuard.open(root);
                guard.verify();
                return NativePlayerData.open(guard.root(), cancelled);
            } catch (IOException | SecurityException failure) {
                throw new StorageResult.OpenException(accessFailure());
            }
        });
        writes = new StorageWriteBuffer(this);
    }

    DatabaseWork databaseWork() {
        return work;
    }

    public StorageWriteBuffer writes() {
        return writes;
    }

    int size(StorageWriteBuffer.Key key, int payloadBytes) {
        return 256 + key.toString().length() * 2 + payloadBytes * 4;
    }

    private <T> StorageResult<T> execute(DatabaseWork.Operation<NativePlayerData, T> operation,
        NativeCancellation token) {
        return resource.run((database, cancelled) -> {
            try {
                guard.verify();
            } catch (IOException | SecurityException failure) {
                return accessFailure();
            }
            return operation.run(database, cancelled);
        }, token);
    }

    private static <T> StorageResult<T> accessFailure() {
        return new StorageResult<>(StorageResult.Outcome.FAILED, StorageResult.Failure.ACCESS_IO, false, List.of());
    }

    /** One complete Local intent; intrinsic has no texture override. Missing remains a result branch. */
    public record LocalSelection(PublishedModelId identity, String texture) {
        public LocalSelection {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(texture);
            PreferenceCodec.utf8(texture);
            if (texture.length() > 256 || texture.indexOf('\0') >= 0
                || identity.isIntrinsicDefault() && !texture.isEmpty())
                throw new IllegalArgumentException("Invalid Local selection texture");
        }
    }

    /** Reads without first filling Missing. Layout rejection preserves the stored value. */
    public StorageQuery<NamedRoamingSnapshot> readRoaming(UUID player, PublishedModelId identity,
        Set<String> allowedFields) {
        var key = NativePlayerData.PreferenceKey.roaming(player, identity);
        var fields = Set.copyOf(allowedFields);
        var logical = new StorageWriteBuffer.RoamingKey(player, identity);
        return writes.query(logical, 512,
            snapshot -> snapshot.containsKey(logical)
                ? ignored -> memory(() -> layout((NamedRoamingSnapshot)snapshot.get(logical), fields))
                : cancelled -> execute((database, token) -> decode(database.readPreference(key, token),
                    value -> layout(PreferenceCodec.roaming(value), fields)), cancelled));
    }

    /**
     * Fills only a Missing target, using this observation's best lower version. The immutable field set must describe
     * the target layout. An invalid selected donor fails the operation; older donors are not fallbacks.
     */
    public StorageWriteBuffer.Operation<NamedRoamingSnapshot> restoreRoaming(UUID player, PublishedModelId identity,
        Set<String> allowedFields) {
        var key = NativePlayerData.PreferenceKey.roaming(player, identity);
        var fields = Set.copyOf(allowedFields);
        var logical = new StorageWriteBuffer.RoamingKey(player, identity);
        return writes.restore(logical, 512, snapshot -> {
            if (snapshot.containsKey(logical))
                return ignored -> memory(() -> layout((NamedRoamingSnapshot)snapshot.get(logical), fields));
            return cancelled -> execute((database, token) -> {
                var existing = database.readPreference(key, token);
                if (existing.outcome() != StorageResult.Outcome.MISSING)
                    return decode(existing, value -> layout(PreferenceCodec.roaming(value), fields));
                NativePlayerData.Preference donor = null;
                ModelId donorId = null;
                if (identity instanceof PublishedModelId.Model target && target.hash().version().isPresent()) {
                    var after = "";
                    while (true) {
                        var page =
                            database.pagePreferences(player, NativePlayerData.PreferenceKind.ROAMING, after, token);
                        if (!page.succeeded())
                            return page.map(row -> (NamedRoamingSnapshot)null);
                        for (var persisted : page.rows()) {
                            var logicalRow = new StorageWriteBuffer.RoamingKey(player,
                                PublishedModelId.parse(persisted.key().identity()));
                            var row = snapshot.containsKey(logicalRow)
                                ? preference(persisted.key(), (NamedRoamingSnapshot)snapshot.get(logicalRow))
                                : persisted;
                            var candidate = PublishedModelId.parse(row.key().identity());
                            if (candidate instanceof PublishedModelId.Model model && lower(model.hash(), target.hash())
                                && (donorId == null || compareDonors(model.hash(), donorId) > 0)) {
                                donor = row;
                                donorId = model.hash();
                            }
                        }
                        if (page.rows().isEmpty())
                            break;
                        after = page.rows().get(page.rows().size() - 1).key().identity();
                    }
                }
                if (identity instanceof PublishedModelId.Model target && target.hash().version().isPresent())
                    for (var entry : snapshot.entrySet())
                        if (entry.getKey() instanceof StorageWriteBuffer.RoamingKey candidateKey
                            && candidateKey.player().equals(player)
                            && candidateKey.identity() instanceof PublishedModelId.Model model
                            && lower(model.hash(), target.hash())
                            && (donorId == null || compareDonors(model.hash(), donorId) > 0)) {
                            donor = preference(NativePlayerData.PreferenceKey.roaming(player, candidateKey.identity()),
                                (NamedRoamingSnapshot)entry.getValue());
                            donorId = model.hash();
                        }
                var payload = new NamedRoamingSnapshot(new String[0], new float[0]);
                if (donor != null) {
                    var initial = decode(new StorageResult<>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE,
                        false, List.of(donor)), value -> layout(PreferenceCodec.roaming(value), fields));
                    if (!initial.succeeded())
                        return initial;
                    payload = initial.rows().get(0);
                }
                var filled = database.insertIfAbsentAndRead(key, ArrayBuffer.borrow(PreferenceCodec.roaming(payload)),
                    Instant.now().getEpochSecond(), token);
                return decode(filled, value -> layout(PreferenceCodec.roaming(value), fields));
            }, cancelled);
        });
    }

    /** Writes one complete, structurally valid snapshot. Producer eligibility belongs to the caller. */
    public StorageWriteBuffer.Operation<NamedRoamingSnapshot> writeRoaming(UUID player, PublishedModelId identity,
        NamedRoamingSnapshot payload) {
        var snapshot = PreferenceCodec.snapshot(payload);
        var key = new StorageWriteBuffer.RoamingKey(player, identity);
        return writes.write(key, snapshot, size(key, 2 + snapshot.names().length * 37),
            roamingWrite(player, identity, snapshot), false);
    }

    StorageWriteBuffer.Work<NamedRoamingSnapshot> roamingWrite(UUID player, PublishedModelId identity,
        NamedRoamingSnapshot value) {
        return cancelled -> write(NativePlayerData.PreferenceKey.roaming(player, identity),
            PreferenceCodec.roaming(value), PreferenceCodec::roaming, cancelled);
    }

    /** Explicit empty is an ordering boundary, not removal of a queued snapshot. */
    public StorageWriteBuffer.Operation<NamedRoamingSnapshot> clearRoaming(UUID player, PublishedModelId identity) {
        var value = new NamedRoamingSnapshot(new String[0], new float[0]);
        var key = new StorageWriteBuffer.RoamingKey(player, identity);
        return writes.write(key, value, size(key, 2), roamingWrite(player, identity, value), true);
    }

    public StorageQuery<Boolean> readFavourite(UUID player, ModelId identity) {
        return read(new StorageWriteBuffer.FavouriteKey(player, identity),
            NativePlayerData.PreferenceKey.favourite(player, identity), PreferenceCodec::favourite);
    }

    public StorageWriteBuffer.Operation<Boolean> writeFavourite(UUID player, ModelId identity, boolean favourite) {
        var key = new StorageWriteBuffer.FavouriteKey(player, identity);
        return writes.write(key, favourite, size(key, 1),
            cancelled -> write(NativePlayerData.PreferenceKey.favourite(player, identity),
                new byte[] {(byte)(favourite ? 1 : 0)}, PreferenceCodec::favourite, cancelled),
            false);
    }

    public record Favourite(ModelId identity, boolean selected) {
    }

    /** One bounded page, including false members so that the caller can always advance the keyset. */
    public StorageQuery<Favourite> pageFavourites(UUID player, String afterIdentity) {
        Objects.requireNonNull(afterIdentity);
        return writes.query(null, 512, snapshot -> cancelled -> execute((database, token) -> {
            var page =
                database.pagePreferences(player, NativePlayerData.PreferenceKind.FAVOURITE, afterIdentity, token);
            if (!page.succeeded())
                return page.map(row -> (Favourite)null);
            var values = new TreeMap<String, Favourite>();
            for (var row : page.rows()) {
                var id = ModelId.parse(row.key().identity());
                var logical = new StorageWriteBuffer.FavouriteKey(player, id);
                if (!snapshot.containsKey(logical)) {
                    var decoded = decode(new StorageResult<>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE,
                        false, List.of(row)), PreferenceCodec::favourite);
                    if (!decoded.succeeded())
                        return decoded.map(value -> (Favourite)null);
                    values.put(id.toString(), new Favourite(id, decoded.rows().get(0)));
                }
            }
            for (var entry : snapshot.entrySet())
                if (entry.getKey() instanceof StorageWriteBuffer.FavouriteKey key && key.player().equals(player)
                    && key.identity().toString().compareTo(afterIdentity) > 0)
                    values.put(key.identity().toString(), new Favourite(key.identity(), (Boolean)entry.getValue()));
            return new StorageResult<>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE, false,
                values.values().stream().limit(StorageResult.MAXIMUM_ROWS).toList());
        }, cancelled));
    }

    public StorageQuery<LocalSelection> readSelection(UUID player) {
        return read(new StorageWriteBuffer.SelectionKey(player), NativePlayerData.PreferenceKey.selection(player),
            PreferenceCodec::selection);
    }

    public StorageWriteBuffer.Operation<LocalSelection> writeSelection(UUID player, LocalSelection selection) {
        var key = new StorageWriteBuffer.SelectionKey(player);
        return writes.write(key, selection, size(key, PreferenceCodec.selection(selection).length),
            cancelled -> write(NativePlayerData.PreferenceKey.selection(player), PreferenceCodec.selection(selection),
                PreferenceCodec::selection, cancelled),
            false);
    }

    private <T> StorageQuery<T> read(StorageWriteBuffer.Key logical, NativePlayerData.PreferenceKey key,
        Function<SharedArrayBuffer, T> decoder) {
        return writes.query(logical, 512,
            snapshot -> snapshot.containsKey(logical)
                ? ignored -> new StorageResult<>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE, false,
                    List.of((T)snapshot.get(logical)))
                : cancelled -> execute((database, token) -> decode(database.readPreference(key, token), decoder),
                    cancelled));
    }

    private <T> StorageResult<T> write(NativePlayerData.PreferenceKey key, byte[] value,
        Function<SharedArrayBuffer, T> decoder, NativeCancellation token) {
        return execute((database, cancelled) -> {
            var previous = decode(database.readPreference(key, cancelled), decoder);
            if (!previous.succeeded())
                return previous;
            return decode(
                database.putPreference(key, ArrayBuffer.borrow(value), Instant.now().getEpochSecond(), cancelled),
                decoder);
        }, token);
    }

    private static NativePlayerData.Preference preference(NativePlayerData.PreferenceKey key,
        NamedRoamingSnapshot value) {
        return new NativePlayerData.Preference(key,
            SharedArrayBuffer.copyOf(ArrayBuffer.borrow(PreferenceCodec.roaming(value))), 0);
    }

    private static <T> StorageResult<T> memory(java.util.function.Supplier<T> value) {
        try {
            return new StorageResult<>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE, false,
                List.of(value.get()));
        } catch (IllegalArgumentException failure) {
            return new StorageResult<>(StorageResult.Outcome.FAILED, StorageResult.Failure.CONTENT, false, List.of());
        }
    }

    private static NamedRoamingSnapshot layout(NamedRoamingSnapshot value, Set<String> fields) {
        if (!fields.containsAll(java.util.Arrays.asList(value.names())))
            throw new IllegalArgumentException("Roaming value does not match the target layout");
        return value;
    }

    private static boolean lower(ModelId candidate, ModelId target) {
        return candidate.family().equals(target.family()) && candidate.version().isPresent()
            && candidate.version().orElseThrow().compareTo(target.version().orElseThrow()) < 0;
    }

    private static int compareDonors(ModelId left, ModelId right) {
        var priority = left.version().orElseThrow().compareTo(right.version().orElseThrow());
        return priority == 0
            ? left.version().orElseThrow().toString().compareTo(right.version().orElseThrow().toString()) : priority;
    }

    private static <T> StorageResult<T> decode(StorageResult<NativePlayerData.Preference> result,
        Function<SharedArrayBuffer, T> decoder) {
        try {
            return result.map(row -> decoder.apply(row.value()));
        } catch (IllegalArgumentException | BufferUnderflowException failure) {
            return new StorageResult<>(StorageResult.Outcome.FAILED, StorageResult.Failure.CONTENT, result.committed(),
                List.of());
        } catch (RuntimeException | Error failure) {
            throw StorageDeliveryException.after(result, failure);
        }
    }
}
