package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.Hash256;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInfoCapabilityTest {
    @Test
    void deliveryOpportunityConsumesTheObservedBusinessChange() {
        var capability = new ModelInfoCapability();

        capability.setModelAndTexture(hash(1), "first");

        assertTrue(capability.consumeDirty());
        assertFalse(capability.consumeDirty());

        capability.setDisabled(true);
        assertTrue(capability.consumeDirty());
    }

    @Test
    void commandSelectionKeepsItsPrivateModeOnlyForTheSameModel() {
        var first = hash(1);
        var second = hash(2);
        var capability = new ModelInfoCapability();

        capability.setCommandSelection(first, "first", true);
        assertTrue(capability.isMandatory());
        assertTrue(capability.ignoresGrantsFor(first));

        capability.setModelAndTexture(first, "second");
        assertTrue(capability.ignoresGrantsFor(first));

        capability.setModelAndTexture(second, "first");
        assertFalse(capability.ignoresGrantsFor(first));
        assertFalse(capability.ignoresGrantsFor(second));

        capability.setCommandSelection(second, "first", true);
        capability.setCommandSelection(second, "first", false);
        assertFalse(capability.ignoresGrantsFor(second));
    }

    @Test
    void cloneAndNbtRoundTripKeepTheForcedSelection() {
        var selected = hash(3);
        var source = new ModelInfoCapability();
        source.setCommandSelection(selected, "skin", true);

        var clone = new ModelInfoCapability();
        clone.moveFrom(source);
        assertTrue(clone.ignoresGrantsFor(selected));

        var restored = new ModelInfoCapability();
        restored.deserializeNBT(source.serializeNBT());
        assertEquals(selected, restored.getModelId());
        assertEquals("skin", restored.getSelectTexture());
        assertTrue(restored.ignoresGrantsFor(selected));
    }

    @Test
    void invalidPersistedModelFailsClosedWithoutBypass() {
        var tag = new CompoundTag();
        tag.putString("model_hash", "invalid");
        tag.putBoolean("ignore_grants", true);

        var restored = new ModelInfoCapability();
        restored.deserializeNBT(tag);

        assertNull(restored.getModelId());
        assertFalse(restored.ignoresGrantsFor(hash(1)));
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }
}
