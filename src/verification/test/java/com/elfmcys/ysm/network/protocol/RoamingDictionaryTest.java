package com.elfmcys.ysm.network.protocol;

import static com.elfmcys.ysm.expression.TestRoamingPayloads.named;
import static org.junit.jupiter.api.Assertions.*;

import dev.semaweave.RoamingPayload;
import java.util.Map;
import java.util.List;
import com.elfmcys.ysm.proto.network.RoamingStateProto;
import org.junit.jupiter.api.Test;

final class RoamingDictionaryTest {
    @Test
    void numberingIsLocalAndMappingKeepsOnlyDeclaredNames() {
        var left = new RoamingDictionary(new String[] {"beta", "alpha"});
        var right = new RoamingDictionary(new String[] {"alpha", "beta"});
        var leftPayload = new RoamingPayload(new short[] {1, 0}, new float[] {1, 2});
        var rightPayload = new RoamingPayload(new short[] {0, 1}, new float[] {1, 2});
        assertArrayEquals(new short[] {1, 0}, left.encode(leftPayload).ids());
        assertArrayEquals(new short[] {0, 1}, right.encode(rightPayload).ids());
        var fields = List.of("gamma", "beta");
        var mapping = left.mapTo(fields);
        var mapped = mapping.decode(left.encode(leftPayload));
        assertEquals(Map.of("beta", 2F), named(mapped, fields));
        assertEquals(Map.of("beta", 2F), named(right.mapTo(fields).decode(right.encode(rightPayload)), fields));
        assertEquals(Map.of("beta", 4F),
            named(mapping.decode(new RoamingValues(new short[] {0}, new float[] {4})), fields));
        assertArrayEquals(new short[] {1}, mapped.fields());
    }

    @Test
    void declarationsPreserveLayoutOrderAndDoNotBorrowTheCallerCollection() {
        var fields = new java.util.ArrayList<>(List.of("beta", "alpha"));
        var dictionary = RoamingDictionary.declarations(fields);
        fields.clear();
        assertArrayEquals(new String[] {"beta", "alpha"}, dictionary.names());
        assertEquals(0, dictionary.encode(new RoamingPayload(new short[0], new float[0])).ids().length);
    }

    @Test
    void wholeBatchValidationPrecedesProjectionAndFiltering() {
        var dictionary = new RoamingDictionary(new String[] {"alpha", "unknown"});
        var projection = new RoamingProjection(dictionary, new RoamingValues(new short[] {0}, new float[] {3}));
        for (var invalid : new RoamingStateProto[] {
            RoamingStateProto.newBuilder().addIds(0, 2).addValues(9F, 8F).build(),
            RoamingStateProto.newBuilder().addIds(0, 1).addValues(9F, Float.NaN).build()}) {
            assertThrows(IllegalArgumentException.class, () -> {
                var decoded = SelectionCodec.read(invalid, dictionary);
                projection.apply(decoded, true);
                dictionary.mapTo(List.of("alpha")).decode(decoded);
            });
            assertArrayEquals(new short[] {0, 1}, projection.snapshot().ids());
            assertArrayEquals(new float[] {3, 0}, projection.snapshot().values());
        }
    }

    @Test
    void denseZeroBaselineAndStableSnapshotsPreserveSignedZero() {
        var dictionary = new RoamingDictionary(new String[] {"alpha", "beta", "missing"});
        var projection =
            new RoamingProjection(dictionary, new RoamingValues(new short[] {0, 1}, new float[] {0F, -0F}));
        var initial = projection.snapshot();
        assertArrayEquals(new short[] {0, 1, 2}, initial.ids());
        assertEquals(0, Float.floatToRawIntBits(initial.values()[0]));
        assertEquals(0x80000000, Float.floatToRawIntBits(initial.values()[1]));
        projection.apply(new RoamingValues(new short[] {0}, new float[] {7}), false);
        assertArrayEquals(new float[] {0F, -0F, 0F}, initial.values());
        assertArrayEquals(new float[] {7F, -0F, 0F}, projection.snapshot().values());
        projection.apply(new RoamingValues(new short[0], new float[0]), false);
        assertArrayEquals(new short[] {0, 1, 2}, projection.snapshot().ids());
        projection.apply(new RoamingValues(new short[0], new float[0]), true);
        assertArrayEquals(new float[] {0F, 0F, 0F}, projection.snapshot().values());
    }

    @Test
    void numericStructureRejectsInvalidBatches() {
        assertThrows(IllegalArgumentException.class,
            () -> SelectionCodec.read(RoamingStateProto.newBuilder().addIds(0).build()));
        assertThrows(IllegalArgumentException.class,
            () -> SelectionCodec.read(RoamingStateProto.newBuilder().addIds(0, 0).addValues(1F, 2F).build()));
        for (int invalid : new int[] {-1, 64, 65536})
            assertThrows(IllegalArgumentException.class,
                () -> SelectionCodec.read(RoamingStateProto.newBuilder().addIds(invalid).addValues(1F).build()));
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,
                () -> SelectionCodec.read(RoamingStateProto.newBuilder().addIds(0).addValues(invalid).build()));
        assertThrows(IllegalArgumentException.class, () -> SelectionCodec
            .read(RoamingStateProto.newBuilder().addIds(new int[65]).addValues(new float[65]).build()));
        var names = new String[64];
        var ids = new short[64];
        var values = new float[64];
        for (short index = 0; index < names.length; index++) {
            names[index] = "field" + index;
            ids[index] = index;
            values[index] = index;
        }
        var dictionary = new RoamingDictionary(names);
        var decoded = SelectionCodec.read(SelectionCodec.write(new RoamingValues(ids, values)), dictionary);
        assertEquals(64, new RoamingProjection(dictionary, decoded).snapshot().ids().length);
    }

    @Test
    void dictionaryRejectsInvalidNamesAndLimitsUtf8Bytes() {
        assertThrows(IllegalArgumentException.class, () -> new RoamingDictionary(new String[] {"alpha", "alpha"}));
        for (var invalid : new String[] {"", "x".repeat(33), "中".repeat(11), "\uD800"})
            assertThrows(IllegalArgumentException.class, () -> new RoamingDictionary(new String[] {invalid}));
        assertThrows(NullPointerException.class, () -> new RoamingDictionary(new String[] {null}));
        assertThrows(IllegalArgumentException.class, () -> new RoamingDictionary(new String[65]));
        assertEquals(1, new RoamingDictionary(new String[] {"中".repeat(10) + "ab"}).size());
    }
}
