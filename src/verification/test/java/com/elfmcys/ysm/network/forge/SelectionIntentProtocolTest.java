package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.CommittedSelection;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.protocol.*;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectionIntentProtocolTest {
    @Test
    void completeSelectionRoundTripsPositiveIntentAndDeclarationDictionary() throws Exception {
        var message = ClientProtocolGateway.prepareSelection(42, new ClientDemandCoordinator.Target(null, "unknown"),
            Long.MAX_VALUE, new NamedRoamingSnapshot(new String[] {"a"}, new float[] {-0F}),
            new RoamingDictionary(new String[] {"a", "missing"}));
        try (var bytes = ProtocolBuffer.serialize(message)) {
            var selected = SelectionCodec
                .read(ProtocolBuffer.parse(bytes, SelectionChangeProto::parseFrom).selectionUnsafe(), null);
            assertEquals(Long.MAX_VALUE, selected.intentId());
            assertEquals("unknown", selected.texture());
            assertArrayEquals(new String[] {"a", "missing"}, selected.dictionary().names());
            var roaming = selected.roaming().snapshot();
            assertArrayEquals(new short[] {0, 1}, roaming.ids());
            assertEquals(Float.floatToRawIntBits(-0F), Float.floatToRawIntBits(roaming.values()[0]));
            assertEquals(0, Float.floatToRawIntBits(roaming.values()[1]));
        }
    }

    @Test
    void negativeIntentsAndInvalidInitialNamesFailBeforeLocalCommit() {
        assertThrows(IllegalArgumentException.class,
            () -> ClientProtocolGateway.prepareSelection(42, new ClientDemandCoordinator.Target(null, ""), -1,
                new NamedRoamingSnapshot(new String[0], new float[0]), new RoamingDictionary(new String[0])));
        assertThrows(IllegalArgumentException.class,
            () -> ClientProtocolGateway.prepareSelection(42, new ClientDemandCoordinator.Target(null, ""), 1,
                new NamedRoamingSnapshot(new String[] {"unknown"}, new float[] {1}),
                new RoamingDictionary(new String[] {"a"})));
    }

    @Test
    void noProducerRequiresEmptyDictionaryAndValues() {
        assertThrows(IllegalArgumentException.class,
            () -> new CommittedSelection(com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, 0, null,
                new RoamingDictionary(new String[] {"a"}), new RoamingValues(new short[0], new float[0]), "", ""));
    }
}
