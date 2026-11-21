package com.elfmcys.ysm.model.storage;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.named;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.fromMap;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.natives.storage.NativePlayerData;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import com.elfmcys.ysm.natives.NativeCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class PlayerPreferencesTest {
    @TempDir
    Path temp;
    static final UUID U = new UUID(0, 1);
    static final UUID V = new UUID(0, 2);
    static final Set<String> FIELDS = Set.of("value");

    @Test
    void sqliteAndDonorRestoreDoNotRetainDictionaryNumbering() throws Exception {
        var first = java.util.List.of("beta", "alpha");
        var fields = Set.of("alpha", "beta", "gamma");
        var initial = NamedRoamingSnapshot.fromPayload(first,
            new dev.semaweave.RoamingPayload(new short[] {0, 1}, new float[] {2F, -0F}));
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), initial));
        }
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            var restored = found(prefs, prefs.restoreRoaming(U, id("1.1.0"), fields).result());
            var next = java.util.List.of("alpha", "gamma", "beta");
            var numeric = restored.toPayload(next);
            assertArrayEquals(new short[] {2, 0}, numeric.fields());
            var decoded = named(NamedRoamingSnapshot.fromPayload(next, numeric));
            assertEquals(Set.of("alpha", "beta"), decoded.keySet());
            assertEquals(0x80000000, Float.floatToRawIntBits(decoded.get("alpha")));
            assertEquals(2F, decoded.get("beta"));
            assertThrows(IllegalArgumentException.class, () -> restored.toPayload(java.util.List.of("beta")));
        }
    }

    @ParameterizedTest
    @CsvSource({"1.2.0+9,1.2.0+10,1.3.0,9", "1.2.0+a,1.1.9,1.2.0+b,10", "1.2.0-rc.1,1.1.9,1.2.0,9"})
    void inheritsObservedHighestStrictlyLowerVersionWithAsciiBuildTie(String first, String second, String target,
        int expected) throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            committed(prefs, prefs.writeRoaming(U, id(first), payload(9)));
            committed(prefs, prefs.writeRoaming(U, id(second), payload(10)));
            committed(prefs, prefs.writeRoaming(V, id("1.2.9"), payload(99)));
            committed(prefs, prefs.writeRoaming(U, PublishedModelId.parse("other/model@1.2.9"), payload(98)));
            // Force the relevant family beyond one native keyset page.
            for (int index = 0; index < 65; index++)
                committed(prefs, prefs.writeRoaming(U, PublishedModelId.parse("aaa/record" + index), payload(97)));
            var restored = await(prefs, prefs.restoreRoaming(U, id(target), FIELDS).result());
            assertTrue(restored.committed(), restored.toString());
            assertEquals(named(payload(expected)), named(restored.rows().get(0)));
            assertEquals(named(payload(9)), named(found(prefs, prefs.readRoaming(U, id(first), FIELDS))));
            committed(prefs, prefs.writeFavourite(U, ModelId.parse("owner/model@" + first), true));
            assertEquals(StorageResult.Outcome.MISSING,
                await(prefs, prefs.readFavourite(U, ModelId.parse("owner/model@" + target))).outcome());
            assertEquals(true, found(prefs, prefs.readFavourite(U, ModelId.parse("owner/model@" + first))));
        }
    }

    @Test
    void explicitEmptyRolesSelectionAndFavouriteRemainIndependent() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            assertEquals(StorageResult.Outcome.MISSING, await(prefs, prefs.readSelection(U)).outcome());
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(1)));
            committed(prefs, prefs.clearRoaming(U, id("1.1.0")));
            assertTrue(named(found(prefs, prefs.restoreRoaming(U, id("1.2.0"), FIELDS).result())).isEmpty());
            committed(prefs, prefs.clearRoaming(U, id("1.0.0")));
            assertTrue(named(found(prefs, prefs.restoreRoaming(U, id("1.0.0"), FIELDS).result())).isEmpty());
            committed(prefs, prefs.writeRoaming(U, PublishedModelId.INTRINSIC_DEFAULT, payload(7)));
            committed(prefs, prefs.writeRoaming(V, id("1.0.0"), payload(8)));
            assertEquals(named(payload(7)),
                named(found(prefs, prefs.readRoaming(U, PublishedModelId.INTRINSIC_DEFAULT, FIELDS))));
            assertEquals(named(payload(8)), named(found(prefs, prefs.readRoaming(V, id("1.0.0"), FIELDS))));
            var model = new PlayerPreferences.LocalSelection(id("1.0.0"), "texture_中文");
            committed(prefs, prefs.writeSelection(U, model));
            committed(prefs,
                prefs.writeSelection(V, new PlayerPreferences.LocalSelection(PublishedModelId.INTRINSIC_DEFAULT, "")));
            assertEquals(model, found(prefs, prefs.readSelection(U)));
            assertTrue(found(prefs, prefs.readSelection(V)).identity().isIntrinsicDefault());
            var ordinary = ModelId.parse("owner/model@1.0.0");
            committed(prefs, prefs.writeFavourite(U, ordinary, true));
            committed(prefs, prefs.writeFavourite(V, ordinary, false));
            assertEquals(true, found(prefs, prefs.readFavourite(U, ordinary)));
            assertEquals(false, found(prefs, prefs.readFavourite(V, ordinary)));
            assertEquals(new PlayerPreferences.Favourite(ordinary, false),
                await(prefs, prefs.pageFavourites(V, "")).rows().get(0));
            assertTrue(
                named(found(prefs, prefs.restoreRoaming(U, PublishedModelId.parse("owner/model"), FIELDS).result()))
                    .isEmpty());
            assertTrue(
                named(found(prefs,
                    prefs
                        .restoreRoaming(U,
                            PublishedModelId.model(
                                ModelId.fromHash(new com.elfmcys.ysm.model.domain.Hash256(new byte[32]))),
                            FIELDS)
                        .result()))
                    .isEmpty());
            assertThrows(IllegalArgumentException.class,
                () -> new PlayerPreferences.LocalSelection(PublishedModelId.INTRINSIC_DEFAULT, "texture"));
            assertThrows(IllegalArgumentException.class,
                () -> new PlayerPreferences.LocalSelection(id("1.0.0"), "\ud800"));
        }
    }

    @Test
    void rejectsMalformedRecordsAndBestDonorWithoutOverwritingOrTryingOlderDonor() throws Exception {
        var path = temp.resolve("preferences-c2.sqlite");
        // These bytes are independent invalid fixtures, rather than output from the codec under test.
        var invalid = new byte[][] {{1, 65}, {1, 1, 0, 0, 0, 0, 0}, {1, 1, 1, (byte)0xff, 0, 0, 0, 0},
            {1, 2, 1, 'x', 0, 0, 0, 0, 1, 'x', 0, 0, 0, 0}, {1, 1, 1, 'x', 0x7f, (byte)0xc0, 0, 0},
            {1, 1, 1, 'x', 0x7f, (byte)0x80, 0, 0}, {1, 1, 1, 'x'}, {1, 0, 42}, longName()};
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(path.getParent(), work);
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(1)));
            for (int index = 0; index < invalid.length; index++) {
                var identity = PublishedModelId.parse("broken/record" + index);
                raw(path, identity, invalid[index]);
                var read = await(prefs, prefs.readRoaming(U, identity, FIELDS));
                assertEquals(StorageResult.Failure.CONTENT, read.failure(), read.toString());
                var write = await(prefs, prefs.writeRoaming(U, identity, payload(9)));
                assertEquals(StorageResult.Failure.CONTENT, write.failure());
                assertFalse(write.committed());
                assertArrayEquals(invalid[index], rawRead(path, identity));
            }
            raw(path, id("1.1.0"), invalid[0]);
            var result = await(prefs, prefs.restoreRoaming(U, id("1.2.0"), FIELDS).result());
            assertEquals(StorageResult.Failure.CONTENT, result.failure());
            assertEquals(StorageResult.Outcome.MISSING,
                await(prefs, prefs.readRoaming(U, id("1.2.0"), FIELDS)).outcome());
            assertEquals(named(payload(1)), named(found(prefs, prefs.readRoaming(U, id("1.0.0"), FIELDS))));
        }
    }

    @Test
    void validatesWholeLayoutAndWireCapacityAndPreservesIncompatibleRecord() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            var values = new TreeMap<String, Float>();
            for (int index = 0; index < 64; index++)
                values.put("field" + index, (float)index);
            values.remove("field0");
            values.put("中".repeat(10) + "ab", 1f);
            var full = fromMap(values);
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), full));
            assertEquals(named(full), named(found(prefs, prefs.readRoaming(U, id("1.0.0"), values.keySet()))));
            assertEquals(StorageResult.Failure.CONTENT,
                await(prefs, prefs.restoreRoaming(U, id("1.1.0"), FIELDS).result()).failure());
            assertEquals(StorageResult.Failure.CONTENT,
                await(prefs, prefs.readRoaming(U, id("1.0.0"), FIELDS)).failure());
            assertEquals(named(full), named(found(prefs, prefs.readRoaming(U, id("1.0.0"), values.keySet()))));
            committed(prefs, prefs.clearRoaming(U, id("1.0.0")));
            assertTrue(named(found(prefs, prefs.restoreRoaming(U, id("1.0.0"), FIELDS).result())).isEmpty());
            values.put("extra", 1f);
            assertThrows(IllegalArgumentException.class, () -> prefs.writeRoaming(U, id("1.0.0"), fromMap(values)));
            assertThrows(IllegalArgumentException.class,
                () -> prefs.writeRoaming(U, id("1.0.0"), fromMap(Map.of("中".repeat(11), 1f))));
            assertThrows(IllegalArgumentException.class,
                () -> prefs.writeRoaming(U, id("1.0.0"), fromMap(Map.of("value", Float.NaN))));
            assertThrows(IllegalArgumentException.class, () -> prefs.writeRoaming(U, id("1.0.0"),
                new NamedRoamingSnapshot(new String[] {"value"}, new float[0])));
            assertThrows(IllegalArgumentException.class, () -> prefs.writes().offerRoaming(U, id("1.0.0"),
                new NamedRoamingSnapshot(new String[] {"value", "value"}, new float[] {1, 2})));
            assertThrows(IllegalArgumentException.class, () -> prefs.writes().offerRoaming(U, id("1.0.0"),
                new NamedRoamingSnapshot(new String[] {"value", "tail"}, new float[] {1, Float.NEGATIVE_INFINITY})));
            assertTrue(named(found(prefs, prefs.readRoaming(U, id("1.0.0"), FIELDS))).isEmpty());
        }
    }

    @Test
    void readFailuresAndEpochMismatchNeverBecomeMissingOrEmptyWrites() throws Exception {
        var path = temp.resolve("preferences-c2.sqlite");
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(path.getParent(), work);
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(3)));
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
            var statement = connection.createStatement()) {
            statement.execute("UPDATE storage_meta SET compatibility_epoch=99 WHERE id=1");
        }
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(path.getParent(), work);
            assertEquals(StorageResult.Failure.INCOMPATIBLE,
                await(prefs, prefs.restoreRoaming(U, id("1.1.0"), FIELDS).result()).failure());
            assertEquals(StorageResult.Failure.INCOMPATIBLE,
                await(prefs, prefs.clearRoaming(U, id("1.0.0"))).failure());
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
            var statement = connection.createStatement()) {
            statement.execute("UPDATE storage_meta SET compatibility_epoch=2 WHERE id=1");
        }
        try (var work = new DatabaseWork()) {
            var reopened = new PlayerPreferences(path.getParent(), work);
            assertEquals(named(payload(3)), named(found(reopened, reopened.readRoaming(U, id("1.0.0"), FIELDS))));
        }
    }

    @Test
    void unreadableFavouriteAndSelectionCannotBeOverwrittenByValidNewIntent() throws Exception {
        var path = temp.resolve("preferences-c2.sqlite");
        var ordinary = ModelId.parse("owner/model@1.0.0");
        try (var cancelled = NativeCancellation.create();
            var database = NativePlayerData.open(path.getParent(), cancelled)) {
            assertTrue(database.putPreference(NativePlayerData.PreferenceKey.favourite(U, ordinary),
                ArrayBuffer.borrow(new byte[] {2}), 0, cancelled).committed());
            // An intrinsic selection carrying a texture is structurally invalid.
            assertTrue(database.putPreference(NativePlayerData.PreferenceKey.selection(U),
                ArrayBuffer.borrow(new byte[] {1, 0, 7, 'd', 'e', 'f', 'a', 'u', 'l', 't', 0, 1, 'x'}), 0, cancelled)
                .committed());
        }
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(path.getParent(), work);
            assertEquals(StorageResult.Failure.CONTENT, await(prefs, prefs.readFavourite(U, ordinary)).failure());
            assertEquals(StorageResult.Failure.CONTENT,
                await(prefs, prefs.writeFavourite(U, ordinary, true)).failure());
            assertEquals(StorageResult.Failure.CONTENT, await(prefs, prefs.readSelection(U)).failure());
            assertEquals(StorageResult.Failure.CONTENT,
                await(prefs, prefs.writeSelection(U, new PlayerPreferences.LocalSelection(id("1.0.0"), "valid")))
                    .failure());
        }
    }

    private static byte[] longName() {
        return ByteBuffer.allocate(2 + 1 + 33 + 4).put((byte)1).put((byte)1).put((byte)33)
            .put("x".repeat(33).getBytes(java.nio.charset.StandardCharsets.UTF_8)).putFloat(1).array();
    }

    private static void raw(Path path, PublishedModelId identity, byte[] bytes) {
        try (var cancelled = NativeCancellation.create();
            var database = NativePlayerData.open(path.getParent(), cancelled)) {
            assertTrue(database.putPreference(NativePlayerData.PreferenceKey.roaming(U, identity),
                ArrayBuffer.borrow(bytes), 0, cancelled).committed());
        }
    }

    private static byte[] rawRead(Path path, PublishedModelId identity) {
        try (var cancelled = NativeCancellation.create();
            var database = NativePlayerData.open(path.getParent(), cancelled)) {
            var bytes = database.readPreference(NativePlayerData.PreferenceKey.roaming(U, identity), cancelled).rows()
                .get(0).value().nio();
            var result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        }
    }

    static PublishedModelId id(String version) {
        return PublishedModelId.parse("owner/model@" + version);
    }

    static NamedRoamingSnapshot payload(float value) {
        return fromMap(Map.of("value", value));
    }

    static <T> StorageResult<T> await(PlayerPreferences preferences, CompletionStage<StorageResult<T>> stage)
        throws Exception {
        return PreferenceTestDriver.await(preferences, stage);
    }

    static <T> StorageResult<T> await(PlayerPreferences preferences, StorageWriteBuffer.Operation<T> operation)
        throws Exception {
        return PreferenceTestDriver.await(preferences, operation);
    }

    static <T> StorageResult<T> await(PlayerPreferences preferences, StorageQuery<T> query) throws Exception {
        return PreferenceTestDriver.await(preferences, query);
    }

    static <T> T found(PlayerPreferences preferences, StorageQuery<T> query) throws Exception {
        var result = await(preferences, query);
        assertTrue(result.succeeded(), result.toString());
        assertEquals(1, result.rows().size(), result.toString());
        return result.rows().get(0);
    }

    static <T> T found(PlayerPreferences preferences, CompletionStage<StorageResult<T>> stage) throws Exception {
        var result = await(preferences, stage);
        assertTrue(result.succeeded(), result.toString());
        assertEquals(1, result.rows().size(), result.toString());
        return result.rows().get(0);
    }

    static void committed(PlayerPreferences preferences, StorageWriteBuffer.Operation<?> operation) throws Exception {
        var result = PreferenceTestDriver.await(preferences, operation);
        assertTrue(result.committed(), result.toString());
    }
}
