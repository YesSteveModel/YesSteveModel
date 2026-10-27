package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoamingVariableStoreTest {
    private static final Hash256 MODEL_HASH = new Hash256(new byte[Hash256.SIZE]);

    @Test
    void appliesDeferredAccessToTheFirstSelectedModel() {
        var store = new RoamingVariableStore();
        store.execute(null, values -> values.put("queued", 2.5F));

        assertEquals(2.5F, store.variables(MODEL_HASH).getFloat("queued"));
    }

    @Test
    void roundTripsPersistentVariables() {
        var source = new RoamingVariableStore();
        source.variables(MODEL_HASH).put("speed", 1.25F);

        var restored = new RoamingVariableStore();
        restored.deserialize(source.serialize());

        assertEquals(1.25F, restored.variables(MODEL_HASH).getFloat("speed"));
    }

    @Test
    void moveTransfersOwnershipAndClearsTheSource() {
        var source = new RoamingVariableStore();
        source.variables(MODEL_HASH).put("value", 3F);
        var destination = new RoamingVariableStore();

        destination.moveFrom(source);

        assertEquals(3F, destination.variables(MODEL_HASH).getFloat("value"));
        assertFalse(source.serialize().contains(String.valueOf(MODEL_HASH.roamingHash())));
    }

    @Test
    void fullReplacementRemovesVariablesMissingFromTheSnapshot() {
        var store = new RoamingVariableStore();
        store.variables(MODEL_HASH).put("old", 1F);
        var replacement = new Object2FloatOpenHashMap<String>();
        replacement.put("current", 2F);

        store.replace(MODEL_HASH.roamingHash(), replacement);

        assertFalse(store.variables(MODEL_HASH).containsKey("old"));
        assertEquals(2F, store.variables(MODEL_HASH).getFloat("current"));
    }
}
