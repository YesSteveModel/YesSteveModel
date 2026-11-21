package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.ModelId;
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
        assertFalse(capability.consumeDirty());
        assertTrue(capability.consumeFlagsDirty());
        assertFalse(capability.consumeFlagsDirty());
    }

    @Test
    void commandSelectionKeepsOnlyTheOrdinarySelectionAndMandatoryFlag() {
        var capability = new ModelInfoCapability();
        capability.setCommandSelection(hash(1), "first");
        assertTrue(capability.isMandatory());
        assertFalse(capability.serializeNBT().contains("ignore_grants"));
        capability.setModelAndTexture(hash(2), "second");
        assertEquals(PublishedModelId.model(hash(2)), capability.getModelId());
    }

    @Test
    void cloneAndNbtRoundTripKeepTheForcedSelection() {
        var selected = hash(3);
        var source = new ModelInfoCapability();
        source.setCommandSelection(selected, "skin");

        var clone = new ModelInfoCapability();
        clone.moveFrom(source);
        assertNull(source.getModelId());
        assertEquals(PublishedModelId.model(selected), clone.getModelId());

        var restored = new ModelInfoCapability();
        restored.deserializeNBT(clone.serializeNBT());
        assertEquals(PublishedModelId.model(selected), restored.getModelId());
        assertEquals("skin", restored.getSelectTexture());
        assertFalse(restored.serializeNBT().contains("ignore_grants"));
    }

    @Test
    void persistenceKeepsIntrinsicAndOrdinaryDefaultDistinct() {
        var capability = new ModelInfoCapability();
        capability.setModelSelection(PublishedModelId.INTRINSIC_DEFAULT, "skin");
        assertEquals("default", capability.serializeNBT().getString("model_id"));
        var restored = new ModelInfoCapability();
        restored.deserializeNBT(capability.serializeNBT());
        assertEquals(PublishedModelId.INTRINSIC_DEFAULT, restored.getModelId());
        capability.setModelAndTexture(hash(3), "skin");
        assertEquals(hash(3).toString(), capability.serializeNBT().getString("model_id"));
        restored.deserializeNBT(capability.serializeNBT());
        assertEquals(PublishedModelId.model(hash(3)), restored.getModelId());
    }

    @Test
    void invalidPersistedModelFailsClosedWithoutBypass() {
        var tag = new CompoundTag();
        tag.putString("model_id", "invalid");
        tag.putBoolean("ignore_grants", true);

        var restored = new ModelInfoCapability();
        restored.deserializeNBT(tag);

        assertNull(restored.getModelId());
        assertFalse(restored.serializeNBT().contains("ignore_grants"));
    }

    private static ModelId hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return ModelId.fromHash(new Hash256(bytes));
    }
}
